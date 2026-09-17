package com.itemcasino.client.screen;

import com.itemcasino.chips.ChipCards;
import com.itemcasino.client.render.CasinoButton;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.render.ChipText;
import com.itemcasino.client.render.InfoBadge;
import com.itemcasino.client.render.ValueStepper;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.menu.CashierMenu;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.network.c2s.C2SCashierAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;

/**
 * The cashier's counter. Left: the card and what the deposit slot would add to it. Right: the
 * currencies it pays out, how many to take, and what that costs.
 *
 * <p>Every figure here is a preview. The deposit is priced by the server into the menu's data
 * slots; the withdrawal cost is computed from the rates sent when the counter opened, and the
 * server prices it again, live, when the button is pressed.
 */
public class CashierScreen extends AbstractContainerScreen<CashierMenu> {

    private static final int GAUGE_X = 42;
    private static final int GAUGE_W = 62;
    private static final int LEFT_X = 14;
    private static final int COLUMN_W = GAUGE_X + GAUGE_W - LEFT_X;    // 90
    private static final int ACTION_Y = 111;
    private static final int ACTION_H = 14;

    private static final int GRID_X = 112;
    private static final int GRID_Y = 23;
    private static final int CELL = 22;
    private static final int COLUMNS = 4;

    private static final int COUNT_Y = 70;
    private static final int COUNT_H = 12;
    private static final int COST_Y = 85;

    private int selected;
    private int count = 1;
    private ValueStepper stepper;
    private Button depositButton;
    private Button withdrawButton;
    private Button less;
    private Button more;
    private Button max;

    public CashierScreen(CashierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = CasinoLayout.WIDTH;
        this.imageHeight = CasinoLayout.HEIGHT;
        this.titleLabelX = 9;
        this.titleLabelY = 7;
        this.inventoryLabelX = CasinoLayout.INV_X;
        this.inventoryLabelY = CasinoLayout.INV_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();
        // Nothing to agree with a server here: the count is the screen's own, sent with the order.
        stepper = new ValueStepper(() -> 1, () -> clampInt(Math.max(1, affordable())), () -> count,
                value -> count = value);
        depositButton = addRenderableWidget(CasinoButton.of(leftPos + LEFT_X, topPos + ACTION_Y, COLUMN_W,
                ACTION_H, Component.translatable("itemcasino.cashier.deposit"), b -> send(C2SCashierAction.DEPOSIT)));
        int right = CasinoLayout.WIDTH - 14;
        withdrawButton = addRenderableWidget(CasinoButton.of(leftPos + GRID_X, topPos + ACTION_Y,
                right - GRID_X, ACTION_H, Component.translatable("itemcasino.cashier.withdraw"),
                b -> send(C2SCashierAction.WITHDRAW)));
        less = addRenderableWidget(CasinoButton.of(leftPos + GRID_X, topPos + COUNT_Y, 12, COUNT_H,
                Component.literal("-"), b -> press(-1, b)));
        more = addRenderableWidget(CasinoButton.of(leftPos + right - 28 - 12, topPos + COUNT_Y, 12, COUNT_H,
                Component.literal("+"), b -> press(1, b)));
        max = addRenderableWidget(CasinoButton.of(leftPos + right - 26, topPos + COUNT_Y, 26, COUNT_H,
                Component.translatable("itemcasino.bet.max"), b -> stepper.set(clampInt(Math.max(1, affordable())))));
        refreshWidgets();
    }

    // ------------------------------------------------------------------ figures

    private List<CashierMenu.Rate> rates() {
        return menu.rates().rates();
    }

    private CashierMenu.Rate rate() {
        List<CashierMenu.Rate> rates = rates();
        if (rates.isEmpty()) return null;
        return rates.get(Math.max(0, Math.min(rates.size() - 1, selected)));
    }

    private long balance() {
        return ChipCards.balance(menu.card());
    }

    /** How many of the selected currency the card buys, capped at one withdrawal. */
    private long affordable() {
        CashierMenu.Rate rate = rate();
        if (rate == null) return 0;
        return Math.min(CashierMenu.MAX_WITHDRAW, Chips.affordable(balance(), rate.unitValue(), menu.rates().feePpm()));
    }

    private int shownCount() {
        return stepper == null ? count : stepper.value();
    }

    private long cost() {
        CashierMenu.Rate rate = rate();
        if (rate == null) return 0;
        int n = shownCount();
        long value = rate.unitValue() >= Long.MAX_VALUE / n ? Long.MAX_VALUE : rate.unitValue() * n;
        return Chips.withdrawCost(value, menu.rates().feePpm());
    }

    private static int clampInt(long value) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    // ------------------------------------------------------------------ input

