package org.night.nightchat.chat;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.minecraft.server.MinecraftServer;
import org.night.nightchat.Nightchat;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ChannelManager {
    private final Map<String, Channel> byId = new LinkedHashMap<>();

    public Channel get(String id) { return byId.get(id); }
    public Channel require(String id) { return Objects.requireNonNull(get(id)); }
    public Collection<Channel> all() { return Collections.unmodifiableCollection(byId.values()); }

    public void loadOrCreateDefaults(MinecraftServer server) {
        byId.clear();
        Path base = server.getFile("config").resolve(Nightchat.MODID).resolve("channels");
        try {
            Files.createDirectories(base);
            // Se não houver nenhum arquivo, criamos exemplos padrão
            boolean hasAny = hasAnyToml(base);
            if (!hasAny) {
                writeIfAbsent(base.resolve("local.toml"), DEFAULT_LOCAL);
                writeIfAbsent(base.resolve("global.toml"), DEFAULT_GLOBAL);
                writeIfAbsent(base.resolve("staff.toml"), DEFAULT_STAFF);
            }
            // Carrega todos os .toml
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(base, "*.toml")) {
                for (Path p : ds) {
                    loadOne(p);
                }
            }
            Nightchat.LOGGER.info("Loaded {} channels: {}", byId.size(), byId.keySet());
        } catch (Exception e) {
            Nightchat.LOGGER.error("Failed to load channels: {}", e.toString());
        }
        // Garantir que pelo menos o 'local' exista
        byId.computeIfAbsent("local", k -> fallbackLocal());
    }

    private boolean hasAnyToml(Path base) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base, "*.toml")) {
            for (Path ignored : ds) return true;
            return false;
        }
    }

    private void writeIfAbsent(Path path, String content) {
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, content, StandardOpenOption.CREATE_NEW);
            }
        } catch (Exception e) {
            Nightchat.LOGGER.warn("Failed to write default channel file {}: {}", path, e.toString());
        }
    }

    private void loadOne(Path path) {
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(path, TomlFormat.instance())
                .preserveInsertionOrder().sync().build()) {
            cfg.load();

            String id = String.valueOf(cfg.getOrElse("id", "local")).toLowerCase(Locale.ROOT);
            String typeStr = String.valueOf(cfg.getOrElse("type", "LOCAL"));
            ChannelType type = ChannelType.valueOf(typeStr.toUpperCase(Locale.ROOT));
            String permission = String.valueOf(cfg.getOrElse("permission", "nightchat.channel." + id));

            List<String> commands = toStrList(cfg.get("commands"));
            double distance = toDouble(cfg.getOrElse("distance", 0.0));
            double delay = toDouble(cfg.getOrElse("delay-message", 0.0));
            boolean mentionable = toBool(cfg.getOrElse("mentionable", true));
            boolean highlight = toBool(cfg.getOrElse("highlight", false));
            boolean preventCaps = toBool(cfg.getOrElse("prevent-capslock", false));

            boolean currency = toBool(cfg.getOrElse("currency", false));
            String currencyId = String.valueOf(cfg.getOrElse("type-currency", "money"));
            double minBalance = toDouble(cfg.getOrElse("min-balance", 0.0));
            double msgCost = toDouble(cfg.getOrElse("message-cost", 0.0));
            boolean showCost = toBool(cfg.getOrElse("show-message-cost", false));

            String format = firstOrEmpty(cfg.get("format"));
            String spy = firstOrEmpty(cfg.get("spy"));

            Map<String, TagDefinition> tags = TagLoader.load(cfg);

            Channel ch = new Channel(
                    id, type, permission,
                    distance, delay, mentionable, highlight, preventCaps,
                    currency, currencyId, minBalance, msgCost, showCost,
                    format, spy, commands, tags
            );

            byId.put(ch.id, ch);
            Nightchat.LOGGER.info("Loaded channel '{}' from {}, tags: {}", ch.id, path.getFileName(), ch.tags.keySet());
        } catch (Exception e) {
            Nightchat.LOGGER.warn("Failed to load channel from {}: {}", path, e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStrList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object x : l) if (x != null) out.add(String.valueOf(x));
            return out;
        }
        return List.of();
    }

    private static String firstOrEmpty(Object v) {
        if (v instanceof String s) return s;
        if (v instanceof List<?> l) {
            for (Object o : l) if (o != null) return String.valueOf(o);
        }
        return "";
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o == null) return false;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0.0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; }
    }

    private Channel fallbackLocal() {
        return new Channel("local", ChannelType.LOCAL, "nightchat.channel.local",
                100.0, 0.0, true, false, false,
                false, "money", 0.0, 0.0, false,
                "&e{prefix} {nick}&f: &e{message}", "&dSPY &e{prefix} {nick}&f: &e{message}",
                List.of("l","local"), Map.of());
    }

    // Exemplos padrão (ajuste livremente)
    private static final String DEFAULT_LOCAL = """
id = "local"
type = "LOCAL"
commands = ["l","local"]
permission = "nightchat.channel.local"

distance = 50.0
delay-message = 5.0
mentionable = false
highlight = false
prevent-capslock = true

format = ["&e{channel_logo} {money_tycoon} {money} {prime} {suffix} {prefix} {nick}&f: &e{message}"]
spy = ["&dSPY &e{prefix} {nick}&f: &e{message}"]

currency = true
type-currency = "money"
min-balance = 0.0
message-cost = 0.0
show-message-cost = false

[[Tags]]
id = "channel_logo"
hover = ["&e[L]"]
suggest = ["&7Chat local"]
suggestCommand = []

[[Tags]]
id = "suffix"
hover = ["&r%luckperms_suffix%"]
suggest = []
suggestCommand = []

[[Tags]]
id = "prefix"
hover = ["&r%luckperms_prefix%"]
suggest = []
suggestCommand = []

[[Tags]]
id = "nick"
hover = ["&r%player%"]
suggest = ["&7Jogador &e%player%"]
suggestCommand = []

[[Custom_Tags]]
id = "money_tycoon"
hover = ["%nighteconomy_money_tycoon%"]
suggest = ["&7O jogador &e%player%", "&fÉ o magnata do servidor"]
suggestCommand = []

[[Custom_Tags]]
id = "money"
hover = ["%nighteconomy_money_balance%"]
suggest = ["&7Verifique o saldo de &e%player%", "&fno servidor"]
suggestCommand = ["money"]
permission = "nightchat.tag.money"

[[Custom_Tags]]
id = "prime"
hover = ["&6[👑]"]
suggest = ["&7Esse jogador é &bPrime"]
suggestCommand = []
permission = "nightchat.tag.prime"
""";

    private static final String DEFAULT_GLOBAL = """
id = "global"
type = "GLOBAL"
commands = ["g","global","!"]
permission = "nightchat.channel.global"

distance = 0.0
delay-message = 0.0
mentionable = true
highlight = false
prevent-capslock = true

format = ["&b[G] {suffix} {prefix} {nick}&f: &b{message}"]
spy = ["&dSPY &b{prefix} {nick}&f: &b{message}"]

currency = false
type-currency = "money"
min-balance = 0.0
message-cost = 0.0
show-message-cost = false
""";

    private static final String DEFAULT_STAFF = """
id = "staff"
type = "STAFF"
commands = ["s","staff","@"]
permission = "nightchat.channel.staff"

distance = 0.0
delay-message = 0.0
mentionable = false
highlight = false
prevent-capslock = false

format = ["&d[@] {suffix} {prefix} {nick}&f: &d{message}"]
spy = ["&dSPY &d{prefix} {nick}&f: &d{message}"]

currency = false
type-currency = "money"
min-balance = 0.0
message-cost = 0.0
show-message-cost = false
""";
}