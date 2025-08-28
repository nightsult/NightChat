package org.night.nightchat.persist;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.night.nightchat.Nightchat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class PlayerStateStore {

    private static final String ROOT_KEY = "nightchat";
    private static final String K_MUTED_CHANNELS = "muted_channels";
    private static final String K_SPY_CHANNELS = "spy_channels";
    private static final String K_MUTED_PLAYERS = "muted_players";
    private static final String K_IGNORED_PLAYERS = "ignored_players";

    public PlayerState load(ServerPlayer player) {
        // 1) Tenta NBT do jogador
        PlayerState fromNbt = readFromNbt(player);
        if (fromNbt != null && !fromNbt.isEmpty()) {
            return fromNbt;
        }
        // 2) Fallback: arquivo TOML em config
        return readFromFile(player);
    }

    public void save(ServerPlayer player, PlayerState state) {
        if (player == null || state == null) return;
        // 1) NBT
        writeToNbt(player, state);
        // 2) Arquivo
        writeToFile(player, state);
    }

    private PlayerState readFromNbt(ServerPlayer p) {
        try {
            CompoundTag root = p.getPersistentData();
            if (root == null || !root.contains(ROOT_KEY, Tag.TAG_COMPOUND)) return new PlayerState();
            CompoundTag nc = root.getCompound(ROOT_KEY);
            PlayerState s = new PlayerState();
            readStringSet(nc, K_MUTED_CHANNELS, s.mutedChannels);
            readStringSet(nc, K_SPY_CHANNELS, s.spyChannels);
            readUuidSet(nc, K_MUTED_PLAYERS, s.mutedPlayers);
            readUuidSet(nc, K_IGNORED_PLAYERS, s.ignoredPlayers);
            return s;
        } catch (Throwable t) {
            Nightchat.LOGGER.warn("Failed to read player NBT state: {}", t.toString());
            return new PlayerState();
        }
    }

    private void writeToNbt(ServerPlayer p, PlayerState s) {
        try {
            CompoundTag root = p.getPersistentData();
            CompoundTag nc = new CompoundTag();
            nc.put(K_MUTED_CHANNELS, toStringListTag(s.mutedChannels));
            nc.put(K_SPY_CHANNELS, toStringListTag(s.spyChannels));
            nc.put(K_MUTED_PLAYERS, toStringListTag(uuidsToStrings(s.mutedPlayers)));
            nc.put(K_IGNORED_PLAYERS, toStringListTag(uuidsToStrings(s.ignoredPlayers)));
            root.put(ROOT_KEY, nc);
        } catch (Throwable t) {
            Nightchat.LOGGER.warn("Failed to write player NBT state: {}", t.toString());
        }
    }

    private PlayerState readFromFile(ServerPlayer p) {
        Path path = getPlayerFile(p.server, p.getUUID());
        if (!Files.exists(path)) {
            return new PlayerState();
        }
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(path, TomlFormat.instance())
                .preserveInsertionOrder().sync().build()) {
            cfg.load();
            PlayerState s = new PlayerState();
            readStringListToSet(cfg, "muted_channels", s.mutedChannels);
            readStringListToSet(cfg, "spy_channels", s.spyChannels);
            readUuidListToSet(cfg, "muted_players", s.mutedPlayers);
            readUuidListToSet(cfg, "ignored_players", s.ignoredPlayers);
            return s;
        } catch (Exception e) {
            Nightchat.LOGGER.warn("Failed to read player state file {}: {}", path, e.toString());
            return new PlayerState();
        }
    }

    private void writeToFile(ServerPlayer p, PlayerState s) {
        Path path = getPlayerFile(p.server, p.getUUID());
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            try (CommentedFileConfig cfg = CommentedFileConfig.builder(path, TomlFormat.instance())
                    .preserveInsertionOrder().sync().build()) {
                cfg.set("uuid", p.getUUID().toString());
                cfg.set("name", p.getGameProfile().getName());
                cfg.set("muted_channels", new ArrayList<>(s.mutedChannels));
                cfg.set("spy_channels", new ArrayList<>(s.spyChannels));
                cfg.set("muted_players", uuidsToStringList(s.mutedPlayers));
                cfg.set("ignored_players", uuidsToStringList(s.ignoredPlayers));
                cfg.save();
            }
        } catch (Exception e) {
            Nightchat.LOGGER.warn("Failed to write player state file {}: {}", path, e.toString());
        }
    }

    private Path getPlayerFile(MinecraftServer server, UUID uuid) {
        Path configDir = server.getFile("config").resolve(Nightchat.MODID);
        Path playersDir = configDir.resolve("players");
        return playersDir.resolve(uuid.toString() + ".toml");
    }

    // Helpers NBT
    private void readStringSet(CompoundTag nbt, String key, Set<String> out) {
        if (!nbt.contains(key, Tag.TAG_LIST)) return;
        ListTag list = nbt.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String v = list.getString(i);
            if (v != null && !v.isBlank()) out.add(v.toLowerCase(Locale.ROOT));
        }
    }

    private void readUuidSet(CompoundTag nbt, String key, Set<UUID> out) {
        if (!nbt.contains(key, Tag.TAG_LIST)) return;
        ListTag list = nbt.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String s = list.getString(i);
            try { out.add(UUID.fromString(s)); } catch (Exception ignored) {}
        }
    }

    private ListTag toStringListTag(Collection<String> vals) {
        ListTag list = new ListTag();
        for (String s : vals) {
            if (s == null) continue;
            list.add(StringTag.valueOf(s));
        }
        return list;
    }

    private Collection<String> uuidsToStrings(Collection<UUID> uuids) {
        List<String> out = new ArrayList<>(uuids.size());
        for (UUID u : uuids) out.add(u.toString());
        return out;
    }

    private List<String> uuidsToStringList(Collection<UUID> uuids) {
        return new ArrayList<>(uuidsToStrings(uuids));
    }

    // Helpers NightConfig
    @SuppressWarnings("unchecked")
    private void readStringListToSet(CommentedFileConfig cfg, String key, Set<String> out) {
        if (!cfg.contains(key)) return;
        Object v = cfg.get(key);
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s.toLowerCase(Locale.ROOT));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void readUuidListToSet(CommentedFileConfig cfg, String key, Set<UUID> out) {
        if (!cfg.contains(key)) return;
        Object v = cfg.get(key);
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                try { out.add(UUID.fromString(String.valueOf(o))); } catch (Exception ignored) {}
            }
        }
    }
}