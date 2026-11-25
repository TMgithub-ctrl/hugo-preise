package de.hugoprices;

import de.hugoprices.logic.ChestStore;
import de.hugoprices.logic.FwmClient;
import de.hugoprices.logic.FwmParser;
import de.hugoprices.logic.ItemKeyBuilder;
import de.hugoprices.logic.MarketClient;
import de.hugoprices.logic.Mode;
import de.hugoprices.logic.ModeDetector;
import de.hugoprices.logic.MoneyFormat;
import de.hugoprices.logic.PriceBook;
import de.hugoprices.logic.PriceExtractor;
import de.hugoprices.logic.Quote;
import de.hugoprices.logic.Settings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Zeigt im Tooltip eines verzauberten Items den niedrigsten Auktionspreis und den höchsten Order-Preis.
 * Quelle 1: Live-Daten von hugosmp-market.net (im Hintergrund abgerufen).
 * Quelle 2 (Ersatz): Preise, die im Auktionshaus-/Order-Menü im Spiel gesehen wurden.
 */
public final class HugoPricesClient implements ClientModInitializer {
    public static final PriceBook BOOK = new PriceBook();
    private static Settings settings = new Settings();
    private static MarketClient market;
    private static FwmClient fwm;
    private static KeyMapping sellAllKey;
    private static KeyMapping sellMenuKey;
    /** Zeitpunkt des ersten Drucks auf "Alles verkaufen" (zweiter Druck innerhalb von 3 s bestätigt). */
    private static long sellAllArmedAt;
    private static KeyMapping wealthKey;
    public static final ChestStore CHESTS = new ChestStore();
    private static Path chestsFile;
    /** Letzter Rechtsklick auf einen Block (um Kisten von Server-Menüs zu unterscheiden). */
    private static long lastUseMillis;
    private static BlockPos lastUsePos;
    private static String lastUseBlock = "";
    private static Map<String, Item> itemsById;
    private static Path pricesFile;
    private static Path dumpFile;
    private static long sessionCounter;
    /** Inhalt des zuletzt gelesenen AH-/Order-Menüs (für die Fehlersuche-Datei). */
    private static String lastDump;

