package com.fanren.simplelan;

import net.minecraft.util.HttpUtil;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for SimpleLan, stored in {@code config/simplelan-common.toml}.
 * <p>
 * The file name is supplied explicitly when the config is registered so that it does not have to
 * follow the default {@code <modid>-<type>.toml} scheme.
 * <p>
 * Every entry declares a {@code translation()}. Without it the option has no translation key at
 * all and Configured falls back to prettifying the TOML key, which is how the labels end up reading
 * "Enabled" or "Port". Configured then looks for {@code <key>.tooltip} for the tooltip and falls back
 * to the raw comment in the TOML if that key is missing.
 */
public final class Config {
    /** File name used for the config inside the {@code config} directory. */
    public static final String FILE_NAME = "simplelan-common.toml";

    /** Highest valid TCP port. */
    public static final int MAX_PORT = 65535;

    /** Default port used when a world is opened to LAN. */
    public static final int DEFAULT_PORT = 25577;

    /** Default delay before the LAN announcement is repeated, in seconds. */
    public static final int DEFAULT_REMIND = 15;

    /** Longest delay before the LAN announcement is repeated, in seconds. */
    public static final int MAX_REMIND = 3600;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch. When false, SimpleLan never opens a world to LAN on its own.",
                    "Commands still work while this is off: /simplelan, /simplelan status,",
                    "/simplelan republish [port], /simplelan defaults <port>")
            .translation("simplelan.configuration.enabled")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue REMIND = BUILDER
            .comment("Seconds to wait before reminding the port number once more, so that",
                    "other mods' chat messages cannot bury the first hint.",
                    "It reminds only once, it does not repeat.",
                    "0 disables the reminder; 1 or more is the delay in seconds.")
            .translation("simplelan.configuration.remind")
            .defineInRange("remind", DEFAULT_REMIND, 0, MAX_REMIND);

    public static final ModConfigSpec.IntValue PORT = BUILDER
            .comment("Port used when opening a world to LAN.",
                    "0 lets Minecraft pick a free port; any other value is used as-is.",
                    "Ports below 1024 need administrator rights and are often taken by system",
                    "services, so a world opened on one of them may fail to publish.",
                    "Change it now with /simplelan republish <port>, or save it with",
                    "/simplelan defaults <port>.")
            .translation("simplelan.configuration.port")
            .defineInRange("port", DEFAULT_PORT, 0, MAX_PORT);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}

    /**
     * Resolves the configured value into a port that is safe to hand to Minecraft.
     * <p>
     * Only {@code 0} is special: it means "pick any free port". Every other value is passed
     * through untouched, so an unusual choice fails with a visible error instead of being silently
     * replaced by some other port. {@code 0} cannot be passed through because
     * {@code IntegratedServer.publishServer} would bind an OS-chosen port while still recording and
     * advertising {@code 0}, leaving other players unable to connect.
     */
    public static int resolvePort() {
        return resolvePort(PORT.getAsInt());
    }

    public static int resolvePort(int configured) {
        return configured == 0 ? HttpUtil.getAvailablePort() : configured;
    }
}
