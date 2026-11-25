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
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Verkauft Items an die beste Order auf HugoSMP. Ein Auftrag = genau ein /order-Befehl, danach klickt die Mod
 * im Order-Menü: höchste Order wählen -> liefern -> bestätigen. Höchstens ein Klick alle paar Ticks.
 * Ablauf auf HugoSMP: Order-Liste -> Liefermenü ("Ganzes Inventar liefern") -> "Bestätigen".
 */
final class SellAutomation {
    private enum Phase { IDLE, WAIT_LIST, WAIT_DELIVER, WAIT_SIGN, WAIT_CONFIRM, DONE_WAIT }

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
    private static int confirmClicks;
    private static boolean sawScreen;
    private static int noScreenTicks;
    private static int listContainerId = -1;
    private static int deliverContainerId = -1;
    private static int confirmWait;
    private static boolean dumped;
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
        confirmClicks = 0;
        sawScreen = false;
        noScreenTicks = 0;
        listContainerId = -1;
        deliverContainerId = -1;
        confirmWait = 0;
        dumped = false;
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
        if (ticks > 20 * 30) {
            finish("Zeitueberschreitung. Details: config/hugoprices-verkauf-log.txt", true);
            return;
        }
        Screen screen = mc.gui.screen();

        // Schild für "Bestimmte Menge liefern": Menge in die erste Zeile schreiben und Schild schließen.
        if (phase == Phase.WAIT_SIGN && screen instanceof AbstractSignEditScreen sign) {
            if (++settle < SETTLE_TICKS) {
                return;
            }
            fillSign(sign);
            return;
        }

        if (!(screen instanceof AbstractContainerScreen<?> cs)) {
            // Beim Wechsel zwischen Server-Menüs ist kurz keins offen: erst nach 2 s als "geschlossen" werten.
            if (phase == Phase.DONE_WAIT) {
                done();
                return;
            }
            if (phase == Phase.WAIT_CONFIRM && ++noScreenTicks > 40) {
                // Server hat nach dem Liefern ohne Bestätigung geschlossen
                done();
                return;
            }
            if (sawScreen && phase != Phase.WAIT_SIGN && ++noScreenTicks > 40) {
                finish("Abgebrochen: Menue wurde geschlossen.", true);
            } else if (!sawScreen && ticks > PHASE_TIMEOUT_TICKS) {
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
            dumped = false;
        }
        if (++settle < SETTLE_TICKS) {
            return; // Inhalt erst ankommen lassen
        }
        if (!dumped && settle >= SETTLE_TICKS + 3) {
            dump(cs, menu);
            dumped = true;
        }
        switch (phase) {
            case WAIT_LIST -> chooseOrder(mc, menu);
            case WAIT_DELIVER -> deliver(mc, menu);
            case WAIT_SIGN -> {
                // Schild ist noch nicht aufgegangen
                if (settle > 80) {
                    finish("Schild fuer die Menge ist nicht aufgegangen. Details: config/hugoprices-verkauf-log.txt",
                            true);
                }
            }
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
                + ". Den genauen Betrag zeigt der Server im Chat.", false);
    }

    private static boolean isTop(Slot s) {
        return !(s.container instanceof Inventory);
    }

    private static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int button, ContainerInput type,
            String what) {
        log.append("Klick: Slot ").append(slot).append(" Taste ").append(button).append(' ').append(type)
                .append(" (").append(what).append(") im Menue ").append(menu.containerId).append('\n');
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
            if (settle > 60) {
                finish("Keine passende Order gefunden. Details: config/hugoprices-verkauf-log.txt", true);
            }
            return;
        }
        chosenPrice = bestPrice;
        listContainerId = menu.containerId;
        log.append("Beste Order: Slot ").append(best).append(" Preis ").append(bestPrice).append('\n');
        click(mc, menu, best, 0, ContainerInput.PICKUP, "Order waehlen");
        phase = Phase.WAIT_DELIVER;
        ticks = 0;
    }

    /** Liefermenü: "Ganzes Inventar liefern" oder "Bestimmte Menge liefern" (öffnet ein Schild). */
    private static void deliver(Minecraft mc, AbstractContainerMenu menu) {
        if (menu.containerId == listContainerId) {
            return; // Server hat das Liefermenü noch nicht geschickt
        }
        int whole = findButton(menu, "ganzes inventar liefern");
        int some = findButton(menu, "bestimmte menge");
        if (whole < 0 && some < 0) {
            if (settle > 60) {
                finish("Liefermenue nicht erkannt. Details: config/hugoprices-verkauf-log.txt", true);
            }
            return;
        }
        deliverContainerId = menu.containerId;
        if (job.amount() < 0) {
            click(mc, menu, whole, 0, ContainerInput.PICKUP, "Ganzes Inventar liefern");
            phase = Phase.WAIT_CONFIRM;
        } else {
            if (some < 0) {
                finish("Knopf 'Bestimmte Menge liefern' fehlt. Details: config/hugoprices-verkauf-log.txt", true);
                return;
            }
            click(mc, menu, some, 0, ContainerInput.PICKUP, "Bestimmte Menge liefern");
            phase = Phase.WAIT_SIGN;
        }
        settle = 0;
        confirmWait = 0;
    }

    /** Menge ins Schild schreiben (wie selbst eintippen) und Schild schließen, damit es gesendet wird. */
    private static void fillSign(AbstractSignEditScreen sign) {
        try {
            java.lang.reflect.Field f = AbstractSignEditScreen.class.getDeclaredField("messages");
            f.setAccessible(true);
            String[] lines = (String[]) f.get(sign);
            log.append("Schild vorher: ").append(String.join(" / ", lines)).append('\n');
            lines[0] = String.valueOf(job.amount());
            log.append("Schild: Zeile 1 = ").append(lines[0]).append('\n');
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.append("Schild nicht beschreibbar: ").append(e).append('\n');
            msg(Component.literal("Bitte " + job.amount() + " ins Schild tippen und auf Fertig klicken.")
                    .withStyle(ChatFormatting.YELLOW));
            phase = Phase.WAIT_CONFIRM;
            settle = 0;
            confirmWait = 0;
            return;
        }
        sign.onClose();
        phase = Phase.WAIT_CONFIRM;
        settle = 0;
        confirmWait = 0;
    }

    /**
     * "Bestätigen" drücken. Der Knopf ist eine Barriere, solange noch nichts gewählt ist; wir warten kurz,
     * bis der Server ihn umstellt, und drücken ihn sonst trotzdem.
     */
    private static void confirm(Minecraft mc, AbstractContainerMenu menu) {
        int btn = findButton(menu, "bestätigen", "bestaetigen", "confirm");
        if (btn < 0) {
            if (settle > 60) {
                finish("Bestaetigen-Knopf nicht gefunden. Details: config/hugoprices-verkauf-log.txt", true);
            }
            return;
        }
        boolean disabled = menu.slots.get(btn).getItem().getItem() == Items.BARRIER;
        if (disabled && ++confirmWait < 30) {
            return;
        }
        if (!dumped) {
            return;
        }
        click(mc, menu, btn, 0, ContainerInput.PICKUP, disabled ? "Bestaetigen (Barriere)" : "Bestaetigen");
        confirmClicks++;
        phase = Phase.DONE_WAIT;
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
