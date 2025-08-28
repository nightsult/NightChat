package org.night.nightchat.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;
import org.night.nightchat.Nightchat;

import java.util.UUID;

public class LuckPermsHook {
    private LuckPerms api;

    public void tryHook() {
        try {
            this.api = LuckPermsProvider.get();
            Nightchat.LOGGER.info("Hooked into LuckPerms API.");
        } catch (Throwable t) {
            Nightchat.LOGGER.warn("LuckPerms not found. Falling back to vanilla permissions.");
            this.api = null;
        }
    }

    public boolean hasPermission(ServerPlayer p, String node) {
        if (node == null || node.isEmpty()) return true;
        if (api == null) {
            // fallback vanilla: ops têm tudo, outros apenas canais não-staff
            return p.hasPermissions(2);
        }
        User user = api.getUserManager().getUser(p.getUUID());
        if (user == null) return p.hasPermissions(2);
        Tristate t = user.getCachedData().getPermissionData(QueryOptions.defaultContextualOptions()).checkPermission(node);
        return t.asBoolean();
    }

    public String getPrefix(ServerPlayer p) {
        if (api == null) return "";
        User user = api.getUserManager().getUser(p.getUUID());
        if (user == null) return "";
        String pre = user.getCachedData().getMetaData().getPrefix();
        return pre == null ? "" : pre;
    }

    public String getSuffix(ServerPlayer p) {
        if (api == null) return "";
        User user = api.getUserManager().getUser(p.getUUID());
        if (user == null) return "";
        String suf = user.getCachedData().getMetaData().getSuffix();
        return suf == null ? "" : suf;
    }
}