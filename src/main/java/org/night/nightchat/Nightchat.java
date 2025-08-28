package org.night.nightchat;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.night.nighteconomy.api.NightEconomyAPI;
import org.night.nighteconomy.api.NightEconomyAPIProvider;
import org.night.nighteconomy.api.event.NightEconomyReadyEvent;
import org.night.nightchat.chat.ChannelManager;
import org.night.nightchat.chat.ChatService;
import org.night.nightchat.chat.MessageFilterService;
import org.night.nightchat.command.ChatCommands;
import org.night.nightchat.config.GlobalConfig;
import org.night.nightchat.integration.LuckPermsHook;
import org.night.nightchat.integration.NightEconomyHook;
import org.night.nightchat.persist.PlayerStateStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(Nightchat.MODID)
public class Nightchat {
    public static final String MODID = "nightchat";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    private final ChannelManager channelManager;
    private final LuckPermsHook luckPermsHook;
    private final NightEconomyHook economyHook;

    private final GlobalConfig globalConfig;
    private final MessageFilterService filters;
    private final PlayerStateStore playerStateStore;
    private final ChatService chatService;

    public Nightchat() {
        this.luckPermsHook = new LuckPermsHook();
        this.economyHook = new NightEconomyHook();
        this.globalConfig = new GlobalConfig();
        this.channelManager = new ChannelManager();
        this.filters = new MessageFilterService(globalConfig);
        this.playerStateStore = new PlayerStateStore();
        this.chatService = new ChatService(channelManager, luckPermsHook, economyHook, globalConfig, filters, playerStateStore);

        NeoForge.EVENT_BUS.addListener(this::onAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onNightEconomyReady);
    }

    private void onAboutToStart(ServerAboutToStartEvent event) {
        // Hooks
        luckPermsHook.tryHook();
        try {
            NightEconomyAPI api = NightEconomyAPIProvider.get();
            economyHook.setApi(api);
            LOGGER.info("NightEconomy API acquired at server about-to-start.");
        } catch (IllegalStateException notReady) {
            LOGGER.warn("NightEconomy API not available yet. Waiting for NightEconomyReadyEvent...");
        }

        // Load configs
        globalConfig.loadOrCreateDefaults(event.getServer());
        filters.rebuildFromConfig();

        // Channels
        channelManager.loadOrCreateDefaults(event.getServer());

        // Chat + player state listeners
        chatService.register();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ChatCommands.register(event.getDispatcher(), channelManager, chatService, luckPermsHook, globalConfig);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        chatService.flushAll(event.getServer()); // salva NBT + arquivos para online
        chatService.unregister();
    }

    private void onNightEconomyReady(NightEconomyReadyEvent event) {
        economyHook.setApi(event.getApi());
        LOGGER.info("NightEconomy API is now ready.");
    }
}