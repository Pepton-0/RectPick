package org.pepton.rectpick;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.pepton.rectpick.client.ClientEntry;
import org.pepton.rectpick.config.Consts;
import org.pepton.rectpick.config.TestConfigLoader;
import org.pepton.rectpick.network.RectPickNetworking;
import org.pepton.rectpick.sound.RectPickSoundEvents;
import org.slf4j.Logger;

/**
 * Mod entry point for RectPick.
 * <p>
 * This class initializes global constants, registers common network payloads,
 * and wires client-only handlers when the physical side is the client.
 */
@Mod(Main.MOD_ID)
public class Main {
    /**
     * Mod id used for resources, payload ids, and NeoForge registration.
     */
    public static final String MOD_ID = "rectpick";

    /**
     * Shared mod logger.
     */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Creates the mod entry point and registers mod bus listeners.
     *
     * @param modEventBus NeoForge mod event bus provided by the loader; must be the bus for this mod instance.
     * @param modContainer NeoForge mod container provided by the loader; currently kept for constructor compatibility.
     */
    public Main(IEventBus modEventBus, ModContainer modContainer) {
        Consts.initialize(new TestConfigLoader());

        RectPickSoundEvents.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(RectPickNetworking::registerPayloadHandlers);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientEntry.initialize(modEventBus);
        }
    }

    /**
     * Logs completion of common setup.
     *
     * @param event common setup event fired by NeoForge during mod loading.
     */
    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("RectPick common setup complete");
    }
}
