package com.itemcasino.client.screen;

import com.itemcasino.client.CasinoSounds;
import com.itemcasino.client.ClientSessionState;
import com.itemcasino.client.ClientValueCache;
import com.itemcasino.client.render.CasinoButton;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.render.WheelRenderer;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.core.value.Odds;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.menu.UpgraderMenu;
import com.itemcasino.network.c2s.C2SRequestValueTable;
import com.itemcasino.network.c2s.C2SSelectTarget;
import com.itemcasino.network.c2s.C2SSetOption;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Upgrader: a wheel in the middle, the numbers on plates either side of it, and one row of
 * controls along the bottom.
 *
 * <p>Nothing overlaps anything, which was not true of the first pass: the odds line, the value line
 * and the inventory caption all landed in the same band of pixels because each was positioned
 * against the wheel instead of against a layout. The read-outs now live in their own gauges and the
 * centre of the felt belongs to the wheel alone.
 */
public class UpgraderScreen extends AbstractCasinoScreen<UpgraderMenu> {

    private static final int WHEEL_CX = CasinoLayout.CENTRE_X;
    private static final int WHEEL_CY = 58;
    private static final int WHEEL_R = 28;

    private static final int TARGET_WELL_X = CasinoLayout.BUTTONS_RIGHT - 16;
    private static final int SPIN_X = 124;
    private static final int SPIN_W = 50;
    private static final int TARGET_BUTTON_X = CasinoLayout.BUTTONS_X;
    private static final int TARGET_BUTTON_W = 74;

    // --- the picker modal ---------------------------------------------------
    private static final int PICKER_X = 14;
    private static final int PICKER_Y = 22;
    private static final int PICKER_W = 188;
    private static final int PICKER_H = 182;
    private static final int INNER_X = PICKER_X + 4;
    private static final int INNER_W = PICKER_W - 8;

    private static final int HEADER_Y = PICKER_Y + 4;
    private static final int SEARCH_W = 132;
    private static final int TOGGLE_X = INNER_X + SEARCH_W + 4;
    private static final int TOGGLE_W = INNER_W - SEARCH_W - 4;

    private static final int CONTENT_Y = 46;
    private static final int VISIBLE_ROWS = 6;
    private static final int ROW_HEIGHT = 20;
    private static final int GRID_COLUMNS = 8;
    private static final int CELL = 20;
    private static final int BAR_W = 4;
    private static final int INFO_Y = CONTENT_Y + VISIBLE_ROWS * ROW_HEIGHT + 4;   // 170

    @Nullable private Item selected;
    private boolean pickerOpen;
    private boolean gridView;
    private int scroll;
    @Nullable private EditBox search;
    @Nullable private Button targetButton;
    @Nullable private Button closeButton;
    @Nullable private Button viewButton;
    @Nullable private Button fewerButton;
    @Nullable private Button moreButton;
    private List<Item> filtered = List.of();
    @Nullable private Item hovered;

    /** Where the scrollbar was last drawn, so a click is tested against what is on screen. */
    /** Notches around the wheel. One tick per notch passed, so the rhythm follows the easing. */
    private static final int DETENTS = 24;
    private int lastDetent = Integer.MIN_VALUE;

    private int barTop;
    private int barHeight;
    private int thumbHeight;
    private boolean draggingBar;

