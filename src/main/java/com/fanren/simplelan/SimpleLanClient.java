package com.fanren.simplelan;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.commands.PublishCommand;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.util.HttpUtil;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Opens the current singleplayer world to LAN by hand.
 * <p>
 * Republishing needs two fields Minecraft keeps private: the {@link LanServerPinger} and the
 * channel list of {@link ServerConnectionListener}. Both are located <em>by type</em> rather than by
 * name, so the lookup keeps working regardless of whether the runtime exposes Mojang or SRG names.
 * {@code ServerConnectionListener.stop()} is not an option: it also closes the in-memory channel the
 * host player is connected through, which would kick you out of your own world.
 * <p>
 * No config screen is registered on purpose. Configured skips any mod that already provides an
 * {@code IConfigScreenFactory}, so registering NeoForge's built-in one would hide Configured's UI.
 * Leaving it unregistered means Configured takes over when installed, and without it the config
 * button in the mod list stays disabled and the file is edited by hand.
 */
// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = SimpleLan.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = SimpleLan.MODID, value = Dist.CLIENT)
public class SimpleLanClient {
    /** Server the pending repeat announcement belongs to. */
    @Nullable
    private static IntegratedServer announcedServer;

    /**
     * Whether a repeat announcement is still pending. A separate flag rather than a sentinel
     * deadline, because {@link System#nanoTime()} may legitimately return zero or a negative value
     * on some platforms.
     */
    private static boolean repeatPending;

    /**
     * {@link System#nanoTime()} reading at which the repeat announcement becomes due. Measured from
     * the wall clock so the delay is exactly the configured number of seconds no matter how fast the
     * client ticks; the tick handler only decides <em>when</em> to fire it, never <em>how long</em>
     * to wait.
     */
    private static long repeatDeadlineNanos;

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        // Only singleplayer worlds get an integrated server; multiplayer and Realms do not.
        if (!mc.hasSingleplayerServer()) {
            return;
        }

