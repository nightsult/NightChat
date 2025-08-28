package org.night.nightchat.chat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.night.nightchat.Nightchat;
import org.night.nightchat.config.GlobalConfig;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageFilterService {

    private final GlobalConfig cfg;

    // Precompiled replacer rules
    private static class Rule {
        final Pattern pattern;
        final String replacement;
        Rule(Pattern p, String r) { this.pattern = p; this.replacement = r; }
    }
    private List<Rule> rules = List.of();

    // Domain detection patterns
    // Full domains: optional scheme/www, capture domain
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(?i)\\b(?:(?:https?://)?(?:www\\.)?)" +
                    "([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9]))+)\\b"
    );

    // Bypass with spaced dot: "youtube .com" or "youtube. com"
    private static final Pattern SPACED_DOT_PATTERN = Pattern.compile(
            "(?i)\\b([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9]))*)\\s*\\.\\s*([a-z]{2,63})\\b"
    );

    public MessageFilterService(GlobalConfig cfg) {
        this.cfg = cfg;
    }

    public void rebuildFromConfig() {
        if (!cfg.replaceEnable || cfg.replacers == null || cfg.replacers.isEmpty()) {
            this.rules = List.of();
            return;
        }
        List<Rule> list = new ArrayList<>();
        for (String line : cfg.replacers) {
            if (line == null) continue;
            String s = line.trim();
            if (s.isEmpty()) continue;
            int idx = s.indexOf("->");
            if (idx <= 0) continue;
            String left = s.substring(0, idx).trim();
            String right = s.substring(idx + 2).trim();
            if (left.isEmpty()) continue;
            // tokens separated by comma
            String[] tokens = left.split(",");
            StringBuilder alt = new StringBuilder();
            for (String t : tokens) {
                String tok = t.trim();
                if (tok.isEmpty()) continue;
                if (alt.length() > 0) alt.append("|");
                // Escape regex
                alt.append(Pattern.quote(tok));
            }
            if (alt.length() == 0) continue;

            // Use case-insensitive, word-ish boundaries when token is alnum/underscore,
            // otherwise allow raw (emotes like :smile:)
            // We wrap with (?<!\\w) and (?!\\w) to avoid mid-word replacements for word tokens.
            Pattern p = Pattern.compile("(?i)(?<!\\w)(?:" + alt + ")(?!\\w)");
            list.add(new Rule(p, right));
        }
        this.rules = List.copyOf(list);
    }

    public static class Result {
        public final boolean canceled;
        public final String message;
        public final String reason;
        public Result(boolean canceled, String message, String reason) {
            this.canceled = canceled; this.message = message; this.reason = reason;
        }
        public static Result ok(String msg) { return new Result(false, msg, null); }
        public static Result cancel(String reason) { return new Result(true, null, reason); }
    }

    public Result process(ServerPlayer sender, Channel channel, String input, MinecraftServer server) {
        String m = input;

        // Replace pipeline
        if (cfg.replaceEnable && cfg.replaceEnableDefault) {
            if (cfg.fixMessage) {
                m = normalizeSpaces(m);
            }
            if (!rules.isEmpty()) {
                for (Rule r : rules) {
                    m = r.pattern.matcher(m).replaceAll(r.replacement);
                }
            }
            if (cfg.capsMessage) {
                m = capitalizeAndPunctuate(m);
            }
        }

        // Capslock control (uses global thresholds; gated by channel.preventCapsLock)
        if (channel.preventCapslock && cfg.capslockEnable && m.length() >= cfg.capslockMinLength) {            int letters = 0, uppers = 0;
            for (int i = 0; i < m.length(); i++) {
                char c = m.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                    if (Character.isUpperCase(c)) uppers++;
                }
            }
            if (letters >= cfg.capslockMinLength) {
                double perc = letters == 0 ? 0.0 : (uppers * 100.0 / letters);
                if (perc > cfg.capslockPercentage) {
                    m = m.toLowerCase(Locale.ROOT);
                    if (cfg.capsMessage) {
                        m = capitalizeAndPunctuate(m);
                    }
                }
            }
        }

        // URL filter
        if (cfg.urlsEnable) {
            String bad = findBlockedDomain(m, cfg.allowedDomains, cfg.urlsConcatenate);
            if (bad != null) {
                runPunishment(server, sender, cfg.urlsPunishmentCommand);
                return Result.cancel("URL blocked: " + bad);
            }
        }

        return Result.ok(m);
    }

    private String normalizeSpaces(String s) {
        String m = s.trim();
        // collapse whitespace
        m = m.replaceAll("\\s+", " ");
        // normalize around punctuation: no space before, one space after
        m = m.replaceAll("\\s*([,.!?;:])\\s*", "$1 ");
        // remove duplicate spaces again
        m = m.replaceAll("\\s{2,}", " ");
        return m.trim();
    }

    private String capitalizeAndPunctuate(String s) {
        if (s.isEmpty()) return s;
        char first = s.charAt(0);
        if (Character.isLetter(first)) {
            s = Character.toUpperCase(first) + (s.length() > 1 ? s.substring(1) : "");
        }
        // ensure ends with punctuation . ! ? (unless already has one)
        if (!s.endsWith(".") && !s.endsWith("!") && !s.endsWith("?")) {
            s = s + ".";
        }
        return s;
    }

    private String findBlockedDomain(String message, Set<String> allowed, boolean detectConcatenate) {
        // Direct domains
        Matcher m = DOMAIN_PATTERN.matcher(message);
        while (m.find()) {
            String domain = m.group(1).toLowerCase(Locale.ROOT);
            if (!isAllowedDomain(domain, allowed)) {
                return domain;
            }
        }
        if (detectConcatenate) {
            // Detect patterns like "example .com" or "example. com"
            Matcher spaced = SPACED_DOT_PATTERN.matcher(message);
            while (spaced.find()) {
                String domain = (spaced.group(1) + "." + spaced.group(2)).toLowerCase(Locale.ROOT);
                if (!isAllowedDomain(domain, allowed)) {
                    return domain;
                }
            }
        }
        return null;
    }

    private boolean isAllowedDomain(String domain, Set<String> allowed) {
        // Allowed if endsWith any allowed domain (so subdomains are okay)
        for (String a : allowed) {
            if (domain.equals(a) || domain.endsWith("." + a)) {
                return true;
            }
        }
        return false;
    }

    private void runPunishment(MinecraftServer server, ServerPlayer sender, String cmdTemplate) {
        try {
            String cmd = cmdTemplate
                    .replace("@player", sender.getGameProfile().getName())
                    .replace("@uuid", sender.getUUID().toString());
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4), cmd);
        } catch (Exception e) {
            Nightchat.LOGGER.warn("Failed to execute punishment command: {}", e.toString());
        }
    }
}