package de.hugoprices;

import de.hugoprices.logic.MoneyFormat;
import de.hugoprices.logic.PriceExtractor;
import de.hugoprices.logic.Settings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Verkauft Items an die beste Order auf HugoSMP. Ein Auftrag = genau ein /order-Befehl, danach klickt die Mod
 * im Order-Menü: höchste Order wählen -> liefern -> bestätigen. Höchstens ein Klick alle paar Ticks.
 * Ablauf auf HugoSMP: Order-Liste -> Liefermenü ("Ganzes Inventar liefern") -> "Bestätigen".
 */
final class SellAutomation {
    private enum Phase { IDLE, WAIT_LIST, WAIT_DELIVER, PLACING, WAIT_CONFIRM, DONE_WAIT }

    /** amount < 0 = alles */
    record Job(Item item, String itemPath, int amount, Component name) {
    }

    private static final int PHASE_TIMEOUT_TICKS = 20 * 6;
    private static final int SETTLE_TICKS = 5;
    private static final Path LOG_FILE = FabricLoader.getInstance().getConfigDir().resolve("hugoprices-verkauf-log.txt");

    private static Phase phase = Phase.IDLE;
    private static Job job;
    private static int ticks;
    private static int settle;
    private static int lastContainerId = -1;
    private static long chosenPrice;
    private static int toPlace;
    private static int placed;
    private static int remainderClicks;
    private static boolean holdingCursor;
    private static int cursorSourceSlot = -1;
    private static int confirmClicks;
    private static boolean sawScreen;
    private static int noScreenTicks;
    private static StringBuilder log = new StringBuilder();
    private static Settings settings;

    private SellAutomation() {
    }

    static void init(Settings s) {
        settings = s;
    }

    static boolean busy() {
        return phase != Phase.IDLE;
    }

    static int countInInventory(Item item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getItem() == item && plain(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Nur unverzauberte Items ohne eigenen Namen werden verkauft (sonst landet die falsche Sache in der Order). */
    static boolean plain(ItemStack s) {
        return s.get(DataComponents.ENCHANTMENTS) == null || s.get(DataComponents.ENCHANTMENTS).isEmpty();
    }

    static void start(Job j) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || busy()) {
            return;
        }
        job = j;
        phase = Phase.WAIT_LIST;
        ticks = 0;
        settle = 0;
        lastContainerId = -1;
        chosenPrice = 0;
        placed = 0;
        remainderClicks = 0;
        holdingCursor = false;
        cursorSourceSlot = -1;
        confirmClicks = 0;
        sawScreen = false;
        noScreenTicks = 0;
        log = new StringBuilder("Verkauf: " + j.itemPath() + " Menge: " + (j.amount() < 0 ? "alles" : j.amount())
                + "\nZeit: " + java.time.LocalDateTime.now() + "\n\n");
        msg(Component.literal("Verkaufe " + amountText() + " ").append(j.name()).append(" an die beste Order ...")
                .withStyle(ChatFormatting.GRAY));
        mc.player.connection.sendCommand("order " + j.itemPath());
    }

    static void cancel(String why) {
        if (phase == Phase.IDLE) {
            return;
        }
        finish(why, true);
    }

    private static String amountText() {
        return job.amount() < 0 ? "alle" : job.amount() + "x";
    }

    private static void finish(String text, boolean error) {
        log.append("\nERGEBNIS: ").append(text).append('\n');
        try {
            Files.writeString(LOG_FILE, log.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // nur Protokoll
        }
        msg(Component.literal(text).withStyle(error ? ChatFormatting.RED : ChatFormatting.GREEN));
        phase = Phase.IDLE;
        job = null;
    }

    static void msg(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[Hugo Preise] ").withStyle(ChatFormatting.GOLD).append(c));
        }
    }

