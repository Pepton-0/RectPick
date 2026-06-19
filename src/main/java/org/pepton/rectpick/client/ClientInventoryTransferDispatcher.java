package org.pepton.rectpick.client;

import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.pepton.rectpick.config.Consts;
import org.pepton.rectpick.network.MoveItemsPayload;
import org.pepton.rectpick.transfer.InventoryItemMover;
import org.pepton.rectpick.transfer.InventoryTransferExecutor;
import org.slf4j.Logger;

/**
 * Client-side dispatcher for inventory transfer requests.
 * <p>
 * It first tries the negotiated RectPick server payload channel. If the server
 * cannot receive the payload, it falls back to vanilla client click operations.
 */
public final class ClientInventoryTransferDispatcher {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientInventoryTransferDispatcher() {
    }

    /**
     * Transfers selected source slots into the inventory containing the target slot.
     *
     * @param menu currently open menu; must be the menu that owns both target and source slot indices.
     * @param targetSlotIndex menu slot index used to choose the destination inventory.
     * @param sourceSlotIndices menu slot indices selected by range selection; invalid or same-inventory slots are skipped by the plan.
     * @return {@code true} when a server request was sent or client fallback transfer work started.
     */
    public static boolean transfer(AbstractContainerMenu menu, int targetSlotIndex, List<Integer> sourceSlotIndices) {
        if (tryServerTransfer(menu, targetSlotIndex, sourceSlotIndices)) {
            return true;
        }

        return clientFallbackTransfer(menu, targetSlotIndex, sourceSlotIndices);
    }