        IntegratedServer server = mc.getSingleplayerServer();
        // Publishing touches client state, so make sure we are back on the client thread.
        mc.execute(() -> {
            // The player may have opened the world to LAN manually before we got here.
            if (server.isPublished() || !Config.ENABLED.getAsBoolean()) {
                return;
            }

            int port = Config.resolvePort();
            if (publish(server, port)) {
                announce(mc, server);
            } else {
                SimpleLan.LOGGER.warn("Could not open {} to LAN on port {}", server.getMotd(), port);
                // A port that is taken or privileged would otherwise look like the mod never ran.
                chat(mc, Component.translatable("simplelan.chat.publishFailed", clickablePort(port))
                        .withStyle(ChatFormatting.RED));
            }
        });
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cancelRepeat();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!repeatPending) {
            return;
        }
        // Subtraction keeps this correct even if nanoTime() wraps around.
        if (System.nanoTime() - repeatDeadlineNanos < 0L) {
            return;
        }

        IntegratedServer server = announcedServer;
        cancelRepeat();

        Minecraft mc = Minecraft.getInstance();
        if (server == null || mc.getSingleplayerServer() != server || !server.isPublished()) {
            return;
        }

        // Restated on purpose: large modpacks bury the first message under a wall of join spam.
        chat(mc, Component.translatable("simplelan.chat.repeat", clickablePort(server.getPort())));
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("simplelan")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("republish")
                        .executes(ctx -> republish(ctx.getSource(), Config.resolvePort()))
                        .then(Commands.argument("port", IntegerArgumentType.integer(0, Config.MAX_PORT))
                                .executes(ctx -> republish(ctx.getSource(),
                                        Config.resolvePort(IntegerArgumentType.getInteger(ctx, "port"))))))
                .then(Commands.literal("defaults")
                        .then(Commands.argument("port", IntegerArgumentType.integer(0, Config.MAX_PORT))
                                .executes(ctx -> setDefaultPort(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "port"))))));
    }

    private static int status(CommandSourceStack source) {
        IntegratedServer server = singleplayerServer(source);
        if (server == null) {
            return 0;
        }

        source.sendSuccess(() -> server.isPublished()
                ? Component.translatable("simplelan.command.status.published", clickablePort(server.getPort()))
                : Component.translatable("simplelan.command.status.idle"), false);
        return 1;
    }

    private static int republish(CommandSourceStack source, int port) {
        IntegratedServer server = singleplayerServer(source);
        if (server == null) {
            return 0;
        }
        if (server.isPublished() && server.getPort() == port) {
            source.sendFailure(Component.translatable("simplelan.command.republish.same", clickablePort(port)));
            return 0;
        }

        // Read the old ping thread before publishServer overwrites the field holding it.
        LanServerPinger previous = server.isPublished() ? currentPinger(server) : null;

        // Bind the new port first: if it fails the world is left exactly as it was, so there is
        // nothing to roll back. Only once it is up is the old advertisement torn down.
        if (!publish(server, port)) {
            source.sendFailure(Component.translatable("simplelan.command.republish.failed", clickablePort(port)));
            return 0;
        }

        if (previous != null) {
            previous.interrupt();
        }
        closeOtherListeners(server.getConnection(), server.getPort());

        announce(Minecraft.getInstance(), server);
        source.sendSuccess(
                () -> Component.translatable("simplelan.command.republish.done", clickablePort(server.getPort())),
                false);
        return 1;
    }

    private static int setDefaultPort(CommandSourceStack source, int port) {
        try {
            Config.PORT.set(port);
            Config.PORT.save();
        } catch (Exception e) {
            SimpleLan.LOGGER.error("Failed to write the default port to the config", e);
            source.sendFailure(Component.translatable("simplelan.command.defaults.failed"));
            return 0;
        }

        SimpleLan.LOGGER.info("Default LAN port set to {}", port);
        source.sendSuccess(
                () -> Component.translatable("simplelan.command.defaults.done", clickablePort(port)),
                false);
        return 1;
    }

    private static void announce(Minecraft mc, IntegratedServer server) {
        SimpleLan.LOGGER.info("Opened {} to LAN on port {}", server.getMotd(), server.getPort());
        // The same line vanilla shows when you press "Open to LAN", so it reads familiar.
        chat(mc, PublishCommand.getSuccessMessage(server.getPort()));

        int remind = Config.REMIND.getAsInt();
        if (remind > 0) {
            announcedServer = server;
            repeatDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(remind);
            repeatPending = true;
        } else {
            // The config is reloaded live, so switching the remind off has to drop a pending one too.
            cancelRepeat();
        }
    }

    /**
     * Binds the requested port on the integrated server. Must run on the client thread.
     */
    private static boolean publish(IntegratedServer server, int port) {
        if (server.isStopped() || Minecraft.getInstance().getSingleplayerServer() != server) {
            return false;
        }
        if (!HttpUtil.isPortAvailable(port)) {
            SimpleLan.LOGGER.warn("Port {} is already in use", port);
            return false;
        }

        try {
            // Mirror the defaults the vanilla "Open to LAN" screen would have offered.
            return server.publishServer(
                    server.getDefaultGameType(),
                    server.getWorldData().isAllowCommands(),
                    port);
        } catch (Exception e) {
            SimpleLan.LOGGER.error("Failed to open world to LAN", e);
            return false;
        }
    }

    @Nullable
    private static LanServerPinger currentPinger(IntegratedServer server) {
        for (Field field : IntegratedServer.class.getDeclaredFields()) {
            if (field.getType() != LanServerPinger.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                return (LanServerPinger) field.get(server);
            } catch (ReflectiveOperationException | SecurityException e) {
                SimpleLan.LOGGER.warn("Could not read the LAN ping thread", e);
                return null;
            }
        }
        return null;
    }

    /** Closes every listener except the one on {@code keepPort}. */
    private static void closeOtherListeners(ServerConnectionListener listener, int keepPort) {
        for (ChannelFuture channel : tcpChannels(listener)) {
            try {
                if (((InetSocketAddress) channel.channel().localAddress()).getPort() != keepPort) {
                    channel.channel().close().sync();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The listeners bound to a real network address. The host player's in-memory channel is skipped
     * because it uses {@code LocalAddress} rather than {@code InetSocketAddress}.
     */
    private static List<ChannelFuture> tcpChannels(ServerConnectionListener listener) {
        List<ChannelFuture> found = new ArrayList<>();

        for (Field field : ServerConnectionListener.class.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (!(field.get(listener) instanceof List<?> values)) {
                    continue;
                }
                for (Object value : values) {
                    if (value instanceof ChannelFuture channel
                            && channel.channel().localAddress() instanceof InetSocketAddress) {
                        found.add(channel);
                    }
                }
            } catch (ReflectiveOperationException | SecurityException e) {
                SimpleLan.LOGGER.warn("Could not inspect the server listener", e);
            }
        }
        return found;
    }

    @Nullable
    private static IntegratedServer singleplayerServer(CommandSourceStack source) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            source.sendFailure(Component.translatable("simplelan.command.no_world"));
        }
        return server;
    }

    private static void cancelRepeat() {
        announcedServer = null;
        repeatPending = false;
    }

    private static void chat(Minecraft mc, Component message) {
        mc.gui.getChat().addMessage(message);
    }

    /**
     * Renders a port exactly the way vanilla does: green, underlined and click-to-copy, so it looks
     * identical to the port inside the "Local game hosted on port X" line.
     */
    private static Component clickablePort(int port) {
        return ComponentUtils.copyOnClickText(String.valueOf(port));
    }
}