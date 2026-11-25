package de.hugoprices;

import de.hugoprices.logic.MoneyFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Kleines Fenster: Menge eingeben und an die beste Order verkaufen. */
final class SellScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;

    private final ItemStack stack;
    private final String currency;
    private EditBox amountBox;
    private Button sellButton;
    private String error = "";

    SellScreen(ItemStack stack, String currency) {
        super(Component.literal("An Order verkaufen"));
        this.stack = stack.copyWithCount(1);
        this.currency = currency;
    }

    private int inInventory() {
        return SellAutomation.countInInventory(stack.getItem());
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = height / 2 - 10;
        Font font = Minecraft.getInstance().font;
        amountBox = new EditBox(font, cx - 50, y, 100, 20, Component.literal("Menge"));
        amountBox.setValue(String.valueOf(Math.max(1, Math.min(64, inInventory()))));
        amountBox.setResponder(v -> error = "");
        addRenderableWidget(amountBox);
        setInitialFocus(amountBox);

        sellButton = addRenderableWidget(Button.builder(Component.literal("Verkaufen"), b -> sellAmount())
                .bounds(cx - 154, y + 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Alles verkaufen"), b -> sellAll())
                .bounds(cx - 50, y + 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Abbrechen"), b -> onClose())
                .bounds(cx + 54, y + 28, 100, 20).build());
    }

    private int parsedAmount() {
        try {
            return Integer.parseInt(amountBox.getValue().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void sellAmount() {
        int n = parsedAmount();
        int have = inInventory();
        if (n <= 0) {
            error = "Bitte eine Zahl groesser 0 eingeben";
            return;
        }
        if (n > have) {
            error = "Du hast nur " + have + " Stueck";
            return;
        }
        start(n == have ? -1 : n);
    }

    private void sellAll() {
        if (inInventory() <= 0) {
            error = "Davon hast du nichts im Inventar";
            return;
        }
        start(-1);
    }

    private void start(int amount) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        onClose();
        SellAutomation.start(new SellAutomation.Job(stack.getItem(), path, amount, stack.getHoverName()));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        int cx = width / 2;
        int top = height / 2 - 70;
        g.centeredText(font, getTitle(), cx, top, WHITE);
        g.item(stack, cx - 8, top + 12);
        int have = inInventory();
        g.centeredText(font, stack.getHoverName().getString() + "  -  im Inventar: " + have, cx, top + 32, GRAY);

        long unit = HugoPricesClient.unitPriceFor(stack);
        int n = parsedAmount();
        if (unit > 0) {
            String line = "Beste Order: " + MoneyFormat.format(unit, currency) + " / Stk";
            if (n > 0) {
                line += "   ->   " + n + "x = ca. " + MoneyFormat.compact(unit * (long) n, currency);
            }
            g.centeredText(font, line, cx, top + 44, GREEN);
        } else {
            g.centeredText(font, "Preis noch unbekannt (wird beim Verkauf im Menue gewaehlt)", cx, top + 44, GRAY);
        }
        if (!error.isEmpty()) {
            g.centeredText(font, error, cx, height / 2 + 46, RED);
        }
        g.centeredText(font, "Die Mod waehlt die hoechste Order und bestaetigt automatisch.", cx,
                height / 2 + 58, GRAY);
    }
}