    /**
     * Sends a server transfer request when the remote side has the RectPick channel.
     *
     * @param menu current menu whose container id will be serialized.
     * @param targetSlotIndex valid menu slot index intended as the destination inventory selector.
     * @param sourceSlotIndices source slot indices to serialize; should come from a stored range selection.
     * @return {@code true} when a server payload was sent, {@code false} when fallback should be used.
     */
    private static boolean tryServerTransfer(AbstractContainerMenu menu, int targetSlotIndex, List<Integer> sourceSlotIndices) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !NetworkRegistry.hasChannel(connection.getConnection(), ConnectionProtocol.PLAY, MoveItemsPayload.TYPE.id())) {
            debugLog("RectPick server transfer channel is not available; using client click fallback");
            return false;
        }

        PacketDistributor.sendToServer(new MoveItemsPayload(menu.containerId, targetSlotIndex, sourceSlotIndices));
        debugLog("RectPick sent server transfer request: target={}, sources={}", targetSlotIndex, sourceSlotIndices);
        return true;
    }

    /**
     * Performs transfer by issuing vanilla click operations from the client.
     *
     * @param menu current menu; must still match the screen being operated on.
     * @param targetSlotIndex menu slot index selecting the destination inventory.
     * @param sourceSlotIndices selected source slot indices; same-destination slots are removed by the transfer plan.
     * @return {@code true} when a transfer plan was available and source slots were processed.
     */
    private static boolean clientFallbackTransfer(AbstractContainerMenu menu, int targetSlotIndex, List<Integer> sourceSlotIndices) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || minecraft.player == null) {
            debugLog("RectPick client fallback transfer skipped because game mode or player is unavailable");
            return false;
        }

        InventoryTransferExecutor.InventoryTransferPlan plan = InventoryTransferExecutor.createPlan(menu, targetSlotIndex, sourceSlotIndices);
        if (plan == null) {
            return false;
        }

        for (InventoryTransferExecutor.SourceInventoryTransfer sourceInventory : plan.sourceInventories()) {
            for (Slot sourceSlot : sourceInventory.sourceSlots()) {
                moveClientSlot(minecraft, menu, sourceInventory.sourceInventory(), sourceSlot, plan.destinationInventory(), plan.destinationSlots());
            }
        }

        debugLog("RectPick client fallback transfer completed: targetSlot={}, sources={}", targetSlotIndex, sourceSlotIndices);
        return true;
    }

    /**
     * Picks up one source slot and distributes the carried stack into destination slots.
     *
     * @param minecraft active client instance; must have non-null player and game mode.
     * @param menu current menu containing all passed slots.
     * @param sourceInventory source inventory that owns {@code sourceSlot}.
     * @param sourceSlot slot to pick up from; must contain an item and be pickup-allowed.
     * @param destinationInventory inventory that should receive items.
     * @param destinationSlots active slots belonging to {@code destinationInventory}, ordered by container slot index.
     */
    private static void moveClientSlot(Minecraft minecraft, AbstractContainerMenu menu, Container sourceInventory, Slot sourceSlot, Container destinationInventory, List<Slot> destinationSlots) {
        if (!sourceSlot.hasItem() || !sourceSlot.mayPickup(minecraft.player) || sourceSlot.container != sourceInventory || sourceInventory == destinationInventory) {
            return;
        }

        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, ClickType.PICKUP, minecraft.player);
        moveCarriedStackWithClicks(minecraft, menu, destinationInventory, destinationSlots, false);
        moveCarriedStackWithClicks(minecraft, menu, destinationInventory, destinationSlots, true);

        if (!menu.getCarried().isEmpty()) {
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, ClickType.PICKUP, minecraft.player);
        }
    }

    /**
     * Clicks destination slots until the carried stack is exhausted or no progress is possible.
     *
     * @param minecraft active client instance; must have non-null player and game mode.
     * @param menu current menu whose carried stack is being moved.
     * @param destinationInventory inventory that each destination slot must belong to.
     * @param destinationSlots candidate destination slots ordered by desired insertion priority.
     * @param emptySlotsOnly {@code true} to click only empty slots, {@code false} to merge only compatible non-empty stacks.
     */
    private static void moveCarriedStackWithClicks(Minecraft minecraft, AbstractContainerMenu menu, Container destinationInventory, List<Slot> destinationSlots, boolean emptySlotsOnly) {
        for (Slot destinationSlot : destinationSlots) {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                return;
            }

            ItemStack destinationStack = destinationSlot.getItem();
            if (emptySlotsOnly != destinationStack.isEmpty()) {
                continue;
            }

            if (!emptySlotsOnly && !ItemStack.isSameItemSameComponents(destinationStack, carried)) {
                continue;
            }

            if (!InventoryItemMover.canAcceptStack(destinationSlot, destinationInventory, carried)) {
                continue;
            }

            if (!hasRoomForCarriedStack(destinationSlot, destinationInventory, carried)) {
                continue;
            }

            int beforeCount = carried.getCount();
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, destinationSlot.index, 0, ClickType.PICKUP, minecraft.player);
            if (!menu.getCarried().isEmpty() && menu.getCarried().getCount() == beforeCount) {
                return;
            }
        }
    }

    /**
     * Checks whether a destination slot has room for the carried stack.
     *
     * @param destinationSlot slot to inspect; must belong to {@code destinationInventory}.
     * @param destinationInventory inventory used for container stack limits.
     * @param carried stack currently carried by the menu; must not be empty.
     * @return {@code true} when the slot is empty or its compatible stack is below the effective slot limit.
     */
    private static boolean hasRoomForCarriedStack(Slot destinationSlot, Container destinationInventory, ItemStack carried) {
        int limit = Math.min(
                Math.min(destinationSlot.getMaxStackSize(carried), destinationInventory.getMaxStackSize(carried)),
                carried.getMaxStackSize()
        );
        return destinationSlot.getItem().isEmpty() || destinationSlot.getItem().getCount() < limit;
    }

    /**
     * Emits a RectPick inventory operation debug log when debug logging is enabled.
     *
     * @param message SLF4J message pattern describing the operation.
     * @param args pattern arguments passed through without additional processing.
     */
    private static void debugLog(String message, Object... args) {
        if (Consts.debugLog) {
            LOGGER.info(message, args);
        }
    }
}