    public UpgraderScreen(UpgraderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected int primaryButtonX() { return SPIN_X; }

    @Override
    protected int primaryButtonWidth() { return SPIN_W; }

    @Override
    protected void init() {
        super.init();
        targetButton = addRenderableWidget(CasinoButton.of(
                leftPos + TARGET_BUTTON_X, topPos + CasinoLayout.ROW_Y,
                TARGET_BUTTON_W, CasinoLayout.BUTTON_H,
                Component.translatable("itemcasino.button.target"), b -> togglePicker()));

        // Unbordered on purpose: the widget's own frame is a pale vanilla box that disappears
        // against the modal, so the field gets a drawn plate instead and the text a colour that
        // survives on it.
        search = new EditBox(font, leftPos + INNER_X + 2, topPos + HEADER_Y + 2,
                SEARCH_W - 4, 12, Component.translatable("itemcasino.picker.search"));
        search.setBordered(false);
        search.setTextColor(CasinoPanel.TEXT_CREAM);
        search.setHint(Component.translatable("itemcasino.picker.search"));
        search.setResponder(text -> { scroll = 0; refilter(); });
        addRenderableWidget(search);

        viewButton = addRenderableWidget(CasinoButton.of(
                leftPos + TOGGLE_X, topPos + HEADER_Y, TOGGLE_W, 16,
                viewLabel(), b -> toggleView()));

        closeButton = addRenderableWidget(CasinoButton.of(
                leftPos + PICKER_X + (PICKER_W - 60) / 2, topPos + PICKER_Y + PICKER_H - 24,
                60, CasinoLayout.BUTTON_H,
                Component.translatable("gui.back"), b -> togglePicker()));

        int countY = 86;
        int rightX = CasinoLayout.RIGHT_GAUGE_X - CasinoLayout.GAUGE_W;
        fewerButton = addRenderableWidget(CasinoButton.of(leftPos + rightX, topPos + countY,
                14, 14, Component.literal("-"), b -> nudgeCount(-1)));
        moreButton = addRenderableWidget(CasinoButton.of(
                leftPos + rightX + CasinoLayout.GAUGE_W - 14, topPos + countY,
                14, 14, Component.literal("+"), b -> nudgeCount(1)));

        if (!ClientValueCache.isReady()) {
            ClientPacketDistributor.sendToServer(C2SRequestValueTable.INSTANCE);
        }
        applyPickerVisibility();
        refilter();
    }

    private Component viewLabel() {
        return Component.translatable(gridView
                ? "itemcasino.picker.view_list" : "itemcasino.picker.view_grid");
    }

    /**
     * The picker is a modal, so everything behind it is hidden rather than merely covered. A widget
     * that is still clickable under an opaque panel is the kind of thing that makes a screen feel
     * broken, and the target button sits directly beneath the list.
     */
    private void applyPickerVisibility() {
        if (search != null) {
            search.setVisible(pickerOpen);
            // Focus has to be set on the screen, not just on the widget: keystrokes are routed to
            // whichever child the screen considers focused, and a box that only thinks it is
            // focused swallows nothing and receives nothing. Handing the screen the field also
            // stops the inventory key from closing the whole screen the moment you type an "e".
            setFocused(pickerOpen ? search : null);
            search.setFocused(pickerOpen);
        }
        if (closeButton != null) closeButton.visible = pickerOpen;
        if (viewButton != null) viewButton.visible = pickerOpen;
        if (targetButton != null) targetButton.visible = !pickerOpen;
        if (actionButton != null) actionButton.visible = !pickerOpen;
        if (fewerButton != null) fewerButton.visible = !pickerOpen;
        if (moreButton != null) moreButton.visible = !pickerOpen;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // Reading the angle once a tick and ticking on every notch it crossed is all the "slow
        // down at the end" there is: the easing does the work, the sound just reports it.
        if (ClientSessionState.phase() == ClientSessionState.Phase.SPINNING) {
            int detent = (int) Math.floor(ClientSessionState.currentAngle(0F)
                    / (com.itemcasino.core.game.wheel.WheelMath.TAU / DETENTS));
            if (lastDetent != Integer.MIN_VALUE && detent != lastDetent) {
                CasinoSounds.wheelTick(detent);
            }
            lastDetent = detent;
        } else {
            lastDetent = Integer.MIN_VALUE;
        }
    }

    private void togglePicker() {
        pickerOpen = !pickerOpen;
        applyPickerVisibility();
        refilter();
    }

    private void toggleView() {
        gridView = !gridView;
        scroll = 0;
        if (viewButton != null) viewButton.setMessage(viewLabel());
    }

    private int perRow() { return gridView ? GRID_COLUMNS : 1; }

    /** Centres the tray in the content box, clear of the scrollbar down the right-hand edge. */
    private int gridOriginX() {
        return leftPos + INNER_X + ((INNER_W - 6) - GRID_COLUMNS * CELL) / 2;
    }

    private int pageSize() { return perRow() * VISIBLE_ROWS; }

    private int maxScroll() {
        return Math.max(0, filtered.size() - pageSize());
    }

    private void refilter() {
        filtered = ClientValueCache.search(search == null ? "" : search.getValue());
        scroll = Math.min(scroll, maxScroll());
    }

    @Override
    protected boolean canWager() {
        return menu.canAct() && selected != null && ClientSessionState.quotedPpm() != Odds.ILLEGAL;
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        boolean settable = menu.canAct() && menu.gameState().acceptsItems() && selected != null;
        if (targetButton != null) {
            targetButton.active = menu.canAct() && menu.gameState().acceptsItems();
        }
        if (fewerButton != null) fewerButton.active = settable && count() > 1;
        if (moreButton != null) moreButton.active = settable && count() < maxCount();
    }

    private int count() { return Math.max(1, menu.option()); }

    /**
     * The ceiling, worked out from the two numbers the server already quoted.
     *
     * <p>The odds sit on their 90% cap for as long as the wager is worth more than the prize, so
     * every count up to {@code wager / oneTarget} is spending surplus that would otherwise be
     * thrown away. One beyond that is the first count that actually costs odds, and the server
     * enforces the same bound — this copy only greys out the button.
     */
    private int maxCount() {
        long input = ClientSessionState.quotedInputValue();
        long prize = ClientSessionState.quotedTargetValue();
        if (input <= 0 || prize <= 0) return 1;
        long unit = prize / Math.max(1, count());
        if (unit <= 0) return 1;
        return (int) Math.max(1, Math.min(128, input / unit + 1));
    }

    private void nudgeCount(int delta) {
        int wanted = Math.max(1, Math.min(maxCount(), count() + delta));
        ClientPacketDistributor.sendToServer(new C2SSetOption(menu.containerId, wanted));
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void renderTable(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int ppm = ClientSessionState.quotedPpm();
        int wheelPpm = ppm == Odds.ILLEGAL ? 0 : ppm;

        WheelRenderer.draw(graphics, leftPos + WHEEL_CX, topPos + WHEEL_CY, WHEEL_R,
                ClientSessionState.currentAngle(partialTick), wheelPpm,
                0xFFF2C14E, 0xFF3A2F3E, 0xFF1B1B22);

        CasinoPanel.well(graphics, leftPos + TARGET_WELL_X, topPos + CasinoLayout.ROW_Y + 1);
        if (selected != null) {
            graphics.renderItem(new ItemStack(selected),
                    leftPos + TARGET_WELL_X, topPos + CasinoLayout.ROW_Y + 1);
        }
    }

    @Override
    protected Component infoText() {
        return Component.translatable("itemcasino.tooltip.upgrader_edge");
    }

    @Override
    protected void renderReadouts(GuiGraphics graphics) {
        int ppm = ClientSessionState.quotedPpm();
        boolean legal = selected != null && ppm != Odds.ILLEGAL;
        int colour = !legal ? CasinoPanel.TEXT_MUTED
                : ppm >= 400_000 ? CasinoPanel.TEXT_WIN
                : ppm >= 100_000 ? CasinoPanel.TEXT_PUSH : CasinoPanel.TEXT_LOSE;

        gauge(graphics, CasinoLayout.LEFT_GAUGE_X, 46, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.chance"),
                Component.literal(legal ? Odds.percent(ppm) + "%" : "--"), colour);

        long input = ClientSessionState.quotedInputValue();
        long target = ClientSessionState.quotedTargetValue();
        int rightX = CasinoLayout.RIGHT_GAUGE_X - CasinoLayout.GAUGE_W;
        gauge(graphics, rightX, 32, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.wager_value"),
                Component.literal(input >= 0 ? Fixed.format(input) : "--"), CasinoPanel.TEXT_CREAM);
        gauge(graphics, rightX, 60, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.target_value"),
                Component.literal(target >= 0 ? Fixed.format(target) : "--"),
                CasinoPanel.TEXT_CREAM);

        // Between the two nudge buttons: how many of the target this spin is playing for.
        Component howMany = Component.translatable("itemcasino.label.times", count());
        centred(graphics, howMany, rightX + CasinoLayout.GAUGE_W / 2, 89,
                count() > 1 ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_MUTED);

        Component caption = selected == null
                ? Component.translatable("itemcasino.label.pick_target")
                : (ppm == Odds.ILLEGAL
                        // Both sides priced but still illegal: the shot is longer than 1 in 1000,
                        // which the server refuses rather than rounding up. Say so, instead of a
                        // bare "no bet" the player cannot act on.
                        ? Component.translatable(input > 0 && target > 0
                                ? "itemcasino.label.long_shot" : "itemcasino.label.no_odds")
                        : new ItemStack(selected).getHoverName());
        centred(graphics, caption, WHEEL_CX, 96,
                ppm == Odds.ILLEGAL && selected != null ? CasinoPanel.TEXT_LOSE
                        : CasinoPanel.TEXT_MUTED);
    }

    @Override
    protected void renderOverlay(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        super.renderOverlay(graphics, partialTick, mouseX, mouseY);
        if (!pickerOpen) return;

        renderPicker(graphics, mouseX, mouseY);

        // The widgets were already drawn, underneath the modal that has just been painted over
        // them. Drawing them a second time here is what puts the search field and the buttons back
        // on top -- they still own the clicks and the keystrokes either way, this is only paint.
        if (search != null) search.render(graphics, mouseX, mouseY, partialTick);
        if (viewButton != null) viewButton.render(graphics, mouseX, mouseY, partialTick);
        if (closeButton != null) closeButton.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos + PICKER_X;
        int y = topPos + PICKER_Y;
        hovered = null;

        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xD0080604);
        CasinoPanel.rounded(graphics, x - 2, y - 2, PICKER_W + 4, PICKER_H + 4, CasinoPanel.GOLD_DEEP);
        CasinoPanel.rounded(graphics, x - 1, y - 1, PICKER_W + 2, PICKER_H + 2, CasinoPanel.FRAME_EDGE);
        CasinoPanel.rounded(graphics, x, y, PICKER_W, PICKER_H, 0xFF1A1410);

        // The plate the search field sits on.
        int searchX = leftPos + INNER_X;
        int searchY = topPos + HEADER_Y;
        graphics.fill(searchX, searchY, searchX + SEARCH_W, searchY + 16, 0xFF0E0A07);
        CasinoPanel.frame(graphics, searchX, searchY, SEARCH_W, 16, CasinoPanel.GOLD_DEEP);

        int contentTop = topPos + CONTENT_Y;
        int contentHeight = VISIBLE_ROWS * ROW_HEIGHT;
        graphics.fill(leftPos + INNER_X, contentTop - 2,
                leftPos + INNER_X + INNER_W - 6, contentTop + contentHeight + 2, 0xFF100C09);

        if (gridView) renderGrid(graphics, mouseX, mouseY, contentTop);
        else renderList(graphics, mouseX, mouseY, contentTop);

        if (filtered.isEmpty()) {
            graphics.drawString(font, Component.translatable("itemcasino.picker.empty"),
                    leftPos + INNER_X + 6, contentTop + 8, CasinoPanel.TEXT_MUTED, false);
        }

        renderScrollbar(graphics, contentTop, contentHeight);
        renderInfoLine(graphics);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY, int contentTop) {
        int x = leftPos + INNER_X;
        int width = INNER_W - 6;
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = scroll + row;
            if (index >= filtered.size()) break;
            Item item = filtered.get(index);
            int rowY = contentTop + row * ROW_HEIGHT;
            boolean hot = mouseX >= x && mouseX < x + width && mouseY >= rowY
                    && mouseY < rowY + ROW_HEIGHT;
            if (hot) {
                graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x33D8B34A);
                hovered = item;
            }
            if (item == selected) {
                CasinoPanel.frame(graphics, x, rowY, width, ROW_HEIGHT, CasinoPanel.GOLD_DEEP);
            }

            ItemStack stack = new ItemStack(item);
            graphics.renderItem(stack, x + 3, rowY + 2);
            graphics.drawString(font, stack.getHoverName().getString(), x + 24, rowY + 6,
                    hot ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_CREAM, false);
            String value = Fixed.format(ClientValueCache.value(item));
            graphics.drawString(font, value, x + width - 4 - font.width(value), rowY + 6,
                    CasinoPanel.TEXT_MUTED, false);
        }
    }

    /**
     * The same list as a tray of icons. Nine to a row instead of one, so a search that matches a
     * hundred things is something you scan rather than scroll through; the name and the price move
     * to the line underneath, for whichever icon is under the cursor.
     */
    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY, int contentTop) {
        int originX = gridOriginX();
        for (int slot = 0; slot < pageSize(); slot++) {
            int index = scroll + slot;
            if (index >= filtered.size()) break;
            Item item = filtered.get(index);
            int cellX = originX + (slot % GRID_COLUMNS) * CELL;
            int cellY = contentTop + (slot / GRID_COLUMNS) * CELL;
            boolean hot = mouseX >= cellX && mouseX < cellX + CELL
                    && mouseY >= cellY && mouseY < cellY + CELL;

            graphics.fill(cellX + 1, cellY + 1, cellX + CELL - 1, cellY + CELL - 1,
                    hot ? 0x44D8B34A : 0xFF191310);
            if (item == selected) {
                CasinoPanel.frame(graphics, cellX, cellY, CELL, CELL, CasinoPanel.GOLD);
            }
            if (hot) hovered = item;
            graphics.renderItem(new ItemStack(item), cellX + 2, cellY + 2);
        }
    }

    private void renderInfoLine(GuiGraphics graphics) {
        Item shown = hovered != null ? hovered : selected;
        int y = topPos + INFO_Y;
        if (shown == null) {
            graphics.drawString(font, Component.translatable("itemcasino.picker.hint"),
                    leftPos + INNER_X, y, CasinoPanel.TEXT_MUTED, false);
            return;
        }
        ItemStack stack = new ItemStack(shown);
        graphics.drawString(font, stack.getHoverName().getString(), leftPos + INNER_X, y,
                CasinoPanel.TEXT_GOLD, false);
        String value = Fixed.format(ClientValueCache.value(shown));
        graphics.drawString(font, value,
                leftPos + INNER_X + INNER_W - 6 - font.width(value), y,
                CasinoPanel.TEXT_CREAM, false);
    }

    /** Only drawn when there is something to scroll: a control that does nothing is just noise. */
    private void renderScrollbar(GuiGraphics graphics, int contentTop, int contentHeight) {
        int overflow = maxScroll();
        barTop = contentTop;
        barHeight = contentHeight;
        thumbHeight = 0;
        if (overflow <= 0) return;

        int barX = barX();
        thumbHeight = Math.max(12, contentHeight * pageSize() / Math.max(1, filtered.size()));
        int travel = contentHeight - thumbHeight;
        int thumbY = contentTop + (int) ((long) travel * scroll / overflow);
        graphics.fill(barX, contentTop, barX + BAR_W, contentTop + contentHeight, 0xFF231A12);
        graphics.fill(barX, thumbY, barX + BAR_W, thumbY + thumbHeight,
                draggingBar ? CasinoPanel.GOLD : CasinoPanel.GOLD_DEEP);
    }

    private int barX() { return leftPos + INNER_X + INNER_W - 5; }

    /**
     * Scrolls so the grab point sits where it was let go.
     *
     * <p>The bar is measured from the middle of the thumb rather than its top, which is what makes
     * dragging feel like moving the thumb instead of dragging an offset around.
     */
    private void scrollToBar(double mouseY) {
        int overflow = maxScroll();
        if (overflow <= 0 || thumbHeight <= 0) return;
        int travel = barHeight - thumbHeight;
        if (travel <= 0) { scroll = 0; return; }
        double top = mouseY - barTop - thumbHeight / 2.0;
        int position = (int) Math.round(top / travel * overflow);
        int step = perRow();
        // Snapped to whole rows in grid view, so the tray never sits half a cell out of line.
        scroll = Math.max(0, Math.min(overflow, position / step * step));
    }

    private boolean overScrollbar(double mouseX, double mouseY) {
        return maxScroll() > 0 && thumbHeight > 0
                && mouseX >= barX() - 2 && mouseX < barX() + BAR_W + 2
                && mouseY >= barTop && mouseY < barTop + barHeight;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!pickerOpen) return super.mouseClicked(event, doubleClick);

        // The modal's own widgets are dispatched by hand, because the usual route would also offer
        // the click to the inventory slots sitting underneath the panel. Nothing behind a modal
        // should be reachable, and a slot you cannot see taking your click is how items get moved
        // by accident.
        for (Button widget : new Button[] { viewButton, closeButton }) {
            if (widget != null && widget.visible && widget.mouseClicked(event, doubleClick)) {
                return true;
            }
        }
        if (search != null && search.visible && search.mouseClicked(event, doubleClick)) {
            setFocused(search);
            return true;
        }

        double mouseX = event.x();
        double mouseY = event.y();

        // The bar: click anywhere on the track to jump there, then keep dragging. It was drawn but
        // not wired before, which made it look like a control that had stopped working.
        if (overScrollbar(mouseX, mouseY)) {
            draggingBar = true;
            scrollToBar(mouseY);
            return true;
        }

        int contentTop = topPos + CONTENT_Y;
        int originX = leftPos + INNER_X;

        if (gridView) {
            int gridX = gridOriginX();
            for (int slot = 0; slot < pageSize(); slot++) {
                int index = scroll + slot;
                if (index >= filtered.size()) break;
                int cellX = gridX + (slot % GRID_COLUMNS) * CELL;
                int cellY = contentTop + (slot / GRID_COLUMNS) * CELL;
                if (mouseX >= cellX && mouseX < cellX + CELL
                        && mouseY >= cellY && mouseY < cellY + CELL) {
                    select(filtered.get(index));
                    return true;
                }
            }
        } else {
            int width = INNER_W - 6;
            for (int row = 0; row < VISIBLE_ROWS; row++) {
                int index = scroll + row;
                if (index >= filtered.size()) break;
                int rowY = contentTop + row * ROW_HEIGHT;
                if (mouseX >= originX && mouseX < originX + width
                        && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                    select(filtered.get(index));
                    return true;
                }
            }
        }

        // Anything else inside the modal is swallowed; a click outside it closes the picker rather
        // than reaching through to the table.
        boolean insideModal = mouseX >= leftPos + PICKER_X && mouseX < leftPos + PICKER_X + PICKER_W
                && mouseY >= topPos + PICKER_Y && mouseY < topPos + PICKER_Y + PICKER_H;
        if (!insideModal) togglePicker();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (pickerOpen && event.key() == InputConstants.KEY_ESCAPE) {
            togglePicker();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pickerOpen && draggingBar) {
            scrollToBar(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        // While the modal is up, a release must not reach the inventory slots hidden beneath it --
        // the click never did, and a release on its own is enough to finish a slot drag.
        if (pickerOpen) return true;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pickerOpen) {
            int step = perRow();
            scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - scrollY * step));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void select(Item item) {
        selected = item;
        pickerOpen = false;
        applyPickerVisibility();

        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id != null) {
            // The server prices both sides and answers with the authoritative quote.
            ClientPacketDistributor.sendToServer(new C2SSelectTarget(menu.containerId, id));
        }
        refreshWidgets();
    }
}
