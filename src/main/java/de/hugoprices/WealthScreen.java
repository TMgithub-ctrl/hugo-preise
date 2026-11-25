package de.hugoprices;

import de.hugoprices.logic.MoneyFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Vermögensübersicht: gespeicherte Kisten + Inventar, bewertet mit dem höchsten Order-Preis. */
final class WealthScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GOLD = 0xFFFFAA00;
    private static final int GREEN = 0xFF55FF55;
    private static final int ROW_H = 20;

    private record Row(ItemStack icon, String name, int count, int chests, long unit, long total) {
    }

    private final Screen parent;
    private List<Row> rows = new ArrayList<>();
    private long sum;
    private int unknown;
    private int belowMin;
    private int scroll;
    private int refreshTicks;

    WealthScreen(Screen parent) {
        super(Component.literal("Vermoegen"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.literal("Kisten vergessen"), b -> askClear())
                .bounds(cx - 154, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Aktualisieren"), b -> recompute())
                .bounds(cx - 50, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Schliessen"), b -> onClose())
                .bounds(cx + 54, height - 28, 100, 20).build());
        recompute();
    }

    private void askClear() {
        Minecraft.getInstance().gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                HugoPricesClient.clearChestsForServer();
            }
            Minecraft.getInstance().gui.setScreen(this);
        }, Component.literal("Alle gespeicherten Kisten dieses Servers vergessen?"),
                Component.literal("Danach zaehlt nur noch dein Inventar, bis du Kisten wieder oeffnest.")));
    }

    private void recompute() {
        Map<String, Integer> totals = new HashMap<>(HugoPricesClient.chestTotals());
        Map<String, Integer> chestCounts = HugoPricesClient.chestCounts();
        HugoPricesClient.inventoryTotals().forEach((k, v) -> totals.merge(k, v, Integer::sum));
        long min = HugoPricesClient.settingsWealthMin();
        List<Row> out = new ArrayList<>();
        long s = 0;
        int unk = 0;
        int below = 0;
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            ItemStack icon = HugoPricesClient.stackForKey(e.getKey());
            if (icon.isEmpty() || HugoPricesClient.excludedFromWealth(e.getKey())) {
                continue;
            }
            long unit = HugoPricesClient.unitPriceForKey(e.getKey(), icon);
            if (unit <= 0) {
                unk++;
                continue;
            }
            long total = unit * (long) e.getValue();
            if (total < min) {
                below++;
                continue;
            }
            out.add(new Row(icon, icon.getHoverName().getString(), e.getValue(),
                    chestCounts.getOrDefault(e.getKey(), 0), unit, total));
            s += total;
        }
        out.sort((a, b) -> Long.compare(b.total(), a.total()));
        rows = out;
        sum = s;
        unknown = unk;
        belowMin = below;
    }

    @Override
    public void tick() {
        // Preise kommen nach und nach (max. 1 Abruf pro Sekunde): alle 2 s neu rechnen
        if (++refreshTicks % 40 == 0) {
            recompute();
        }
    }

    private int listTop() {
        return 58;
    }

    private int visibleRows() {
        return Math.max(1, (height - 40 - listTop()) / ROW_H);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, rows.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        String cur = HugoPricesClient.currency();
        int cx = width / 2;
        g.centeredText(font, "Vermoegen: " + MoneyFormat.format(sum, cur), cx, 10, GOLD);
        String sub = HugoPricesClient.chestCount() + " Kisten gespeichert + Inventar  |  nur Items ab "
                + MoneyFormat.compact(HugoPricesClient.settingsWealthMin(), cur) + " Gesamtwert";
        g.centeredText(font, sub, cx, 24, GRAY);
        String info = (unknown > 0 ? unknown + " Items noch ohne Preis (laden ...)" : "Alle Preise geladen")
                + (belowMin > 0 ? "  |  " + belowMin + " kleinere Posten ausgeblendet" : "");
        g.centeredText(font, info, cx, 36, GRAY);

        int w = Math.min(420, width - 20);
        int x = cx - w / 2;
        int y = listTop();
        g.fill(x - 4, y - 4, x + w + 4, y + visibleRows() * ROW_H + 2, 0x90000000);
        if (rows.isEmpty()) {
            g.centeredText(font, "Noch nichts gefunden. Oeffne deine Kisten einmal, dann werden sie gespeichert.",
                    cx, y + 10, WHITE);
            return;
        }
        int end = Math.min(rows.size(), scroll + visibleRows());
        for (int i = scroll; i < end; i++) {
            Row r = rows.get(i);
            g.item(r.icon(), x, y + 1);
            String left = r.count() + "x " + r.name() + (r.chests() > 0 ? "  (" + r.chests() + " Kisten)" : "");
            g.text(font, left, x + 20, y + 5, WHITE);
            String right = MoneyFormat.compact(r.total(), cur) + "  (" + MoneyFormat.compact(r.unit(), cur) + "/Stk)";
            g.text(font, right, x + w - font.width(right), y + 5, GREEN);
            y += ROW_H;
        }
        if (rows.size() > visibleRows()) {
            g.centeredText(font, (scroll + 1) + "-" + end + " von " + rows.size() + "  (Mausrad zum Scrollen)", cx,
                    height - 40, GRAY);
        }
    }
}
