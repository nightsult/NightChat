package org.night.nightchat.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.minecraft.server.MinecraftServer;
import org.night.nightchat.Nightchat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;

public class GlobalConfig {

    // [channel]
    public boolean channelShowMessage = true;
    public boolean ignoreGlobalMessages = true;

    // [replace]
    public boolean replaceEnable = true;
    public boolean replaceEnableDefault = true;
    public boolean capsMessage = true;
    public boolean fixMessage = true;
    public List<String> replacers = new ArrayList<>();

    // [capslock]
    public boolean capslockEnable = true;
    public int capslockMinLength = 6;
    public int capslockPercentage = 25;

    // [URLs]
    public boolean urlsEnable = true;
    public boolean urlsConcatenate = true;
    public String urlsPunishmentCommand = "/mute @player Divulgando no servidor.";
    public Set<String> allowedDomains = new HashSet<>();

    // [tell]
    public String tellFormat = "&8[%send%] -> [%receiver%]:&r %message%";

    public void loadOrCreateDefaults(MinecraftServer server) {
        Path configDir = server.getFile("config").resolve(Nightchat.MODID);
        try {
            Files.createDirectories(configDir);
            Path target = configDir.resolve("config.toml");
            if (!Files.exists(target)) {
                copyDefaultResource("/config.toml", target);
            }
            load(target);
        } catch (Exception e) {
            Nightchat.LOGGER.error("Failed to load global config", e);
        }
    }

    private void copyDefaultResource(String resourcePath, Path target) {
        try (InputStream in = GlobalConfig.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                Nightchat.LOGGER.warn("Default resource missing: {}", resourcePath);
                return;
            }
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                in.transferTo(out);
            }
        } catch (Exception e) {
            Nightchat.LOGGER.error("Failed to copy default resource {} to {}", resourcePath, target, e);
        }
    }

    private void load(Path path) {
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(path, TomlFormat.instance()).preserveInsertionOrder().sync().build()) {
            cfg.load();

            // channel
            if (cfg.contains("channel")) {
                Config c = cfg.get("channel");
                channelShowMessage = getBool(c, "showMessage", channelShowMessage);
                ignoreGlobalMessages = getBool(c, "ignoreGlobalMessages", ignoreGlobalMessages);
            }

            // replace
            if (cfg.contains("replace")) {
                Config r = cfg.get("replace");
                replaceEnable = getBool(r, "enable", replaceEnable);
                replaceEnableDefault = getBool(r, "enableDefault", replaceEnableDefault);
                capsMessage = getBool(r, "capsMessage", capsMessage);
                fixMessage = getBool(r, "fixMessage", fixMessage);
                replacers = getStringList(r, "replacers", replacers);
            }

            // capslock
            if (cfg.contains("capslock")) {
                Config cl = cfg.get("capslock");
                capslockEnable = getBool(cl, "enable", capslockEnable);
                capslockMinLength = getInt(cl, "minLength", capslockMinLength);
                capslockPercentage = getInt(cl, "percentage", capslockPercentage);
            }

            // URLs
            if (cfg.contains("URLs")) {
                Config u = cfg.get("URLs");
                urlsEnable = getBool(u, "enable", urlsEnable);
                urlsPunishmentCommand = getString(u, "punishmentCommand", urlsPunishmentCommand);
                urlsConcatenate = getBool(u, "concatenate", urlsConcatenate);
                List<String> allowed = getStringList(u, "allowed-domains", new ArrayList<>(allowedDomains));
                allowedDomains.clear();
                for (String d : allowed) allowedDomains.add(d.toLowerCase(Locale.ROOT));
            }

            // tell
            if (cfg.contains("tell")) {
                Config t = cfg.get("tell");
                List<String> fmtList = getStringList(t, "format", List.of(tellFormat));
                if (!fmtList.isEmpty()) tellFormat = fmtList.get(0);
            }

        } catch (Exception e) {
            Nightchat.LOGGER.error("Failed to parse global config {}", path, e);
        }
    }

    // Helpers
    private boolean getBool(Config cfg, String key, boolean def) {
        if (!cfg.contains(key)) return def;
        Object v = cfg.get(key);
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private int getInt(Config cfg, String key, int def) {
        if (!cfg.contains(key)) return def;
        Object v = cfg.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private String getString(Config cfg, String key, String def) {
        if (!cfg.contains(key)) return def;
        Object v = cfg.get(key);
        return v == null ? def : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Config cfg, String key, List<String> def) {
        if (!cfg.contains(key)) return def;
        Object v = cfg.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(String.valueOf(o));
            return out;
        }
        return def;
    }
}