    @Override
    public void onInitializeClient() {
        settings = Settings.load(FabricLoader.getInstance().getConfigDir().resolve("hugoprices.properties"));
        market = new MarketClient(settings);
        fwm = new FwmClient(settings);
        Path configDir = FabricLoader.getInstance().getConfigDir();
        pricesFile = configDir.resolve("hugoprices-preise.txt");
        dumpFile = configDir.resolve("hugoprices-letztes-menue.txt");
        BOOK.load(pricesFile, System.currentTimeMillis(), settings.maxAgeMinutes * 60_000L);
        chestsFile = configDir.resolve("hugoprices-kisten.txt");
        CHESTS.load(chestsFile);

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) {
                lastUseMillis = System.currentTimeMillis();
                lastUsePos = hit.getBlockPos();
                lastUseBlock = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).getPath();
            }
            return InteractionResult.PASS;
        });

        // Menü offen: alle paar Ticks die Einträge lesen. Jedes geöffnete Menü ist eine eigene Sitzung.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return;
            }
            if (screen instanceof InventoryScreen inv && settings.wealthButton) {
                addWealthButton(client, inv);
            }
            String chestKey = chestKeyFor(client, container);
            long session = ++sessionCounter;
            int[] counter = {0};
            ScreenEvents.afterTick(screen).register(s -> {
                if (counter[0]++ % settings.scanEveryTicks == 0) {
                    scan(container, session);
                    if (chestKey != null) {
                        snapshotChest(container, chestKey);
                    }
                }
            });
            ScreenEvents.remove(screen).register(s -> {
                if (chestKey != null) {
                    snapshotChest(container, chestKey);
                    CHESTS.saveIfDirty(chestsFile);
                }
                onMenuClosed();
            });
            // Verkaufs-Tasten auch im Inventar/Menü: dann zählt das Item unter der Maus.
            ScreenKeyboardEvents.afterKeyPress(screen).register((scr, key) -> {
                if (SellAutomation.busy() || !(scr instanceof AbstractContainerScreen<?> acs)) {
                    return;
                }
                if (wealthKey.matches(key)) {
                    client.gui.setScreen(new WealthScreen(scr));
                    return;
                }
                ItemStack hovered = hoveredStack(acs);
                if (hovered.isEmpty()) {
                    return;
                }
                if (sellAllKey.matches(key)) {
                    onSellAll(client, hovered);
                } else if (sellMenuKey.matches(key)) {
                    onSellMenu(client, hovered);
                }
            });
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            BOOK.saveIfDirty(pricesFile);
            CHESTS.saveIfDirty(chestsFile);
        });

        // Tooltip: Preise anhängen.
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> addPriceLines(stack, lines));

        // Anzeige oben rechts für den Block, auf den man schaut.
        BlockPriceHud.register(settings);

        // Verkaufen an Order: Tasten (in Optionen > Steuerung > "Hugo Preise" einstellbar).
        SellAutomation.init(settings);
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("hugoprices", "main"));
        sellAllKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hugoprices.sell_all", -1, cat));
        sellMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hugoprices.sell_menu", 78, cat)); // N
        wealthKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hugoprices.wealth", -1, cat));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (sellAllKey.consumeClick()) {
                onSellAllKey(client);
            }
            while (sellMenuKey.consumeClick()) {
                onSellMenuKey(client);
            }
            while (wealthKey.consumeClick()) {
                if (client.player != null) {
                    client.gui.setScreen(new WealthScreen(null));
                }
            }
            SellAutomation.tick(client);
        });
    }

    // ---------- Vermögen / Kisten ----------

    private static String serverPrefix(Minecraft mc) {
        var sd = mc.getCurrentServer();
        return (sd != null && sd.ip != null ? sd.ip.toLowerCase(java.util.Locale.ROOT) : "local") + "|";
    }

    private static boolean isStorageBlock(String path) {
        return path.endsWith("chest") || path.equals("barrel") || path.endsWith("shulker_box")
                || path.equals("hopper") || path.equals("dispenser") || path.equals("dropper");
    }

    /** Schlüssel der Kiste, wenn dieses Menü gerade durch Rechtsklick auf eine Kiste geöffnet wurde, sonst null. */
    private static String chestKeyFor(Minecraft mc, AbstractContainerScreen<?> screen) {
        if (screen instanceof InventoryScreen || mc.level == null || lastUsePos == null
                || System.currentTimeMillis() - lastUseMillis > 1500 || !isStorageBlock(lastUseBlock)
                || SellAutomation.busy() || ModeDetector.detect(screen.getTitle().getString(), settings).isPresent()) {
            return null;
        }
        lastUseMillis = 0; // nur für genau dieses Menü
        String prefix = serverPrefix(mc);
        if (lastUseBlock.equals("ender_chest")) {
            return prefix + "enderchest";
        }
        String dim = mc.level.dimension().identifier().toString();
        return prefix + dim + "|" + lastUsePos.getX() + "," + lastUsePos.getY() + "," + lastUsePos.getZ();
    }

    private static void snapshotChest(AbstractContainerScreen<?> screen, String key) {
        Map<String, Integer> items = new HashMap<>();
        int top = 0;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            top++;
            ItemStack st = slot.getItem();
            if (!st.isEmpty()) {
                items.merge(keyOf(st), st.getCount(), Integer::sum);
            }
        }
        if (top == 0) {
            return;
        }
        CHESTS.put(key, items, System.currentTimeMillis());
        // Doppelkiste: die andere Hälfte nicht doppelt zählen
        if (top >= 54 && !key.endsWith("enderchest")) {
            int bar = key.lastIndexOf('|');
            String[] xyz = key.substring(bar + 1).split(",");
            try {
                BlockPos pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                for (Direction d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    BlockPos n = pos.relative(d);
                    CHESTS.remove(key.substring(0, bar + 1) + n.getX() + "," + n.getY() + "," + n.getZ());
                }
            } catch (RuntimeException ignored) {
                // Schlüssel ohne Koordinaten
            }
        }
    }

    private static void addWealthButton(Minecraft mc, InventoryScreen inv) {
        int left = intField(inv, "leftPos", (inv.width - 176) / 2);
        int top = intField(inv, "topPos", (inv.height - 166) / 2);
        int w = intField(inv, "imageWidth", 176);
        int y = Math.max(2, top - 18);
        Button b = Button.builder(Component.literal("Vermoegen"), btn -> mc.gui.setScreen(new WealthScreen(inv)))
                .bounds(left + w - 62, y, 62, 16)
                .tooltip(Tooltip.create(Component.literal("Wert von Inventar und gespeicherten Kisten")))
                .build();
        Screens.getWidgets(inv).add(b);
    }

    private static int intField(Object o, String name, int fallback) {
        try {
            java.lang.reflect.Field f = AbstractContainerScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    static Map<String, Integer> chestTotals() {
        return CHESTS.totals(serverPrefix(Minecraft.getInstance()));
    }

    static Map<String, Integer> chestCounts() {
        return CHESTS.chestCounts(serverPrefix(Minecraft.getInstance()));
    }

    static int chestCount() {
        return CHESTS.count(serverPrefix(Minecraft.getInstance()));
    }

    static void clearChestsForServer() {
        CHESTS.clearPrefix(serverPrefix(Minecraft.getInstance()));
        CHESTS.saveIfDirty(chestsFile);
    }

    static Map<String, Integer> inventoryTotals() {
        Map<String, Integer> out = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (ItemStack st : mc.player.getInventory().getNonEquipmentItems()) {
                if (!st.isEmpty()) {
                    out.merge(keyOf(st), st.getCount(), Integer::sum);
                }
            }
        }
        return out;
    }

    static long settingsWealthMin() {
        return settings.wealthMinValue;
    }

    static String currency() {
        return settings.currencySymbol;
    }

    /** Symbol-Stack für einen gespeicherten Schlüssel wie "minecraft:diamond" oder "minecraft:book|...". */
    static ItemStack stackForKey(String key) {
        if (itemsById == null) {
            Map<String, Item> m = new HashMap<>();
            for (Item it : BuiltInRegistries.ITEM) {
                m.put(BuiltInRegistries.ITEM.getKey(it).toString(), it);
            }
            itemsById = m;
        }
        int bar = key.indexOf('|');
        Item it = itemsById.get(bar < 0 ? key : key.substring(0, bar));
        return it == null ? ItemStack.EMPTY : new ItemStack(it);
    }

    /** Stückpreis für einen Schlüssel: normale Items über FWM/Spiel, verzauberte nur über im Spiel gesehene Preise. */
    static long unitPriceForKey(String key, ItemStack icon) {
        if (key.indexOf('|') < 0) {
            return unitPriceFor(icon);
        }
        long maxAge = settings.maxAgeMinutes * 60_000L;
        return BOOK.get(Mode.ORDER, key, System.currentTimeMillis(), maxAge).map(PriceBook.Entry::price)
                .orElseGet(() -> BOOK.get(Mode.AH, key, System.currentTimeMillis(), maxAge)
                        .map(PriceBook.Entry::price).orElse(0L));
    }

    private static void onSellAllKey(Minecraft mc) {
        if (mc.player != null) {
            onSellAll(mc, mc.player.getMainHandItem());
        }
    }

    private static void onSellMenuKey(Minecraft mc) {
        if (mc.player != null) {
            onSellMenu(mc, mc.player.getMainHandItem());
        }
    }

    /** Item unter der Maus in einem Menü (Feld "hoveredSlot", seit 26.1 ohne Verschleierung per Name erreichbar). */
    private static ItemStack hoveredStack(AbstractContainerScreen<?> screen) {
        try {
            java.lang.reflect.Field f = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
            f.setAccessible(true);
            Object slot = f.get(screen);
            if (slot instanceof Slot s && !s.getItem().isEmpty()) {
                return s.getItem();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // nicht gefunden: dann eben nicht
        }
        return ItemStack.EMPTY;
    }

    private static void onSellAll(Minecraft mc, ItemStack hand) {
        if (mc.player == null || SellAutomation.busy()) {
            return;
        }
        if (hand.isEmpty() || !SellAutomation.plain(hand)) {
            mc.gui.hud.setOverlayMessage(Component.literal("Item in die Hand nehmen oder im Inventar mit der Maus drauf zeigen")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        int have = SellAutomation.countInInventory(hand.getItem());
        if (have <= 0) {
            mc.gui.hud.setOverlayMessage(Component.literal("Davon hast du nichts im Inventar")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sellAllArmedAt > 3000) {
            sellAllArmedAt = now;
            long unit = unitPriceFor(hand);
            String price = unit > 0 ? " fuer ca. " + MoneyFormat.compact(unit * (long) have, settings.currencySymbol)
                    : "";
            mc.gui.hud.setOverlayMessage(Component.literal("Nochmal druecken: alle " + have + "x "
                    + hand.getHoverName().getString() + price + " an Order verkaufen").withStyle(ChatFormatting.YELLOW),
                    false);
            return;
        }
        sellAllArmedAt = 0;
        String path = BuiltInRegistries.ITEM.getKey(hand.getItem()).getPath();
        SellAutomation.start(new SellAutomation.Job(hand.getItem(), path, -1, hand.getHoverName()));
    }

    private static void onSellMenu(Minecraft mc, ItemStack hand) {
        if (mc.player == null || SellAutomation.busy()) {
            return;
        }
        if (hand.isEmpty() || !SellAutomation.plain(hand)) {
            mc.gui.hud.setOverlayMessage(Component.literal("Item in die Hand nehmen oder im Inventar mit der Maus drauf zeigen")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        mc.gui.setScreen(new SellScreen(hand, settings.currencySymbol));
    }

    /** Bester bekannter Stückpreis (höchste Order) für die Verkaufs-Anzeige. 0 = unbekannt. */
    static long unitPriceFor(ItemStack stack) {
        long nowMs = System.currentTimeMillis();
        long maxAge = settings.maxAgeMinutes * 60_000L;
        FwmParser.Stats st = null;
        String fwmKey = settings.fwmEnabled ? fwmKeyOf(stack) : null;
        if (fwmKey != null) {
            FwmClient.Lookup fl = fwm.lookup(fwmKey);
            st = fl.result() != null ? fl.result().stats() : null;
        }
        return bestUnitPrice(null, st, keyOf(stack), nowMs, maxAge);
    }

    private static void onMenuClosed() {
        BOOK.saveIfDirty(pricesFile);
        String dump = lastDump;
        if (settings.dumpMenus && dump != null) {
            lastDump = null;
            try {
                Files.writeString(dumpFile, dump, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // egal, nur zur Fehlersuche
            }
        }
    }

    private static void scan(AbstractContainerScreen<?> screen, long session) {
        String title = screen.getTitle().getString();
        Optional<Mode> mode = ModeDetector.detect(title, settings);
        if (mode.isEmpty()) {
            return;
        }
        StringBuilder dump = settings.dumpMenus ? new StringBuilder() : null;
        if (dump != null) {
            dump.append("Menue-Titel: ").append(title).append("\nErkannt als: ").append(mode.get())
                    .append("\nZeit: ").append(java.time.LocalDateTime.now()).append("\n\n");
        }
        long now = System.currentTimeMillis();
        long maxAge = settings.maxAgeMinutes * 60_000L;
        int slotNr = -1;
        for (Slot slot : screen.getMenu().slots) {
            slotNr++;
            if (slot.container instanceof Inventory) {
                continue; // eigenes Inventar überspringen, nur die Menü-Einträge zählen
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            List<String> lore = loreOf(stack);
            Optional<PriceExtractor.Result> found = PriceExtractor.extract(lore, settings);
            if (dump != null) {
                dump.append("[Slot ").append(slotNr).append("] ").append(keyOf(stack))
                        .append(" x").append(stack.getCount()).append(" \"").append(stack.getHoverName().getString())
                        .append("\"\n");
                for (String l : lore) {
                    dump.append("    | ").append(l).append('\n');
                }
                dump.append("    -> erkannter Preis: ")
                        .append(found.map(f -> f.price() + (f.perPieceHint() ? " (pro Stueck)" : "")).orElse("keiner"))
                        .append("\n\n");
            }
            if (found.isEmpty()) {
                continue;
            }
            boolean total = mode.get() == Mode.AH ? settings.ahPriceIsTotal : settings.orderPriceIsTotal;
            long price = found.get().price();
            if (total && !found.get().perPieceHint()) {
                price = PriceExtractor.perPiece(price, stack.getCount());
            }
            BOOK.observe(mode.get(), keyOf(stack), price, now, maxAge, session);
        }
        if (dump != null) {
            lastDump = dump.toString();
        }
    }

    static void addPriceLines(ItemStack stack, List<Component> lines) {
        addPriceLines(stack, lines, false);
    }

    /** @param hint true (Anzeige oben rechts): zeigt einen Hinweis, wenn noch kein Preis bekannt ist */
    static void addPriceLines(ItemStack stack, List<Component> lines, boolean hint) {
        if (stack.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long maxAge = settings.maxAgeMinutes * 60_000L;
        String cur = settings.currencySymbol;

        MarketClient.Result web = null;
        boolean loading = false;
        String webError = null;
        String marketKey = settings.webEnabled ? marketKeyOf(stack) : null;
        if (marketKey != null) {
            MarketClient.Lookup lookup = market.lookup(marketKey);
            web = lookup.result();
            loading = lookup.loading() && web == null;
            webError = web == null ? lookup.error() : null;
        } else if (settings.webEnabled && isUnsupported(stack)) {
            lines.add(Component.literal("Preise: fuer diese Item-Art nicht verfuegbar").withStyle(ChatFormatting.GRAY));
        }
        FwmParser.Stats fwmStats = null;
        String fwmKey = settings.fwmEnabled ? fwmKeyOf(stack) : null;
        if (fwmKey != null) {
            FwmClient.Lookup fl = fwm.lookup(fwmKey);
            if (fl.result() != null) {
                fwmStats = fl.result().stats();
            } else {
                loading = loading || fl.loading();
                if (webError == null && !fl.loading()) {
                    webError = fl.error();
                }
            }
        }
        String gameKey = keyOf(stack);

        if (settings.showOrder && fwmStats != null && fwmStats.orderMax().isPresent()) {
            FwmParser.Value max = fwmStats.orderMax().get();
            String avg = fwmStats.orderAvg().map(a -> ", \u00d8 " + MoneyFormat.compact(a.price(), cur)).orElse("");
            lines.add(Component.literal("Order: " + MoneyFormat.format(max.price(), cur)
                    + " (" + max.period() + avg + ")").withStyle(ChatFormatting.AQUA));
        }
        if (settings.showAh && fwmStats != null && fwmStats.ahAvg().isPresent()) {
            FwmParser.Value ah = fwmStats.ahAvg().get();
            lines.add(Component.literal("AH \u00d8: " + MoneyFormat.format(ah.price(), cur)
                    + " (" + ah.period() + ")").withStyle(ChatFormatting.GOLD));
        }
        boolean fwmHasAh = fwmStats != null && fwmStats.ahAvg().isPresent();
        boolean fwmHasOrder = fwmStats != null && fwmStats.orderMax().isPresent();

        if (settings.showAh && !fwmHasAh) {
            Optional<Quote> q = web != null ? web.auction() : Optional.empty();
            if (q.isPresent()) {
                lines.add(Component.literal("AH: " + MoneyFormat.format(q.get().price(), cur)
                        + " (" + MoneyFormat.age(nowMs - q.get().seenAt().toEpochMilli()) + ")")
                        .withStyle(ChatFormatting.GOLD));
            } else {
                Optional<PriceBook.Entry> g = BOOK.get(Mode.AH, gameKey, nowMs, maxAge);
                if (g.isPresent()) {
                    lines.add(Component.literal("AH: " + MoneyFormat.format(g.get().price(), cur)
                            + " (im Spiel gesehen, " + MoneyFormat.age(nowMs - g.get().seenAtMillis()) + ")")
                            .withStyle(ChatFormatting.GOLD));
                } else if (web != null) {
                    lines.add(Component.literal("AH: kein aktuelles Angebot").withStyle(ChatFormatting.GRAY));
                }
            }
        }
        if (settings.showOrder && !fwmHasOrder) {
            Optional<Quote> q = web != null ? web.order() : Optional.empty();
            if (q.isPresent()) {
                lines.add(Component.literal("Order: " + MoneyFormat.format(q.get().price(), cur)
                        + " (" + MoneyFormat.age(nowMs - q.get().seenAt().toEpochMilli()) + ")")
                        .withStyle(ChatFormatting.AQUA));
            } else {
                Optional<PriceBook.Entry> g = BOOK.get(Mode.ORDER, gameKey, nowMs, maxAge);
                if (g.isPresent()) {
                    lines.add(Component.literal("Order: " + MoneyFormat.format(g.get().price(), cur)
                            + " (im Spiel gesehen, " + MoneyFormat.age(nowMs - g.get().seenAtMillis()) + ")")
                            .withStyle(ChatFormatting.AQUA));
                } else if (web != null) {
                    lines.add(Component.literal("Order: keine Order gefunden").withStyle(ChatFormatting.GRAY));
                }
            }
        }
        if (settings.showValue) {
            long unit = bestUnitPrice(web, fwmStats, gameKey, nowMs, maxAge);
            if (unit > 0) {
                addValueLine(stack, gameKey, unit, lines);
            }
        }
        if (fwmStats != null && !fwmStats.isEmpty() && !hint) {
            lines.add(Component.literal("Quelle: FriendsWithMoney").withStyle(ChatFormatting.DARK_GRAY));
        } else if (fwmStats != null && lines.isEmpty() && !hint) {
            lines.add(Component.literal("Keine Marktdaten fuer dieses Item").withStyle(ChatFormatting.GRAY));
        }
        if (hint && lines.isEmpty() && !loading && webError == null) {
            lines.add(Component.literal("Noch kein Preis bekannt").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("/order " + BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()
                    + " oeffnen").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        if (loading) {
            lines.add(Component.literal("Preise werden geladen ...").withStyle(ChatFormatting.DARK_GRAY));
        } else if (webError != null) {
            lines.add(Component.literal("Preise nicht abrufbar: " + webError).withStyle(ChatFormatting.RED));
        }
    }

    /** Stückpreis für die Wert-Berechnung: bevorzugt der höchste Order-Preis (das bekommst du beim Verkaufen). */
    private static long bestUnitPrice(MarketClient.Result web, FwmParser.Stats fwmStats, String gameKey, long nowMs,
            long maxAge) {
        if (fwmStats != null && fwmStats.orderMax().isPresent()) {
            return fwmStats.orderMax().get().price();
        }
        Optional<PriceBook.Entry> g = BOOK.get(Mode.ORDER, gameKey, nowMs, maxAge);
        if (g.isPresent()) {
            return g.get().price();
        }
        if (web != null && web.order().isPresent()) {
            return web.order().get().price();
        }
        if (fwmStats != null && fwmStats.ahAvg().isPresent()) {
            return fwmStats.ahAvg().get().price();
        }
        return BOOK.get(Mode.AH, gameKey, nowMs, maxAge).map(PriceBook.Entry::price).orElse(0L);
    }

    /** Eine kurze Zeile: "Wert: x64 = $1,4 Mio · Inventar x320 = $7 Mio". */
    private static void addValueLine(ItemStack stack, String gameKey, long unit, List<Component> lines) {
        int stackCount = stack.getCount();
        int invCount = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) {
                if (!s.isEmpty() && s.getItem() == stack.getItem() && keyOf(s).equals(gameKey)) {
                    invCount += s.getCount();
                }
            }
        }
        StringBuilder sb = new StringBuilder("Wert: ");
        boolean any = false;
        if (stackCount > 1) {
            sb.append('x').append(stackCount).append(" = ").append(MoneyFormat.compact(times(unit, stackCount),
                    settings.currencySymbol));
            any = true;
        }
        if (invCount > 0 && invCount != stackCount) {
            if (any) {
                sb.append(" \u00b7 ");
            }
            sb.append("Inventar x").append(invCount).append(" = ")
                    .append(MoneyFormat.compact(times(unit, invCount), settings.currencySymbol));
            any = true;
        }
        if (any) {
            lines.add(Component.literal(sb.toString()).withStyle(ChatFormatting.GREEN));
        }
    }

    private static long times(long unit, int count) {
        try {
            return Math.multiplyExact(unit, (long) count);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Schlüssel für friendswithmoney.net: nur unverzauberte Vanilla-Items, z. B. "netherite_ingot". */
    private static String fwmKeyOf(ItemStack stack) {
        if (isUnsupported(stack) || !enchantsOf(stack).isEmpty()) {
            return null;
        }
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!"minecraft".equals(id.getNamespace())) {
            return null;
        }
        return id.getPath();
    }

    /** Items, deren Marktschlüssel mehr als die ID braucht (Tränke, Spawner, Köpfe ...): nicht abfragen. */
    private static boolean isUnsupported(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.endsWith("potion") || id.endsWith("tipped_arrow") || id.endsWith("spawner")
                || id.endsWith("_head") || id.endsWith("_skull") || id.endsWith("written_book")
                || id.endsWith("filled_map") || id.endsWith("firework_rocket") || id.endsWith("firework_star");
    }

    private static List<String> loreOf(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> out = new ArrayList<>();
        if (lore != null) {
            for (Component c : lore.lines()) {
                out.add(c.getString());
            }
        }
        return out;
    }

    /** Verzauberungen als "minecraft:sharpness=5" (bei Büchern die gespeicherten). */
    private static List<String> enchantsOf(ItemStack stack) {
        List<String> ench = new ArrayList<>();
        var direct = stack.get(DataComponents.ENCHANTMENTS);
        if (direct != null) {
            direct.entrySet().forEach(e -> ench.add(e.getKey().getRegisteredName() + "=" + e.getIntValue()));
        }
        var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null) {
            stored.entrySet().forEach(e -> ench.add(e.getKey().getRegisteredName() + "=" + e.getIntValue()));
        }
        return ench;
    }

    /** Schlüssel für hugosmp-market.net, z. B. "enchanted_book/multishot:1". null = nicht abfragen. */
    private static String marketKeyOf(ItemStack stack) {
        if (isUnsupported(stack)) {
            return null;
        }
        List<String> ench = enchantsOf(stack);
        if (ench.isEmpty() && settings.onlyEnchanted) {
            return null;
        }
        return ItemKeyBuilder.build(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), ench,
                settings.enchantSeparator);
    }

    /** Schlüssel für die im Spiel gesehenen Preise: Item-ID + sortierte Verzauberungen. */
    private static String keyOf(ItemStack stack) {
        StringBuilder sb = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        List<String> ench = new ArrayList<>();
        var direct = stack.get(DataComponents.ENCHANTMENTS);
        if (direct != null) {
            direct.entrySet().forEach(e -> ench.add(e.getKey().getRegisteredName() + "=" + e.getIntValue()));
        }
        var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null) {
            stored.entrySet().forEach(e -> ench.add("stored:" + e.getKey().getRegisteredName() + "=" + e.getIntValue()));
        }
        java.util.Collections.sort(ench);
        for (String e : ench) {
            sb.append('|').append(e);
        }
        return sb.toString();
    }
}
