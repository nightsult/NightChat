package org.night.nightchat.chat;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.night.nightchat.config.GlobalConfig;
import org.night.nightchat.integration.LuckPermsHook;
import org.night.nightchat.integration.NightEconomyHook;
import org.night.nightchat.persist.PlayerState;
import org.night.nightchat.persist.PlayerStateStore;
import org.night.nightchat.util.NumberUtil;
import org.night.nightchat.util.TextUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatService {
    private final ChannelManager channels;
    private final LuckPermsHook luckPerms;
    private final NightEconomyHook economy;
    private final GlobalConfig config;
    private final MessageFilterService filters;
    private final PlayerStateStore stateStore;

    private final Map<UUID, Set<String>> mutedChannels = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> mutedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> spyChannels = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignoredPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> nextSpeakAtNanos = new ConcurrentHashMap<>();

    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<!\\w)@([A-Za-z0-9_]{3,16})");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([a-z0-9_]+)}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT_TOKEN = Pattern.compile("%([a-z0-9_]+)(?:_([a-z0-9_]+))?(?:_([a-z0-9_]+))?%", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_TOKEN = Pattern.compile("^([!@]|\\S+)\\s+(.*)$");

    public ChatService(ChannelManager channels, LuckPermsHook lp, NightEconomyHook economy,
                       GlobalConfig config, MessageFilterService filters, PlayerStateStore store) {
        this.channels = channels;
        this.luckPerms = lp;
        this.economy = economy;
        this.config = config;
        this.filters = filters;
        this.stateStore = store;
    }

    public void register() {
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);
    }

    public void unregister() {}

    private void applyState(UUID uuid, PlayerState s) {
        mutedChannels.put(uuid, ConcurrentHashMap.newKeySet());
        spyChannels.put(uuid, ConcurrentHashMap.newKeySet());
        mutedPlayers.put(uuid, ConcurrentHashMap.newKeySet());
        ignoredPlayers.put(uuid, ConcurrentHashMap.newKeySet());

        mutedChannels.get(uuid).addAll(lowercaseAll(s.mutedChannels));
        spyChannels.get(uuid).addAll(lowercaseAll(s.spyChannels));
        mutedPlayers.get(uuid).addAll(s.mutedPlayers);
        ignoredPlayers.get(uuid).addAll(s.ignoredPlayers);
    }

    private PlayerState snapshotState(UUID uuid) {
        PlayerState s = new PlayerState();
        s.mutedChannels.addAll(lowercaseAll(mutedChannels.getOrDefault(uuid, Set.of())));
        s.spyChannels.addAll(lowercaseAll(spyChannels.getOrDefault(uuid, Set.of())));
        s.mutedPlayers.addAll(mutedPlayers.getOrDefault(uuid, Set.of()));
        s.ignoredPlayers.addAll(ignoredPlayers.getOrDefault(uuid, Set.of()));
        return s;
    }

    private Collection<String> lowercaseAll(Collection<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String x : in) out.add(x.toLowerCase(Locale.ROOT));
        return out;
    }

    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        PlayerState loaded = stateStore.load(p);
        applyState(p.getUUID(), loaded);
    }

    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        PlayerState snap = snapshotState(p.getUUID());
        stateStore.save(p, snap);
    }

    public void flushAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerState snap = snapshotState(p.getUUID());
            stateStore.save(p, snap);
        }
    }

    public boolean toggleMuteChannel(ServerPlayer player, String channelId) {
        String id = channelId.toLowerCase(Locale.ROOT);
        mutedChannels.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        Set<String> set = mutedChannels.get(player.getUUID());
        boolean nowMuted;
        if (set.contains(id)) { set.remove(id); nowMuted = false; } else { set.add(id); nowMuted = true; }
        stateStore.save(player, snapshotState(player.getUUID()));
        return nowMuted;
    }

    public boolean toggleSpyChannel(ServerPlayer player, String channelId) {
        String id = channelId.toLowerCase(Locale.ROOT);
        spyChannels.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        Set<String> set = spyChannels.get(player.getUUID());
        boolean nowOn;
        if (set.contains(id)) { set.remove(id); nowOn = false; } else { set.add(id); nowOn = true; }
        stateStore.save(player, snapshotState(player.getUUID()));
        return nowOn;
    }

    public boolean toggleIgnore(ServerPlayer player, ServerPlayer target) {
        ignoredPlayers.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        Set<UUID> set = ignoredPlayers.get(player.getUUID());
        boolean nowIgnored;
        if (set.contains(target.getUUID())) { set.remove(target.getUUID()); nowIgnored = false; } else { set.add(target.getUUID()); nowIgnored = true; }
        stateStore.save(player, snapshotState(player.getUUID()));
        return nowIgnored;
    }

    public boolean toggleMutePlayer(ServerPlayer player, ServerPlayer target) {
        mutedPlayers.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        Set<UUID> set = mutedPlayers.get(player.getUUID());
        boolean nowMuted;
        if (set.contains(target.getUUID())) { set.remove(target.getUUID()); nowMuted = false; } else { set.add(target.getUUID()); nowMuted = true; }
        stateStore.save(player, snapshotState(player.getUUID()));
        return nowMuted;
    }

    public boolean isIgnoring(UUID who, UUID target) {
        return ignoredPlayers.getOrDefault(who, Set.of()).contains(target);
    }

    private boolean hasMutedChannel(UUID who, String channelId) {
        return mutedChannels.getOrDefault(who, Set.of()).contains(channelId.toLowerCase(Locale.ROOT));
    }

    private boolean hasMutedPlayer(UUID who, UUID target) {
        return mutedPlayers.getOrDefault(who, Set.of()).contains(target);
    }

    private boolean hasSpy(UUID who, String channelId) {
        return spyChannels.getOrDefault(who, Set.of()).contains(channelId.toLowerCase(Locale.ROOT));
    }

    private boolean canUseChannel(ServerPlayer p, Channel c) {
        return luckPerms.hasPermission(p, c.permission) || (c.type != ChannelType.STAFF && p.hasPermissions(0));
    }

    private boolean canSeeStaff(ServerPlayer p) {
        return luckPerms.hasPermission(p, "nightchat.channel.staff") || p.hasPermissions(2);
    }

    private boolean hasBypassDelay(ServerPlayer p, String channelId) {
        return luckPerms.hasPermission(p, "nightchat.bypass.delay")
                || luckPerms.hasPermission(p, "nightchat.bypass.delay." + channelId.toLowerCase(Locale.ROOT));
    }

    private boolean isBlockedByIgnore(UUID viewer, UUID sender, ChannelType type) {
        boolean ignoring = isIgnoring(viewer, sender) || hasMutedPlayer(viewer, sender);
        if (!ignoring) return false;
        if (type == ChannelType.GLOBAL && !config.ignoreGlobalMessages) return false;
        return true;
    }

    private static final class Parsed {
        final Channel channel;
        final String message;
        Parsed(Channel c, String m) { this.channel = c; this.message = m; }
    }

    private Parsed parseIncoming(ServerPlayer sender, String raw) {
        if (raw == null || raw.isBlank()) {
            return new Parsed(channels.get("local") != null ? channels.get("local") : channels.require("local"), "");
        }
        Matcher mat = FIRST_TOKEN.matcher(raw);
        if (mat.find()) {
            String head = mat.group(1);
            String tail = mat.group(2) == null ? "" : mat.group(2).trim();

            if ("!".equals(head)) {
                Channel g = channels.get("global");
                if (g != null && canUseChannel(sender, g)) return new Parsed(g, tail);
            } else if ("@".equals(head)) {
                Channel s = channels.get("staff");
                if (s != null && canUseChannel(sender, s)) return new Parsed(s, tail);
            } else {
                String token = head.toLowerCase(Locale.ROOT);
                for (Channel c : channels.all()) {
                    if (c.commands.stream().anyMatch(a -> a.equalsIgnoreCase(token))) {
                        if (canUseChannel(sender, c)) {
                            return new Parsed(c, tail);
                        }
                    }
                }
            }
        }
        Channel local = channels.get("local");
        return new Parsed(local != null ? local : channels.require("local"), raw);
    }

    // PLACEHOLDERS BÁSICOS
    private Map<String, String> buildPlaceholders(Channel c, ServerPlayer sender, String rawMessage) {
        Map<String, String> m = new HashMap<>();
        String prefix = luckPerms.getPrefix(sender);
        String suffix = luckPerms.getSuffix(sender);
        String name = sender.getGameProfile().getName();

        m.put("channel", c.id.substring(0,1).toUpperCase() + c.id.substring(1));
        m.put("prefix", (prefix == null || prefix.isBlank()) ? "" : prefix);
        m.put("suffix", (suffix == null || suffix.isBlank()) ? "" : suffix);
        m.put("nick", name);
        m.put("message", rawMessage);

        if (c.currencyEnabled && economy.isReady()) {
            double bal = economy.getBalance(sender, c.currencyId);
            m.put("money", NumberUtil.formatCompact(bal));

            String tycoonTag = economy.getTycoonTagIfSelf(sender, c.currencyId);
            m.put("money_tycoon", tycoonTag == null ? "" : tycoonTag);
        } else {
            m.put("money", "");
            m.put("money_tycoon", "");
        }
        return m;
    }

    private Map<String, String> buildPlaceholders(Channel c, ServerPlayer sender, String rawMessage, boolean applyTransforms) {
        String msg = applyTransforms ? applyChannelTransformations(c, rawMessage) : rawMessage;
        Map<String, String> base = buildPlaceholders(c, sender, msg);
        base.put("message", msg);
        return base;
    }

    // Expansão %...% (PlaceholderAPI-like) no texto
