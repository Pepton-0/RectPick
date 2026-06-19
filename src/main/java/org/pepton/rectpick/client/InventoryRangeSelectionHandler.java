package org.pepton.rectpick.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.pepton.rectpick.config.Consts;
import org.slf4j.Logger;

/**
 * Stateful client-side handler for rectangle selection and transfer gestures.
 * <p>
 * This object is registered once on the NeoForge event bus by {@link ClientEntry}.
 * It stores the current key gesture and the latest selected source slots.
 */
public final class InventoryRangeSelectionHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SLOT_SIZE = 16;

    private GuiPoint startPos;
    private GuiPoint endPos;
    private boolean pickKeyWasDown;
    private int selectedMenuId = -1;
    private List<Integer> selectedSourceSlotIndices = List.of();
    private final RectPickSelectionRenderer renderer;

    /**
     * Creates the selection handler and connects it to the visual feedback renderer.
     *
     * @param renderer renderer that owns all GUI visual state; must not be {@code null}.
     */
    public InventoryRangeSelectionHandler(RectPickSelectionRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /**
     * Handles screen key press events for PICK_KEY.
     *
     * @param event key press event from a visible screen; only events matching PICK_KEY in GUI context are consumed.
     */
    @SubscribeEvent
    public void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!ClientKeyMappings.isPickKey(event.getKeyCode(), event.getScanCode())) {
            return;
        }

        handlePickKeyDown(event.getScreen(), "screen key press");
    }

    /**
     * Handles screen key release events for PICK_KEY.
     *
     * @param event key release event from a visible screen; only events matching PICK_KEY in GUI context are consumed.
     */
    @SubscribeEvent
    public void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (!ClientKeyMappings.isPickKey(event.getKeyCode(), event.getScanCode())) {
            return;
        }

        handlePickKeyUp(event.getScreen(), "screen key release");
    }

    /**
     * Polls PICK_KEY state to recover DOWN/UP transitions missed by screen key events.
     *
     * @param event client tick event fired after Minecraft client tick work; used only for edge detection.
     */
    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean pickKeyDown = ClientKeyMappings.isPickKeyDown(minecraft.getWindow().getWindow());

        if (pickKeyDown && !pickKeyWasDown) {
            handlePickKeyDown(minecraft.screen, "client tick");
        } else if (!pickKeyDown && pickKeyWasDown) {
            handlePickKeyUp(minecraft.screen, "client tick");
        } else if (pickKeyDown) {
            updateActiveSelection(minecraft.screen);
        }

        pickKeyWasDown = pickKeyDown;
    }

    /**
     * Clears transient and stored selection state when an inventory screen closes.
     *
     * @param event closing event; only {@link AbstractContainerScreen} instances affect RectPick state.
     */
    @SubscribeEvent
    public void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?>) {
            clearSelectionState();
            clearStoredSelection();
            renderer.clearAll();
            pickKeyWasDown = false;
        }
    }

    /**
     * Starts a PICK_KEY gesture by storing the current GUI mouse position.
     *
     * @param screen current screen object; must be an {@link AbstractContainerScreen} for selection state to be kept.
     * @param source human-readable source used in logs, such as screen event or tick polling.
     */
    private void handlePickKeyDown(Object screen, String source) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            clearSelectionState();
            clearStoredSelection();
            renderer.clearAll();
            debugLog("RectPick pick key down ignored because the current screen is not an inventory screen; source={}", source);
            return;
        }

        try {
            boolean firstDown = !pickKeyWasDown && selectedSourceSlotIndices.isEmpty();
            startPos = currentGuiMousePosition();
            endPos = null;
            renderer.beginSelection(startPos);
            pickKeyWasDown = true;
            if (firstDown) {
                ClientOperationSounds.playBeginPick();
            }

            debugLog("RectPick pick key down: mouse=({}, {}), source={}", startPos.x(), startPos.y(), source);
        } catch (Exception exception) {
            clearSelectionState();
            renderer.endSelection();
            LOGGER.error("Failed to read mouse position for RectPick selection start", exception);
        }
    }

    /**
     * Finishes a PICK_KEY gesture and dispatches either selection or transfer behavior.
     *
     * @param screen current screen object; must be an {@link AbstractContainerScreen} to process inventory slots.
     * @param source human-readable source used in logs, such as screen event or tick polling.
     */
    private void handlePickKeyUp(Object screen, String source) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            clearSelectionState();
            clearStoredSelection();
            renderer.clearAll();
            debugLog("RectPick pick key up ignored because the current screen is not an inventory screen; source={}", source);
            return;
        }

        try {
            endPos = currentGuiMousePosition();
            debugLog("RectPick pick key up: mouse=({}, {}), source={}", endPos.x(), endPos.y(), source);
        } catch (Exception exception) {
            clearSelectionState();
            renderer.endSelection();
            LOGGER.error("Failed to read mouse position for RectPick selection end", exception);
            return;
        }

        GuiPoint localStart = startPos;
        GuiPoint localEnd = endPos;
        clearSelectionState();
        renderer.endSelection();
        pickKeyWasDown = false;

        if (localStart == null || localEnd == null) {
            debugLog("RectPick selection ignored because start or end position was missing: start={}, end={}", localStart, localEnd);
            return;
        }

        try {
            if (localStart.isSamePosition(localEnd) && !selectedSourceSlotIndices.isEmpty()) {
                handleTransferGesture(containerScreen, localStart, localEnd, "point");
                return;
            }

            GuiRect selectionRect = GuiRect.fromTwoPoints(localStart, localEnd);
            List<Integer> candidateSelectedSlots = collectIntersectingSlots(containerScreen, selectionRect, false);
            if (shouldTreatAsTransferGesture(localStart, localEnd, candidateSelectedSlots)) {
                handleTransferGesture(containerScreen, localStart, localEnd, "drag tolerance");
                return;
            }

            List<Integer> selectedSlots = collectIntersectingSlots(containerScreen, selectionRect, true);
            selectedMenuId = containerScreen.getMenu().containerId;
            selectedSourceSlotIndices = List.copyOf(selectedSlots);
            renderer.showSelectedSlots(selectedMenuId, selectedSourceSlotIndices);
            if (selectedSourceSlotIndices.isEmpty()) {
                ClientOperationSounds.playOperationIgnored();
            } else {
                ClientOperationSounds.playSelectionStored();
            }

            debugLog("RectPick stored {} selected source slot(s) for transfer", selectedSourceSlotIndices.size());
        } catch (Exception exception) {
            LOGGER.error("Failed to process RectPick selection", exception);
        }
    }

    /**
     * Decides whether a small drag should be interpreted as a transfer gesture.
     *
     * @param start GUI point captured on PICK_KEY DOWN; must not be {@code null}.
     * @param end GUI point captured on PICK_KEY UP; must not be {@code null}.
     * @param candidateSelectedSlots non-empty slot hits that a range selection would produce for the gesture.
     * @return {@code true} when stored source slots exist and the drag is empty or within configured tolerance.
     */
    private boolean shouldTreatAsTransferGesture(GuiPoint start, GuiPoint end, List<Integer> candidateSelectedSlots) {
        if (selectedSourceSlotIndices.size() < 2) {
            return false;
        }

        double dragDistance = start.distanceTo(end);
        boolean emptyRange = candidateSelectedSlots.isEmpty();
        boolean smallDrag = dragDistance <= Consts.get().moveOperationMaxDragDistance();
        if (emptyRange || smallDrag) {
            debugLog(
                    "RectPick treating drag as transfer gesture: emptyRange={}, dragDistance={}, tolerance={}",
                    emptyRange,
                    dragDistance,
                    Consts.get().moveOperationMaxDragDistance()
            );
            return true;
        }

        return false;
    }

    /**
     * Updates the renderer with the current mouse position during an active PICK_KEY gesture.
     *
     * @param screen current screen object; must be an {@link AbstractContainerScreen} to keep drawing the active rectangle.
     */
    private void updateActiveSelection(Object screen) {
        if (startPos == null) {
            return;
        }

        if (!(screen instanceof AbstractContainerScreen<?>)) {
            clearSelectionState();
            renderer.endSelection();
            return;
        }

        try {
            renderer.updateSelection(startPos, currentGuiMousePosition());
        } catch (Exception exception) {
            clearSelectionState();
            renderer.endSelection();
            LOGGER.error("Failed to update RectPick selection renderer", exception);
        }
    }

    /**
     * Collects item-containing slots intersecting a selection rectangle.
     *
     * @param screen container screen whose menu slots are checked.
     * @param selectionRect normalized GUI rectangle to test against slot screen bounds.
     * @param logHits {@code true} to log the rectangle and every intersecting slot.
     * @return menu slot indices for intersecting slots that currently contain items.
     */
    private List<Integer> collectIntersectingSlots(AbstractContainerScreen<?> screen, GuiRect selectionRect, boolean logHits) {
        List<Integer> selectedSlots = new ArrayList<>();

        if (logHits) {
            debugLog(
                    "RectPick selection rect: left={}, top={}, right={}, bottom={}",
                    selectionRect.left(),
                    selectionRect.top(),
                    selectionRect.right(),
                    selectionRect.bottom()
            );
            debugLog("RectPick selected slots:");
        }

        for (Slot slot : screen.getMenu().slots) {
            if (!slot.isActive()) {
                continue;
            }

            double slotLeft = screen.getGuiLeft() + slot.x;
            double slotTop = screen.getGuiTop() + slot.y;
            double slotRight = slotLeft + SLOT_SIZE;
            double slotBottom = slotTop + SLOT_SIZE;

            if (!selectionRect.intersects(slotLeft, slotTop, slotRight, slotBottom)) {
                continue;
            }

            if (slot.hasItem()) {
                selectedSlots.add(slot.index);
            }

            if (logHits) {
                logSlot(slot);
            }
        }

        return selectedSlots;
    }

    /**
     * Performs a transfer gesture using the stored source selection.
     *
     * @param screen current container screen; must own the stored selection menu id.
     * @param start GUI point captured on PICK_KEY DOWN, used as a fallback target lookup point.
     * @param end GUI point captured on PICK_KEY UP, preferred as the target lookup point.
     * @param reason short reason logged to explain why this gesture became a transfer.
     */
    private void handleTransferGesture(AbstractContainerScreen<?> screen, GuiPoint start, GuiPoint end, String reason) {
        if (selectedSourceSlotIndices.isEmpty()) {
            debugLog("RectPick transfer ignored because no source slots are selected");
            return;
        }

        if (selectedMenuId != screen.getMenu().containerId) {
            debugLog(
                    "RectPick transfer ignored because the selected menu changed; keeping selected source slots: selected={}, current={}",
                    selectedMenuId,
                    screen.getMenu().containerId
            );
            return;
        }

        Slot targetSlot = findTransferTargetSlot(screen, start, end);
        if (targetSlot == null) {
            ClientOperationSounds.playOperationIgnored();
            debugLog(
                    "RectPick transfer ignored because no target inventory is near mouse=({}, {}) or start=({}, {}); keeping selected source slots",
                    end.x(),
                    end.y(),
                    start.x(),
                    start.y()
            );
            return;
        }

        int sameInventorySourceCount = countSelectedSourceSlotsInTargetInventory(screen, targetSlot);
        if (sameInventorySourceCount == selectedSourceSlotIndices.size()) {
            debugLog(
                    "RectPick transfer target is in the same inventory as all selected source slots; cancelling stored selection: targetMenuSlot={}, targetContainerSlot={}",
                    targetSlot.index,
                    targetSlot.getContainerSlot()
            );
            clearStoredSelection();
            return;
        }

        if (sameInventorySourceCount > 0) {
            debugLog(
                    "RectPick transfer will skip {} selected source slot(s) that already belong to the target inventory",
                    sameInventorySourceCount
            );
        }

        debugLog(
                "RectPick transfer requested: targetMenuSlot={}, targetContainerSlot={}, selectedSourceSlots={}, reason={}",
                targetSlot.index,
                targetSlot.getContainerSlot(),
                selectedSourceSlotIndices,
                reason
        );

        List<Integer> movedSlotIndices = List.copyOf(selectedSourceSlotIndices);
        if (ClientInventoryTransferDispatcher.transfer(screen.getMenu(), targetSlot.index, selectedSourceSlotIndices)) {
            renderer.showMovedSlots(screen.getMenu().containerId, movedSlotIndices);
            ClientOperationSounds.playTransferDone();
        }

        clearStoredSelection();
    }

    /**
     * Finds the target slot for a transfer gesture.
     *
     * @param screen container screen whose menu slots are searched.
     * @param start DOWN point used only if no slot is found at {@code end}.
     * @param end UP point used as the primary target lookup position.
     * @return active slot under or near {@code end} or {@code start}, or {@code null} when no inventory is close enough.
     */
    private Slot findTransferTargetSlot(AbstractContainerScreen<?> screen, GuiPoint start, GuiPoint end) {
        Slot targetSlot = findSlotAt(screen, end);
        if (targetSlot != null) {
            return targetSlot;
        }

        targetSlot = findSlotAt(screen, start);
        if (targetSlot != null) {
            return targetSlot;
        }

        targetSlot = findNearestInventorySlot(screen, end);
        if (targetSlot != null) {
            return targetSlot;
        }

        return findNearestInventorySlot(screen, start);
    }

    /**
     * Counts selected source slots that already belong to the target inventory.
     *
     * @param screen current container screen; must own the stored selected source slot indices.
     * @param targetSlot slot whose container is treated as the destination inventory.
     * @return count of valid stored source slots whose container matches {@code targetSlot.container}.
     */
    private int countSelectedSourceSlotsInTargetInventory(AbstractContainerScreen<?> screen, Slot targetSlot) {
        int count = 0;
        for (int sourceSlotIndex : selectedSourceSlotIndices) {
            if (!screen.getMenu().isValidSlotIndex(sourceSlotIndex)) {
                continue;
            }

            if (screen.getMenu().getSlot(sourceSlotIndex).container == targetSlot.container) {
                count++;
            }
        }

        return count;
    }

    /**
     * Finds an active slot under one GUI point.
     *
     * @param screen container screen whose slots are searched.
     * @param point GUI-scaled point to test against slot screen bounds.
     * @return active slot containing the point, or {@code null} when no slot contains it.
     */
    private Slot findSlotAt(AbstractContainerScreen<?> screen, GuiPoint point) {
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.isActive()) {
                continue;
            }

            double slotLeft = screen.getGuiLeft() + slot.x;
            double slotTop = screen.getGuiTop() + slot.y;
            double slotRight = slotLeft + SLOT_SIZE;
            double slotBottom = slotTop + SLOT_SIZE;

            if (point.x() >= slotLeft && point.x() < slotRight && point.y() >= slotTop && point.y() < slotBottom) {
                return slot;
            }
        }

        return null;
    }

    /**
     * Finds a representative slot from the nearest inventory within the configured snap distance.
     *
     * @param screen container screen whose active slot groups are searched.
     * @param point GUI-scaled point that may be just outside an inventory.
     * @return nearest slot from the nearest inventory, or {@code null} when all inventories are too far away.
     */
    private Slot findNearestInventorySlot(AbstractContainerScreen<?> screen, GuiPoint point) {
        List<InventoryBounds> inventories = collectInventoryBounds(screen);
        InventoryBounds nearestInventory = null;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (InventoryBounds inventory : inventories) {
            double distance = inventory.distanceTo(point);
            if (distance > Consts.transferTargetSnapDistance || distance >= nearestDistance) {
                continue;
            }

            nearestInventory = inventory;
            nearestDistance = distance;
        }

        if (nearestInventory == null) {
            return null;
        }

        Slot nearestSlot = nearestInventory.nearestSlotTo(point);
        if (nearestSlot == null) {
            return null;
        }

        debugLog(
                "RectPick transfer target snapped to nearest inventory: mouse=({}, {}), distance={}, targetMenuSlot={}, targetContainerSlot={}",
                point.x(),
                point.y(),
                nearestDistance,
                nearestSlot.index,
                nearestSlot.getContainerSlot()
        );
        return nearestSlot;
    }

    /**
     * Groups active screen slots into inventory bounding boxes by backing container identity.
     *
     * @param screen container screen whose active slots are grouped.
     * @return inventory bounds in menu traversal order.
     */
    private List<InventoryBounds> collectInventoryBounds(AbstractContainerScreen<?> screen) {
        List<InventoryBounds> inventories = new ArrayList<>();

        for (Slot slot : screen.getMenu().slots) {
            if (!slot.isActive()) {
                continue;
            }

            InventoryBounds inventory = findInventoryBounds(inventories, slot.container);
            if (inventory == null) {
                inventory = new InventoryBounds(slot.container);
                inventories.add(inventory);
            }

            double slotLeft = screen.getGuiLeft() + slot.x;
            double slotTop = screen.getGuiTop() + slot.y;
            inventory.includeSlot(slot, slotLeft, slotTop, slotLeft + SLOT_SIZE, slotTop + SLOT_SIZE);
        }

        return inventories;
    }

    /**
     * Finds the bounds object for one container using identity comparison.
     *
     * @param inventories existing inventory bounds.
     * @param container backing container to locate.
     * @return matching bounds, or {@code null} when this container has not been seen.
     */
    private InventoryBounds findInventoryBounds(List<InventoryBounds> inventories, Container container) {
        for (InventoryBounds inventory : inventories) {
            if (inventory.container == container) {
                return inventory;
            }
        }

        return null;
    }

    /**
     * Logs one intersecting slot in the compact selected-slot list format.
     *
     * @param slot menu slot to report.
     */
    private void logSlot(Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        debugLog(
                "  -> menuIndex={}, containerSlot={}, item=\"{}\", count={}",
                slot.index,
                slot.getContainerSlot(),
                stack.getHoverName().getString(),
                stack.getCount()
        );
    }

    /**
     * Reads the current raw mouse position and converts it to GUI-scaled coordinates.
     *
     * @return current mouse position in GUI-scaled screen space.
     */
    private GuiPoint currentGuiMousePosition() {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        double rawX = minecraft.mouseHandler.xpos();
        double rawY = minecraft.mouseHandler.ypos();
        double guiX = rawX * window.getGuiScaledWidth() / window.getScreenWidth();
        double guiY = rawY * window.getGuiScaledHeight() / window.getScreenHeight();
        return new GuiPoint(guiX, guiY);
    }

    /**
     * Clears only the active DOWN/UP gesture state.
     */
    private void clearSelectionState() {
        startPos = null;
        endPos = null;
    }

    /**
     * Clears the stored source slot selection used by later transfer gestures.
     */
    private void clearStoredSelection() {
        selectedMenuId = -1;
        selectedSourceSlotIndices = List.of();
        renderer.clearSelectedSlots();
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

    /**
     * Mutable bounding box for active slots that belong to the same backing container.
     */
    private static final class InventoryBounds {
        private final Container container;
        private final List<SlotBounds> slots = new ArrayList<>();
        private double left = Double.POSITIVE_INFINITY;
        private double top = Double.POSITIVE_INFINITY;
        private double right = Double.NEGATIVE_INFINITY;
        private double bottom = Double.NEGATIVE_INFINITY;

        /**
         * Creates an empty bounds accumulator for one backing container.
         *
         * @param container inventory container represented by this bounds object; compared by identity.
         */
        private InventoryBounds(Container container) {
            this.container = container;
        }

        /**
         * Expands this inventory bounds with one active screen slot.
         *
         * @param slot menu slot belonging to {@link #container}.
         * @param slotLeft screen-space left edge.
         * @param slotTop screen-space top edge.
         * @param slotRight screen-space right edge.
         * @param slotBottom screen-space bottom edge.
         */
        private void includeSlot(Slot slot, double slotLeft, double slotTop, double slotRight, double slotBottom) {
            slots.add(new SlotBounds(slot, slotLeft, slotTop, slotRight, slotBottom));
            left = Math.min(left, slotLeft);
            top = Math.min(top, slotTop);
            right = Math.max(right, slotRight);
            bottom = Math.max(bottom, slotBottom);
        }

        /**
         * Calculates shortest distance from a point to this inventory bounds.
         *
         * @param point GUI-scaled point to test.
         * @return zero when inside the bounds, otherwise Euclidean distance to the nearest edge or corner.
         */
        private double distanceTo(GuiPoint point) {
            return distanceToRect(point, left, top, right, bottom);
        }

        /**
         * Finds the slot in this inventory nearest to the supplied point.
         *
         * @param point GUI-scaled point used for ranking slot rectangles.
         * @return nearest active slot from this inventory, or {@code null} when no slot was collected.
         */
        private Slot nearestSlotTo(GuiPoint point) {
            SlotBounds nearestSlot = null;
            double nearestDistance = Double.POSITIVE_INFINITY;

            for (SlotBounds slot : slots) {
                double distance = slot.distanceTo(point);
                if (distance >= nearestDistance) {
                    continue;
                }

                nearestSlot = slot;
                nearestDistance = distance;
            }

            return nearestSlot == null ? null : nearestSlot.slot();
        }
    }

    /**
     * Immutable screen-space slot rectangle used for nearest-slot ranking.
     *
     * @param slot menu slot represented by the rectangle.
     * @param left screen-space left edge.
     * @param top screen-space top edge.
     * @param right screen-space right edge.
     * @param bottom screen-space bottom edge.
     */
    private record SlotBounds(Slot slot, double left, double top, double right, double bottom) {
        /**
         * Calculates shortest distance from a point to this slot rectangle.
         *
         * @param point GUI-scaled point to test.
         * @return zero when inside the slot, otherwise Euclidean distance to the nearest edge or corner.
         */
        private double distanceTo(GuiPoint point) {
            return distanceToRect(point, left, top, right, bottom);
        }
    }

    /**
     * Calculates shortest distance from a point to an axis-aligned rectangle.
     *
     * @param point GUI-scaled point to test.
     * @param left rectangle left edge.
     * @param top rectangle top edge.
     * @param right rectangle right edge.
     * @param bottom rectangle bottom edge.
     * @return zero when the point is inside the rectangle, otherwise Euclidean distance to the nearest edge or corner.
     */
    private static double distanceToRect(GuiPoint point, double left, double top, double right, double bottom) {
        double dx = Math.max(Math.max(left - point.x(), 0.0), point.x() - right);
        double dy = Math.max(Math.max(top - point.y(), 0.0), point.y() - bottom);
        return Math.sqrt(dx * dx + dy * dy);
    }
}