    private void send(byte action) {
        CashierMenu.Rate rate = rate();
        if (action == C2SCashierAction.WITHDRAW && rate == null) return;
        if (stepper != null) stepper.flush();
        ClientPacketDistributor.sendToServer(new C2SCashierAction(menu.containerId, action,
                (byte) Math.max(0, Math.min(rates().size() - 1, selected)), shownCount()));
    }

    private void press(int direction, Button button) {
        boolean shift = Minecraft.getInstance().hasShiftDown();
        int fast = shift ? 64 : 16;
        stepper.press(direction, shift ? 16 : 1, fast,
                () -> button.isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed());
    }

    private int cellAt(double mouseX, double mouseY) {
        int x = (int) Math.floor(mouseX) - leftPos - GRID_X;
        int y = (int) Math.floor(mouseY) - topPos - GRID_Y;
        if (x < 0 || y < 0) return -1;
        int column = x / CELL;
        int row = y / CELL;
        if (column >= COLUMNS || row >= 2 || x % CELL >= CELL - 2 || y % CELL >= CELL - 2) return -1;
        int index = row * COLUMNS + column;
        return index < rates().size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int cell = event.button() == 0 ? cellAt(event.x(), event.y()) : -1;
        if (cell >= 0) {
            if (cell != selected) {
                selected = cell;
                stepper.set(1);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        if (scrollY != 0 && x >= GRID_X && y >= COUNT_Y - 2 && y <= COUNT_Y + COUNT_H + 2) {
            stepper.nudge(scrollY > 0 ? 1 : -1, Minecraft.getInstance().hasShiftDown() ? 16 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (stepper != null) stepper.release();
        return super.mouseReleased(event);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (stepper != null) {
            stepper.tick();
            // A deposit or a withdrawal changed the card: keep the count one the card can pay for.
            if (count > Math.max(1, affordable())) count = clampInt(Math.max(1, affordable()));
        }
        refreshWidgets();
    }

    private void refreshWidgets() {
        if (depositButton == null) return;
        int state = menu.depositState();
        depositButton.active = state == CashierMenu.DEPOSIT_OK || state == CashierMenu.DEPOSIT_CARD;
        boolean card = ChipCards.isCard(menu.card());
        boolean any = card && rate() != null;
        withdrawButton.active = any && cost() > 0 && cost() <= balance();
        less.active = any && shownCount() > 1;
        more.active = any && shownCount() < affordable();
        max.active = any && affordable() > 1;
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        CasinoPanel.background(graphics, leftPos, topPos);
        CasinoPanel.goldWell(graphics, leftPos + CashierMenu.CARD_X, topPos + CashierMenu.CARD_Y);
        CasinoPanel.well(graphics, leftPos + CashierMenu.DEPOSIT_X, topPos + CashierMenu.DEPOSIT_Y);

        List<CashierMenu.Rate> rates = rates();
        for (int i = 0; i < rates.size(); i++) {
            int x = leftPos + GRID_X + (i % COLUMNS) * CELL;
            int y = topPos + GRID_Y + (i / COLUMNS) * CELL;
            if (i == selected) {
                CasinoPanel.goldWell(graphics, x + 1, y + 1);
            } else {
                CasinoPanel.well(graphics, x + 1, y + 1);
            }
            graphics.renderItem(new ItemStack(rates.get(i).item()), x + 1, y + 1);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, CasinoPanel.TEXT_GOLD, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                CasinoPanel.TEXT_MUTED, false);

        boolean card = ChipCards.isCard(menu.card());
        gauge(graphics, GAUGE_X, CashierMenu.CARD_Y - 4, Component.translatable("itemcasino.cashier.balance"),
                card ? Component.literal(ChipText.format(balance()))
                        : Component.translatable("itemcasino.cashier.no_card"),
                card ? CasinoPanel.TEXT_CREAM : CasinoPanel.TEXT_MUTED);

        Component deposit;
        int colour;
        switch (menu.depositState()) {
            case CashierMenu.DEPOSIT_OK, CashierMenu.DEPOSIT_CARD -> {
                deposit = Component.literal("+" + ChipText.format(menu.depositPreviewCents()));
                colour = CasinoPanel.TEXT_WIN;
            }
            case CashierMenu.DEPOSIT_REFUSED -> {
                deposit = Component.translatable("itemcasino.cashier.refused");
                colour = CasinoPanel.TEXT_LOSE;
            }
            default -> {
                deposit = Component.translatable("itemcasino.cashier.put_items");
                colour = CasinoPanel.TEXT_MUTED;
            }
        }
        gauge(graphics, GAUGE_X, CashierMenu.DEPOSIT_Y - 4, Component.translatable("itemcasino.cashier.deposit_value"),
                deposit, colour);

        int right = CasinoLayout.WIDTH - 14;
        if (rates().isEmpty()) {
            leftFit(graphics, Component.translatable("itemcasino.cashier.no_currency"), GRID_X, GRID_Y + 4,
                    right - GRID_X, CasinoPanel.TEXT_MUTED);
            return;
        }
        // The count, between its buttons.
        int countLeft = GRID_X + 12;
        int countRight = right - 28 - 12;
        Component countText = Component.literal("x" + shownCount());
        int countWidth = countRight - countLeft - 2;
        int textWidth = Math.min(font.width(countText), countWidth);
        leftFit(graphics, countText, (countLeft + countRight) / 2 - textWidth / 2, COUNT_Y + 2, countWidth,
                CasinoPanel.TEXT_CREAM);

        long cost = cost();
        boolean short_ = !card || cost > balance();
        CasinoPanel.plate(graphics, GRID_X, COST_Y, right - GRID_X, 24);
        leftFit(graphics, Component.translatable("itemcasino.cashier.cost", InfoBadge.percent(menu.rates().feePpm())),
                GRID_X + 4, COST_Y + 4, right - GRID_X - 8, CasinoPanel.TEXT_MUTED);
        leftFit(graphics, Component.literal(ChipText.format(cost)), GRID_X + 4, COST_Y + 14, right - GRID_X - 8,
                short_ ? CasinoPanel.TEXT_LOSE : CasinoPanel.TEXT_CREAM);
    }

    private void gauge(GuiGraphics graphics, int x, int y, Component caption, Component value, int colour) {
        CasinoPanel.plate(graphics, x, y, GAUGE_W, 24);
        leftFit(graphics, caption, x + 4, y + 4, GAUGE_W - 8, CasinoPanel.TEXT_MUTED);
        leftFit(graphics, value, x + 4, y + 14, GAUGE_W - 8, colour);
    }

    private void leftFit(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int argb) {
        int width = font.width(text);
        if (width <= maxWidth) {
            graphics.drawString(font, text, x, y, argb, false);
            return;
        }
        float scale = maxWidth / (float) width;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y + (1 - scale) * font.lineHeight / 2F);
        graphics.pose().scale(scale, scale);
        graphics.drawString(font, text, 0, 0, argb, false);
        graphics.pose().popMatrix();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        InfoBadge.draw(graphics, font, leftPos + AbstractCasinoScreen.INFO_X, topPos + AbstractCasinoScreen.INFO_Y,
                mouseX, mouseY, Component.translatable("itemcasino.info.cashier",
                        InfoBadge.percent(menu.rates().feePpm())));
        boolean cardHint = renderCardPlaceholder(graphics, mouseX, mouseY);
        int cell = cellAt(mouseX, mouseY);
        if (cardHint) return;                  // the hint is this frame's tooltip
        if (cell >= 0) {
            CashierMenu.Rate rate = rates().get(cell);
            long buy = Chips.withdrawCost(rate.unitValue(), menu.rates().feePpm());
            long sell = Chips.centsForValue(rate.unitValue());
            graphics.setComponentTooltipForNextFrame(font, List.of(
                    new ItemStack(rate.item()).getHoverName(),
                    Component.translatable("itemcasino.cashier.price_buy", ChipText.format(buy))
                            .withStyle(net.minecraft.ChatFormatting.GRAY),
                    Component.translatable("itemcasino.cashier.price_sell", ChipText.format(sell))
                            .withStyle(net.minecraft.ChatFormatting.GRAY)), mouseX, mouseY);
        } else {
            renderTooltip(graphics, mouseX, mouseY);
        }
    }

    /**
     * The empty card slot shows a faded chip card, like the chips-only tables do, and says on hover
     * what it is for: without it the slot is an unlabelled hole next to an unlabelled hole.
     *
     * @return true when the hint was queued as this frame's tooltip
     */
    private boolean renderCardPlaceholder(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.card().isEmpty()) return false;
        int x = leftPos + CashierMenu.CARD_X;
        int y = topPos + CashierMenu.CARD_Y;
        graphics.renderItem(new ItemStack(com.itemcasino.registry.CasinoItems.CHIP_CARD.get()), x, y);
        graphics.fill(x, y, x + 16, y + 16, 0xAA1D2A22);
        if (mouseX < x || mouseX >= x + 16 || mouseY < y || mouseY >= y + 16 || !menu.getCarried().isEmpty()) {
            return false;
        }
        graphics.setTooltipForNextFrame(font, font.split(
                Component.translatable("itemcasino.hint.cashier_card_slot"), 170), mouseX, mouseY);
        return true;
    }
}