// Expansão %...% (PlaceholderAPI-like) no texto
    private String expandPercentTokens(String in, Channel c, ServerPlayer sender) {
        if (in == null || in.isEmpty()) return "";
        Matcher matcher = PERCENT_TOKEN.matcher(in);
        StringBuffer out = new StringBuffer();

        while (matcher.find()) {
            String aRaw = matcher.group(1); // pode vir completo ex.: "nighteconomy_money_tycoon"
            String b = matcher.group(2);
            String c3 = matcher.group(3);

            // Ajuste contra ganância do primeiro grupo: se b/c3 nulos e houver "_", dividir manualmente
            String a = aRaw;
            if ((b == null || b.isEmpty()) && (c3 == null || c3.isEmpty()) && aRaw != null && aRaw.indexOf('_') != -1) {
                String[] parts = aRaw.split("_", 3);
                a = parts[0];
                if (parts.length > 1) b = parts[1];
                if (parts.length > 2) c3 = parts[2];
            }

            String replacement;

            if ("player".equalsIgnoreCase(a) && "click".equalsIgnoreCase(b)) {
                replacement = "%player_click%";
            } else if ("player_click".equalsIgnoreCase(a)) {
                replacement = "%player_click%";
            } else if (("luckperms".equalsIgnoreCase(a) && "prefix".equalsIgnoreCase(b)) || "luckperms_prefix".equalsIgnoreCase(a)) {
                String p = luckPerms.getPrefix(sender);
                replacement = p == null ? "" : p;
            } else if (("luckperms".equalsIgnoreCase(a) && "suffix".equalsIgnoreCase(b)) || "luckperms_suffix".equalsIgnoreCase(a)) {
                String s = luckPerms.getSuffix(sender);
                replacement = s == null ? "" : s;
            } else if ("player".equalsIgnoreCase(a)) {
                replacement = sender.getGameProfile().getName();
            } else if ("nighteconomy".equalsIgnoreCase(a) && b != null) {
                String cur = b.toLowerCase(Locale.ROOT);
                String suff = c3 == null ? "" : c3.toLowerCase(Locale.ROOT);

                // TAG do Tycoon do REMETENTE (se não for tycoon, retorna vazio)
                if ("tycoon".equals(suff) || "tag".equals(suff)) {
                    String t = economy.isReady() ? economy.getTycoonTagIfSelf(sender, cur) : "";
                    replacement = (t == null) ? "" : t;
                }
                // Saldo do remetente (aceita _balance ou vazio)
                else if ("balance".equals(suff) || suff.isEmpty()) {
                    double bal = economy.isReady() ? economy.getBalance(sender, cur) : 0.0D;
                    replacement = NumberUtil.formatCompact(bal);
                }
                // Qualquer outro sufixo desconhecido de nighteconomy não vaza literal
                else {
                    replacement = "";
                }
            } else {
                // Placeholder desconhecido: mantemos como veio
                replacement = matcher.group(0);
            }

            // Escapar replacement para não interpretar $n e '\' como backreferences
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String lstripSpaces(String s) {
        int i = 0, n = s.length();
        while (i < n && s.charAt(i) == ' ') i++;
        return i == 0 ? s : s.substring(i);
    }
    private static String rstripSpaces(String s) {
        int i = s.length();
        while (i > 0 && s.charAt(i - 1) == ' ') i--;
        return i == s.length() ? s : s.substring(0, i);
    }

    private static boolean containsUnexpandedPercent(String s) {
        return s != null && s.indexOf('%') != -1 && PERCENT_TOKEN.matcher(s).find();
    }

    private String joinWithSingleSpace(StringBuilder buf, String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        boolean bufEndsWithSpace = buf.length() > 0 && buf.charAt(buf.length() - 1) == ' ';
        boolean sStartsWithSpace = s.charAt(0) == ' ';

        if (bufEndsWithSpace && sStartsWithSpace) {
            int i = 0;
            while (i < s.length() && s.charAt(i) == ' ') i++;
            return s.substring(i); // remove os espaços extras à esquerda
        }
        if (!bufEndsWithSpace && !sStartsWithSpace && buf.length() > 0) {
            return " " + s; // injeta um espaço de separação
        }
        return s;
    }

    private Component parseFormatToComponent(Channel c, String format, Map<String, String> ph, ServerPlayer sender) {
        if (format == null) format = "";

        Component result = Component.empty();
        StringBuilder plainBuf = new StringBuilder();

        Matcher m = TOKEN_PATTERN.matcher(format);
        int last = 0;
        boolean prevEmptyToken = false;

        while (m.find()) {
            String token = m.group(1);

            // Texto antes do token
            String chunk = format.substring(last, m.start());
            // Não removemos espaços à esquerda aqui; o joinWithSingleSpace normaliza
            if (!chunk.isEmpty()) {
                chunk = expandPercentTokens(chunk, c, sender);
                chunk = joinWithSingleSpace(plainBuf, chunk);
                plainBuf.append(chunk);
            }

            // Resolver token
            TagDefinition tag = c.getTag(token);
            boolean tokenEmpty;
            Component tokenComp = null;
            String tokenVal = null;

            if (tag != null) {
                // Permissão do Tag
                if (tag.permission != null && !tag.permission.isBlank() && !luckPerms.hasPermission(sender, tag.permission)) {
                    tokenEmpty = true;
                } else {
                    // Preferimos valor do placeholder explícito; se vazio, caímos para hover[0]
                    String fromPH = expandPercentTokens(ph.getOrDefault(token, ""), c, sender);
                    String fromHover = (!tag.hover.isEmpty()) ? expandPercentTokens(tag.hover.get(0), c, sender) : "";

                    // Como o espaçamento vem do format, limpamos bordas do conteúdo do tag
                    if (fromPH != null) fromPH = fromPH.strip();
                    if (fromHover != null) fromHover = fromHover.strip();

                    String baseText = (fromPH != null && !fromPH.isEmpty()) ? fromPH
                            : (fromHover != null ? fromHover : "");

                    if (baseText == null || baseText.isEmpty()) {
                        tokenEmpty = true;
                    } else {
                        Component comp = TextUtil.legacyToComponent(baseText);

                        // Tooltip: SOMENTE 'suggest'
                        List<String> suggestLines = new ArrayList<>();
                        for (String s : tag.suggest) {
                            String line = expandPercentTokens(s, c, sender);
                            if (!line.isBlank()) suggestLines.add(line);
                        }
                        if (!suggestLines.isEmpty()) {
                            String hoverJoined = String.join("\n", suggestLines);
                            comp = comp.copy().withStyle(style ->
                                    style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextUtil.legacyToComponent(hoverJoined)))
                            );
                        }
                        if (!tag.suggestCommand.isEmpty()) {
                            String cmd = expandPercentTokens(tag.suggestCommand.get(0), c, sender);
                            if (!cmd.isBlank()) {
                                comp = comp.copy().withStyle(style ->
                                        style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmd)));
                            }
                        }

                        tokenComp = comp;
                        tokenEmpty = false;
                    }
                }
            } else {
                tokenVal = expandPercentTokens(ph.getOrDefault(token, ""), c, sender);
                if (tokenVal == null) tokenVal = "";
                tokenVal = joinWithSingleSpace(plainBuf, tokenVal);
                tokenEmpty = tokenVal.isEmpty();
            }

            // Anexa token
            if (!tokenEmpty) {
                if (tokenComp != null) {
                    if (plainBuf.length() > 0) {
                        result = result.copy().append(TextUtil.legacyToComponent(plainBuf.toString()));
                        plainBuf.setLength(0);
                    }
                    result = result.copy().append(tokenComp);
                } else {
                    plainBuf.append(tokenVal);
                }
            }

            prevEmptyToken = tokenEmpty;
            last = m.end();
        }

        // Sufixo após o último token
        String tail = format.substring(last);
        if (!tail.isEmpty()) {
            tail = expandPercentTokens(tail, c, sender);
            tail = joinWithSingleSpace(plainBuf, tail);
            plainBuf.append(tail);
        }

        if (plainBuf.length() > 0) {
            result = result.copy().append(TextUtil.legacyToComponent(plainBuf.toString()));
        }

        return result;
    }

    private String applyChannelTransformations(Channel c, String message) {
        String m = message == null ? "" : message;
        if (c.preventCapslock) {
            int letters = 0, uppers = 0;
            for (int i = 0; i < m.length(); i++) {
                char ch = m.charAt(i);
                if (Character.isLetter(ch)) {
                    letters++;
                    if (Character.isUpperCase(ch)) uppers++;
                }
            }
            if (letters >= 6 && uppers >= Math.max(5, (int)Math.round(letters * 0.7))) {
                m = m.toLowerCase(Locale.ROOT);
            }
        }
        if (c.highlight && !m.isBlank()) {
            char first = m.charAt(0);
            if (Character.isLetter(first)) {
                m = Character.toUpperCase(first) + m.substring(1);
            }
        }
        return m;
    }

    private Map<String, ServerPlayer> findMentionedPlayers(MinecraftServer server, String message) {
        if (message == null || message.isEmpty()) return Collections.emptyMap();
        Matcher m = MENTION_PATTERN.matcher(message);
        Map<String, ServerPlayer> map = new LinkedHashMap<>();
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        while (m.find()) {
            String token = m.group(1);
            String lower = token.toLowerCase(Locale.ROOT);
            if (map.containsKey(lower)) continue;
            for (ServerPlayer p : online) {
                if (p.getGameProfile().getName().equalsIgnoreCase(token)) {
                    map.put(lower, p);
                    break;
                }
            }
        }
        return map;
    }

    private String highlightMentions(String message, Map<String, ServerPlayer> mentioned) {
        if (mentioned.isEmpty() || message == null || message.isEmpty()) return message;
        Matcher matcher = MENTION_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            ServerPlayer p = mentioned.get(token.toLowerCase(Locale.ROOT));
            if (p != null) {
                String shown = p.getGameProfile().getName();
                String replacement = "&6@" + shown + "&r";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public void onServerChat(ServerChatEvent event) {
        ServerPlayer sender = event.getPlayer();
        String raw = event.getRawText();
        if (raw == null) return;

        Parsed parsed = parseIncoming(sender, raw);
        Channel channel = parsed.channel;
        String messageBase = parsed.message;

        if (!canUseChannel(sender, channel)) {
            sender.sendSystemMessage(TextUtil.legacyToComponent("&cVocê não tem permissão para falar no canal " + channel.id + "."));
            event.setCanceled(true); return;
        }

        if (channel.delaySeconds > 0 && !hasBypassDelay(sender, channel.id)) {
            long now = System.nanoTime();
            long nextAllowed = nextSpeakAtNanos
                    .computeIfAbsent(sender.getUUID(), k -> new ConcurrentHashMap<>())
                    .getOrDefault(channel.id.toLowerCase(Locale.ROOT), 0L);
            if (now < nextAllowed) {
                long remaining = nextAllowed - now;
                double remainingSec = Math.max(0.05, remaining / 1_000_000_000.0);
                String pretty = remainingSec >= 1.0
                        ? String.format(Locale.ROOT, "%.1fs", remainingSec)
                        : String.format(Locale.ROOT, "%dms", (int)Math.ceil(remaining / 1_000_000.0));
                sender.sendSystemMessage(TextUtil.legacyToComponent("&cAguarde &e" + pretty + " &cpara falar no canal &e" + channel.id + "&c."));
                event.setCanceled(true); return;
            }
        }

        MessageFilterService.Result fr = filters.process(sender, channel, messageBase, sender.server);
        if (fr.canceled) { event.setCanceled(true); return; }
        String processed = fr.message;

        if (!handleEconomyCost(sender, channel, processed)) {
            event.setCanceled(true); return;
        }

        if (channel.delaySeconds > 0 && !hasBypassDelay(sender, channel.id)) {
            long now = System.nanoTime();
            long delayNanos = (long)(channel.delaySeconds * 1_000_000_000L);
            nextSpeakAtNanos
                    .computeIfAbsent(sender.getUUID(), k -> new ConcurrentHashMap<>())
                    .put(channel.id.toLowerCase(Locale.ROOT), now + delayNanos);
        }

        MinecraftServer server = sender.server;
        Map<String, ServerPlayer> mentioned = channel.mentionable ? findMentionedPlayers(server, processed) : Collections.emptyMap();
        String msgForRender = applyChannelTransformations(channel, processed);
        msgForRender = channel.mentionable ? highlightMentions(msgForRender, mentioned) : msgForRender;

        Component formatted = parseFormatToComponent(channel, channel.format, buildPlaceholders(channel, sender, msgForRender, false), sender);

        Set<ServerPlayer> recipients = new LinkedHashSet<>();
        computeRecipientsAndDeliver(channel, sender, formatted, msgForRender, mentioned, recipients);
        event.setCanceled(true);

        if (config.channelShowMessage) {
            if (recipients.size() == 1 && recipients.contains(sender)) {
                sender.sendSystemMessage(TextUtil.legacyToComponent("&7Ninguém por perto recebeu sua mensagem."));
            }
        }
    }

    public boolean sendToChannel(ServerPlayer sender, String channelId, String message) {
        Channel channel = channels.get(channelId.toLowerCase(Locale.ROOT));
        if (channel == null) {
            sender.sendSystemMessage(TextUtil.legacyToComponent("&cCanal não encontrado: " + channelId));
            return false;
        }
        if (!canUseChannel(sender, channel)) {
            sender.sendSystemMessage(TextUtil.legacyToComponent("&cVocê não tem permissão para falar no canal " + channel.id + "."));
            return false;
        }

        if (channel.delaySeconds > 0 && !hasBypassDelay(sender, channel.id)) {
            long now = System.nanoTime();
            long nextAllowed = nextSpeakAtNanos
                    .computeIfAbsent(sender.getUUID(), k -> new ConcurrentHashMap<>())
                    .getOrDefault(channel.id.toLowerCase(Locale.ROOT), 0L);
            if (now < nextAllowed) {
                long remaining = nextAllowed - now;
                double remainingSec = Math.max(0.05, remaining / 1_000_000_000.0);
                String pretty = remainingSec >= 1.0
                        ? String.format(Locale.ROOT, "%.1fs", remainingSec)
                        : String.format(Locale.ROOT, "%dms", (int)Math.ceil(remaining / 1_000_000.0));
                sender.sendSystemMessage(TextUtil.legacyToComponent("&cAguarde &e" + pretty + " &cpara falar no canal &e" + channel.id + "&c."));
                return false;
            }
        }

        MessageFilterService.Result fr = filters.process(sender, channel, message, sender.server);
        if (fr.canceled) return false;
        String processed = fr.message;

        if (!handleEconomyCost(sender, channel, processed)) {
            return false;
        }

        if (channel.delaySeconds > 0 && !hasBypassDelay(sender, channel.id)) {
            long now = System.nanoTime();
            long delayNanos = (long)(channel.delaySeconds * 1_000_000_000L);
            nextSpeakAtNanos
                    .computeIfAbsent(sender.getUUID(), k -> new ConcurrentHashMap<>())
                    .put(channel.id.toLowerCase(Locale.ROOT), now + delayNanos);
        }

        MinecraftServer server = sender.server;
        Map<String, ServerPlayer> mentioned = channel.mentionable ? findMentionedPlayers(server, processed) : Collections.emptyMap();

        String msgForRender = applyChannelTransformations(channel, processed);
        msgForRender = channel.mentionable ? highlightMentions(msgForRender, mentioned) : msgForRender;

        Component formatted = parseFormatToComponent(channel, channel.format, buildPlaceholders(channel, sender, msgForRender, false), sender);

        Set<ServerPlayer> recipients = new LinkedHashSet<>();
        computeRecipientsAndDeliver(channel, sender, formatted, msgForRender, mentioned, recipients);

        if (config.channelShowMessage) {
            if (recipients.size() == 1 && recipients.contains(sender)) {
                sender.sendSystemMessage(TextUtil.legacyToComponent("&7Ninguém por perto recebeu sua mensagem."));
            }
        }
        return true;
    }

    private void computeRecipientsAndDeliver(Channel channel, ServerPlayer sender, Component formatted,
                                             String originalMessage, Map<String, ServerPlayer> mentioned, Set<ServerPlayer> recipients) {
        MinecraftServer server = sender.server;

        switch (channel.type) {
            case GLOBAL -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.getUUID().equals(sender.getUUID())) { recipients.add(p); continue; }
                    if (hasMutedChannel(p.getUUID(), channel.id)) continue;
                    if (isBlockedByIgnore(p.getUUID(), sender.getUUID(), ChannelType.GLOBAL)) continue;
                    recipients.add(p);
                }
            }
            case STAFF -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (!canSeeStaff(p) && !hasSpy(p.getUUID(), channel.id)) continue;
                    if (p.getUUID().equals(sender.getUUID())) { recipients.add(p); continue; }
                    if (hasMutedChannel(p.getUUID(), channel.id)) continue;
                    if (isBlockedByIgnore(p.getUUID(), sender.getUUID(), ChannelType.STAFF)) continue;
                    recipients.add(p);
                }
            }
            case LOCAL -> {
                double r = channel.radius <= 0 ? 100.0 : channel.radius;
                double r2 = r * r;
                ServerLevel level = sender.serverLevel();
                AABB box = sender.getBoundingBox().inflate(r);
                List<ServerPlayer> nearby = level.getEntitiesOfClass(ServerPlayer.class, box, p -> true);

                recipients.add(sender);

                for (ServerPlayer p : nearby) {
                    if (p.getUUID().equals(sender.getUUID())) continue;
                    boolean inSphere = p.distanceToSqr(sender) <= r2;
                    boolean spy = hasSpy(p.getUUID(), channel.id);
                    if (!(inSphere || spy)) continue;
                    if (hasMutedChannel(p.getUUID(), channel.id)) continue;
                    if (isBlockedByIgnore(p.getUUID(), sender.getUUID(), ChannelType.LOCAL)) continue;
                    recipients.add(p);
                }

                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.level() == level) continue;
                    if (hasSpy(p.getUUID(), channel.id)) {
                        if (!hasMutedChannel(p.getUUID(), channel.id)
                                && !isBlockedByIgnore(p.getUUID(), sender.getUUID(), ChannelType.LOCAL)) {
                            recipients.add(p);
                        }
                    }
                }
            }
        }

        for (ServerPlayer p : recipients) {
            p.sendSystemMessage(formatted);
        }

        if (!spyChannels.isEmpty()) {
            Component spyMsg = parseFormatToComponent(
                    channel,
                    channel.spyFormat,
                    buildPlaceholders(channel, sender, originalMessage, false),
                    sender
            );
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!hasSpy(p.getUUID(), channel.id)) continue;
                if (recipients.contains(p)) continue;
                p.sendSystemMessage(spyMsg);
            }
        }

        if (channel.mentionable && mentioned != null && !mentioned.isEmpty()) {
            for (ServerPlayer p : mentioned.values()) {
                if (!recipients.contains(p)) continue;
                if (isIgnoring(p.getUUID(), sender.getUUID())) continue;
                p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
            }
        }
    }

    private boolean handleEconomyCost(ServerPlayer sender, Channel c, String raw) {
        if (!c.currencyEnabled || (c.messageCost <= 0 && c.minBalance <= 0)) return true;
        if (!economy.isReady()) return true;
        double bal = economy.getBalance(sender, c.currencyId);
        if (bal < c.minBalance) {
            sender.sendSystemMessage(TextUtil.legacyToComponent("&cSaldo insuficiente para falar neste canal."));
            return false;
        }
        if (c.messageCost > 0) {
            boolean ok = economy.withdraw(sender, c.currencyId, c.messageCost, "nightchat:" + c.id + " message");
            if (!ok) {
                sender.sendSystemMessage(TextUtil.legacyToComponent("&cFalha ao cobrar custo de mensagem."));
                return false;
            }
            if (c.showMessageCost) {
                sender.sendSystemMessage(TextUtil.legacyToComponent("&7Custo de mensagem: &e" + c.messageCost));
            }
        }
        return true;
    }

    public void rebuildFilters() {
        this.filters.rebuildFromConfig();
    }
}