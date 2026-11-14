package com.fanren.simplelan;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(SimpleLan.MODID)
public class SimpleLan {
    /** Mod id, shared by the common and client entry points. */
    public static final String MODID = "simplelan";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SimpleLan(IEventBus modEventBus, ModContainer modContainer) {
        // Registering with an explicit file name keeps the file at config/simplelan-common.toml
        // instead of relying on the default <modid>-<type>.toml scheme.
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, Config.FILE_NAME);
    }
}