    static void tick(Minecraft mc) {
        if (phase == Phase.IDLE) {
            return;
        }
        if (mc.player == null || mc.gameMode == null) {
            phase = Phase.IDLE;
            return;
        }
        ticks++;
        Screen screen = mc.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> cs)) {
            // Beim Wechsel zwischen zwei Server-Menüs ist kurz kein Menü offen: erst nach 1,5 s abbrechen.
            if (sawScreen && phase != Phase.DONE_WAIT && ++noScreenTicks > 30) {
                finish("Abgebrochen: Menue wurde geschlossen.", true);
                return;
            }
            if (phase == Phase.DONE_WAIT) {
                done();
                return;
            }
            if (ticks > PHASE_TIMEOUT_TICKS) {
                finish("Order-Menue ist nicht aufgegangen (gibt es /order " + job.itemPath() + "?).", true);
            }
            return;
        }
        sawScreen = true;
        noScreenTicks = 0;
        AbstractContainerMenu menu = cs.getMenu();
        if (menu.containerId != lastContainerId) {
            lastContainerId = menu.containerId;
            settle = 0;
            dump(cs, menu);
        }
        if (++settle < SETTLE_TICKS) {
            return; // Inhalt erst ankommen lassen
        }
        if (ticks > PHASE_TIMEOUT_TICKS * 4) {
            finish("Zeitueberschreitung. Details: config/hugoprices-verkauf-log.txt", true);
            return;
        }
        switch (phase) {
            case WAIT_LIST -> chooseOrder(mc, menu);
            case WAIT_DELIVER -> deliver(mc, menu);
            case PLACING -> place(mc, menu);
            case WAIT_CONFIRM -> confirm(mc, menu);
            case DONE_WAIT -> {
                if (settle > 40) {
                    done();
                }
            }
            default -> {
            }
        }
    }

    private static void done() {
        String est = chosenPrice > 0 && job.amount() > 0
                ? " (ca. " + MoneyFormat.compact(chosenPrice * job.amount(), settings.currencySymbol) + ")"
                : chosenPrice > 0 ? " zu " + MoneyFormat.format(chosenPrice, settings.currencySymbol) + "/Stk" : "";
        finish("Geliefert: " + amountText() + " " + job.name().getString() + est
                + ". Pruefe den Chat des Servers fuer den Betrag.", false);
    }

    private static boolean isTop(Slot s) {
        return !(s.container instanceof Inventory);
    }

    private static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int button, ContainerInput type,
            String what) {
        log.append("Klick: Slot ").append(slot).append(" Taste ").append(button).append(' ').append(type)
                .append(" (").append(what).append(")\n");
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, type, mc.player);
    }

    private static void chooseOrder(Minecraft mc, AbstractContainerMenu menu) {
        int best = -1;
        long bestPrice = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            ItemStack st = s.getItem();
            if (!isTop(s) || st.isEmpty() || st.getItem() != job.item()) {
                continue;
            }
            Optional<PriceExtractor.Result> r = PriceExtractor.extract(lore(st), settings);
            if (r.isPresent() && r.get().price() > bestPrice) {
                bestPrice = r.get().price();
                best = i;
            }
        }
        if (best < 0) {
            if (settle > 40) {
                finish("Keine passende Order gefunden. Details: config/hugoprices-verkauf-log.txt", true);
            }
            return;
        }
        chosenPrice = bestPrice;
        log.append("Beste Order: Slot ").append(best).append(" Preis ").append(bestPrice).append('\n');
        click(mc, menu, best, 0, ContainerInput.PICKUP, "Order waehlen");
        phase = Phase.WAIT_DELIVER;
        ticks = 0;
    }

    private static void deliver(Minecraft mc, AbstractContainerMenu menu) {
        if (job.amount() < 0) {
            int btn = findButton(menu, "ganzes inventar liefern", "inventar liefern", "alles liefern");
            if (btn < 0) {
                if (settle > 40) {
                    finish("Knopf 'Ganzes Inventar liefern' nicht gefunden. Details: config/hugoprices-verkauf-log.txt",
                            true);
                }
                return;
            }
            click(mc, menu, btn, 0, ContainerInput.PICKUP, "Ganzes Inventar liefern");
            phase = Phase.WAIT_CONFIRM;
            ticks = 0;
            settle = 0;
            return;
        }
        toPlace = job.amount();
        phase = Phase.PLACING;
        settle = SETTLE_TICKS;
    }

    /** Bestimmte Menge einlegen: volle Stacks per Shift-Klick, Rest Stück für Stück per Rechtsklick. */
    private static void place(Minecraft mc, AbstractContainerMenu menu) {
        if (settle % 2 != 0) {
            return; // höchstens ein Klick alle 2 Ticks
        }
        int left = toPlace - placed;
        if (holdingCursor) {
            if (remainderClicks > 0) {
                int empty = emptyTopSlot(menu);
                if (empty < 0) {
                    finish("Kein freier Platz im Liefermenue. Details: config/hugoprices-verkauf-log.txt", true);
                    return;
                }
                click(mc, menu, empty, 1, ContainerInput.PICKUP, "1 Stueck ablegen");
                remainderClicks--;
                placed++;
                return;
            }
            click(mc, menu, cursorSourceSlot, 0, ContainerInput.PICKUP, "Rest zuruecklegen");
            holdingCursor = false;
            return;
        }
        if (left <= 0) {
            phase = Phase.WAIT_CONFIRM;
            settle = SETTLE_TICKS;
            return;
        }
        int src = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            ItemStack st = s.getItem();
            if (!isTop(s) && !st.isEmpty() && st.getItem() == job.item() && plain(st)) {
                src = i;
                if (st.getCount() <= left) {
                    break; // passt komplett
                }
            }
        }
        if (src < 0) {
            finish("Nicht genug Items im Inventar (eingelegt: " + placed + ").", true);
            return;
        }
        int count = menu.slots.get(src).getItem().getCount();
        if (count <= left) {
            click(mc, menu, src, 0, ContainerInput.QUICK_MOVE, "Stack einlegen");
            placed += count;
            return;
        }
        // Stack ist größer als der Rest: aufnehmen und einzeln ablegen
        click(mc, menu, src, 0, ContainerInput.PICKUP, "Stack aufnehmen");
        holdingCursor = true;
        cursorSourceSlot = src;
        remainderClicks = left;
    }

    private static void confirm(Minecraft mc, AbstractContainerMenu menu) {
        int btn = findButton(menu, "bestätigen", "bestaetigen", "liefern", "abgeben", "confirm");
        if (btn < 0) {
            if (settle > 40) {
                finish("Bestaetigen-Knopf nicht gefunden. Details: config/hugoprices-verkauf-log.txt", true);
            }
            return;
        }
        click(mc, menu, btn, 0, ContainerInput.PICKUP, "Bestaetigen");
        confirmClicks++;
        phase = confirmClicks >= 2 || nameOf(menu.slots.get(btn).getItem()).contains("bestätig")
                ? Phase.DONE_WAIT : Phase.WAIT_CONFIRM;
        settle = 0;
    }

    private static int findButton(AbstractContainerMenu menu, String... words) {
        boolean wantWholeInventory = words.length > 0 && words[0].contains("inventar");
        for (String w : words) {
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot s = menu.slots.get(i);
                ItemStack st = s.getItem();
                if (!isTop(s) || st.isEmpty() || st.getItem() == job.item()) {
                    continue;
                }
                String n = nameOf(st);
                // Nie aus Versehen "Ganzes Inventar liefern" drücken, wenn wir das nicht wollen.
                if (!wantWholeInventory && n.contains("inventar")) {
                    continue;
                }
                if (n.contains(w)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int emptyTopSlot(AbstractContainerMenu menu) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (isTop(s) && s.getItem().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static String nameOf(ItemStack st) {
        return st.getHoverName().getString().replaceAll("§.", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> out = new ArrayList<>();
        if (lore != null) {
            for (Component c : lore.lines()) {
                out.add(c.getString());
            }
        }
        return out;
    }

    private static void dump(AbstractContainerScreen<?> cs, AbstractContainerMenu menu) {
        log.append("=== Menue \"").append(cs.getTitle().getString()).append("\" (id ").append(menu.containerId)
                .append(", Phase ").append(phase).append(") ===\n");
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            ItemStack st = s.getItem();
            if (!isTop(s) || st.isEmpty()) {
                continue;
            }
            log.append("Slot ").append(i).append(": ").append(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath())
                    .append(" x").append(st.getCount()).append(" \"").append(st.getHoverName().getString()).append("\"");
            for (String l : lore(st)) {
                log.append(" | ").append(l);
            }
            log.append('\n');
        }
        log.append('\n');
    }
}
