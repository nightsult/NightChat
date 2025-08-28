package org.night.nightchat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.night.nightchat.chat.Channel;
import org.night.nightchat.chat.ChannelManager;
import org.night.nightchat.chat.ChatService;
import org.night.nightchat.config.GlobalConfig;
import org.night.nightchat.integration.LuckPermsHook;
import org.night.nightchat.util.TextUtil;

import java.util.Locale;

public class ChatCommands {

    public static void register(CommandDispatcher<CommandSourceStack> d,
                                ChannelManager channels,
                                ChatService chat,
                                LuckPermsHook lp,
                                GlobalConfig global) {

        // /nightchat reload
        d.register(Commands.literal("nightchat")
                .requires(src -> {
                    try {
                        ServerPlayer p = src.getPlayer();
                        // Permite OP nível 2+ OU permissão LuckPerms "nightchat.reload"
                        return src.hasPermission(2) || (p != null && lp.hasPermission(p, "nightchat.reload"));
                    } catch (Exception e) {
                        // Console tem permissão
                        return true;
                    }
                })
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            var server = src.getServer();
                            try {
                                // Recarrega configs globais
                                global.loadOrCreateDefaults(server);
                                // Recarrega filtros a partir da config
                                chat.rebuildFilters();
                                // Recarrega canais (formatos, tags, permissões, etc.)
                                channels.loadOrCreateDefaults(server);

                                // Avisos
                                src.sendSuccess(() -> TextUtil.legacyToComponent("&aNightChat recarregado com sucesso."), true);
                                src.sendSuccess(() -> TextUtil.legacyToComponent("&7Canais carregados: &e" + channels.all().size()), false);
                                src.sendSuccess(() -> TextUtil.legacyToComponent("&7Observação: alterações em 'commands' dos canais exigem reinício para atualizar os aliases."), false);
                                return 1;
                            } catch (Throwable t) {
                                src.sendFailure(TextUtil.legacyToComponent("&cFalha ao recarregar NightChat: " + t.getClass().getSimpleName() + " - " + t.getMessage()));
                                return 0;
                            }
                        })));

        // Mensagem privada
        d.register(Commands.literal("tell")
                .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.player())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                    ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "target");
                                    String msg = StringArgumentType.getString(ctx, "message").trim();

                                    if (sender.getUUID().equals(target.getUUID())) {
                                        sender.sendSystemMessage(TextUtil.legacyToComponent("&cVocê não pode enviar mensagem para si mesmo."));
                                        return 0;
                                    }
                                    if (chat.isIgnoring(target.getUUID(), sender.getUUID())) {
                                        sender.sendSystemMessage(TextUtil.legacyToComponent("&cEsse jogador está ignorando você."));
                                        return 0;
                                    }
                                    if (chat.isIgnoring(sender.getUUID(), target.getUUID())) {
                                        sender.sendSystemMessage(TextUtil.legacyToComponent("&cVocê está ignorando esse jogador."));
                                        return 0;
                                    }

                                    String fmt = global.tellFormat
                                            .replace("%send%", sender.getGameProfile().getName())
                                            .replace("%receiver%", target.getGameProfile().getName())
                                            .replace("%message%", msg);

                                    target.sendSystemMessage(TextUtil.legacyToComponent(fmt));
                                    sender.sendSystemMessage(TextUtil.legacyToComponent(fmt));
                                    return 1;
                                }))));

        // Mute/Ignore/Spy
        d.register(Commands.literal("mute")
                .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer self = ctx.getSource().getPlayerOrException();
                            ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "target");
                            boolean muted = chat.toggleMutePlayer(self, target);
                            self.sendSystemMessage(TextUtil.legacyToComponent(muted
                                    ? "&7Você silenciou &e" + target.getGameProfile().getName()
                                    : "&7Você removeu o silêncio de &e" + target.getGameProfile().getName()));
                            return 1;
                        })));

        d.register(Commands.literal("ignore")
                .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer self = ctx.getSource().getPlayerOrException();
                            ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "target");
                            boolean ignored = chat.toggleIgnore(self, target);
                            self.sendSystemMessage(TextUtil.legacyToComponent(ignored
                                    ? "&7Você agora está ignorando &e" + target.getGameProfile().getName()
                                    : "&7Você parou de ignorar &e" + target.getGameProfile().getName()));
                            return 1;
                        })));

        d.register(Commands.literal("muteall")
                .then(Commands.argument("channel", StringArgumentType.word())
                        .suggests((c, b) -> {
                            channels.all().forEach(ch -> b.suggest(ch.id));
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            ServerPlayer self = ctx.getSource().getPlayerOrException();
                            String id = StringArgumentType.getString(ctx, "channel").toLowerCase(Locale.ROOT);
                            Channel ch = channels.get(id);
                            if (ch == null) {
                                self.sendSystemMessage(TextUtil.legacyToComponent("&cCanal não encontrado: " + id));
                                return 0;
                            }
                            boolean muted = chat.toggleMuteChannel(self, id);
                            self.sendSystemMessage(TextUtil.legacyToComponent(muted
                                    ? "&7Você silenciou o canal &e" + id
                                    : "&7Você reativou o canal &e" + id));
                            return 1;
                        })));

        d.register(Commands.literal("spy")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("channel", StringArgumentType.word())
                        .suggests((c, b) -> {
                            channels.all().forEach(ch -> b.suggest(ch.id));
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            ServerPlayer self = ctx.getSource().getPlayerOrException();
                            String id = StringArgumentType.getString(ctx, "channel").toLowerCase(Locale.ROOT);
                            Channel ch = channels.get(id);
                            if (ch == null) {
                                self.sendSystemMessage(TextUtil.legacyToComponent("&cCanal não encontrado: " + id));
                                return 0;
                            }
                            boolean on = chat.toggleSpyChannel(self, id);
                            self.sendSystemMessage(TextUtil.legacyToComponent(on
                                    ? "&7Modo espionagem ativado para &e" + id
                                    : "&7Modo espionagem desativado para &e" + id));
                            return 1;
                        })));

        // Comandos dinâmicos por canal (ex.: /l, /local, /g ...)
        for (Channel ch : channels.all()) {
            for (String alias : ch.commands) {
                if (alias == null || alias.isBlank()) continue;
                String lit = alias.toLowerCase(Locale.ROOT);
                d.register(Commands.literal(lit)
                        .requires(src -> {
                            try {
                                ServerPlayer p = src.getPlayer();
                                return p != null && (lp.hasPermission(p, ch.permission) || (ch.type != org.night.nightchat.chat.ChannelType.STAFF && p.hasPermissions(0)));
                            } catch (Exception e) { return false; }
                        })
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                    String msg = StringArgumentType.getString(ctx, "message").trim();
                                    boolean ok = chat.sendToChannel(sender, ch.id, msg);
                                    return ok ? 1 : 0;
                                })));
            }
        }
    }
}