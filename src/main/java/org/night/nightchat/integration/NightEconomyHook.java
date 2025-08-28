package org.night.nightchat.integration;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.night.nighteconomy.api.NightEconomyAPI;
import org.night.nighteconomy.api.data.TycoonInfo;
import org.night.nightchat.Nightchat;

import java.math.BigDecimal;
import java.util.UUID;

public class NightEconomyHook {
    private NightEconomyAPI api;

    public void setApi(NightEconomyAPI api) {
        this.api = api;
        Nightchat.LOGGER.info("Hooked into NightEconomy API.");
    }

    public boolean isReady() {
        return api != null;
    }

    /**
     * Returns the player's balance (double) for the given currency.
     * The API returns BigDecimal; we safely convert here.
     */
    public double getBalance(ServerPlayer p, String currencyId) {
        if (api == null) return Double.MAX_VALUE; // do not block features if API isn't ready yet
        try {
            BigDecimal bal = api.getBalance(p.getUUID(), currencyId);
            return bal != null ? bal.doubleValue() : 0.0D;
        } catch (Throwable t) {
            Nightchat.LOGGER.warn("NightEconomy getBalance failed: {}", t.toString());
            return 0.0D;
        }
    }

    /**
     * Debits the player's account in the given currency.
     * Uses the new API method: tryDebit(UUID playerId, String currencyId, BigDecimal amount, String reason)
     */
    public boolean withdraw(ServerPlayer p, String currencyId, double amount, String reason) {
        if (api == null || amount <= 0) return true; // don't block when API not ready or invalid amount
        try {
            return api.tryDebit(p.getUUID(), currencyId, BigDecimal.valueOf(amount), reason != null ? reason : "");
        } catch (Throwable t) {
            Nightchat.LOGGER.warn("NightEconomy tryDebit failed: {}", t.toString());
            return false;
        }
    }

    /**
     * Returns the current tycoon's player name for the given currency.
     * Uses getCurrentTycoon(currencyId). Falls back to ProfileCache if name is missing.
     */
    public String getTycoonName(String currencyId, MinecraftServer server) {
        if (api == null) return null;
        try {
            TycoonInfo info = api.getCurrentTycoon(currencyId);
            if (info == null) return null;

            // Prefer the name provided by the API
            if (info.playerName() != null && !info.playerName().isBlank()) {
                return info.playerName();
            }

            // Fallback: resolve from profile cache by UUID
            UUID uuid = info.playerId();
            if (uuid == null || server == null || server.getProfileCache() == null) return null;
            return server.getProfileCache().get(uuid).map(p -> p.getName()).orElse(null);
        } catch (Throwable t) {
            Nightchat.LOGGER.warn("NightEconomy getTycoonName failed: {}", t.toString());
            return null;
        }
    }
}