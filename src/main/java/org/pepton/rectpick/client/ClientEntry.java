package org.pepton.rectpick.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only bootstrap for RectPick.
 * <p>
 * This utility class registers key mappings on the mod bus and runtime GUI
 * handlers on the NeoForge event bus.
 */
public final class ClientEntry {
    private ClientEntry() {
    }

    /**
     * Registers all client-only RectPick event handlers.
     *
     * @param modEventBus mod event bus for this mod; must be available only during client initialization.
     */
    public static void initialize(IEventBus modEventBus) {
        modEventBus.addListener(ClientKeyMappings::register);

        RectPickSelectionRenderer selectionRenderer = new RectPickSelectionRenderer();
        NeoForge.EVENT_BUS.register(selectionRenderer);
        NeoForge.EVENT_BUS.register(new InventoryRangeSelectionHandler(selectionRenderer));
    }
}
