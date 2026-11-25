package de.hugoprices.logic;

import java.util.List;
import java.util.Optional;

/** Einfacher Test ohne Bibliotheken: java de.hugoprices.logic.LogicTest */
public final class LogicTest {
    private static int failed = 0;
    private static int passed = 0;

    private static void eq(Object expected, Object actual, String name) {
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FEHLER " + name + ": erwartet " + expected + " war " + actual);
        }
    }

    private static Object p(String text) {
        var v = PriceParser.findFirst(text);
        return v.isPresent() ? (Object) v.getAsLong() : "leer";
    }

    public static void main(String[] args) {
        // Preis-Parser
        eq(1500000L, p("$1.500.000"), "punkt-gruppen");
        eq(1500000L, p("Preis: $1,500,000"), "komma-gruppen");
        eq(1500000L, p("1 500 000$"), "leerzeichen");
        eq(2500000L, p("2,5M"), "2,5M");
        eq(2500000L, p("2.5m"), "2.5m");
        eq(1500000L, p("1,5 Mio"), "mio");
        eq(750L, p("$750"), "einfach");
        eq(1500L, p("$1.500"), "tausenderpunkt");
        eq(1500L, p("$1,500"), "tausenderkomma");
        eq(1250L, p("1,25k"), "1,25k");
        eq(3000000000L, p("3 Mrd"), "mrd");
        eq(12345678L, p("$12.345.678,00"), "mit nachkomma");
        eq(2L, p("$1,5"), "eins-komma-fuenf rundet");
        eq("leer", p("kein preis hier"), "keine zahl");
        eq("leer", p(""), "leer");

        Settings s = new Settings();

        // Preis aus Lore
        Optional<PriceExtractor.Result> r = PriceExtractor.extract(
                List.of("§7Verkäufer: Steve", "§6Preis: §a$1.500.000", "§7Läuft ab in 2h"), s);
        eq(1500000L, r.get().price(), "lore preis");
        eq(false, r.get().perPieceHint(), "kein stueck-hinweis");

        r = PriceExtractor.extract(List.of("Preis: $2.000 pro Stück"), s);
        eq(true, r.get().perPieceHint(), "stueck-hinweis");

        r = PriceExtractor.extract(List.of("Verkäufer: 4711", "Kosten: $900"), s);
        eq(900L, r.get().price(), "ersatz waehrungszeichen");

        r = PriceExtractor.extract(List.of("Verkäufer: Steve", "Klick zum Kaufen"), s);
        eq(true, r.isEmpty(), "kein preis gefunden");

        // Pro Stück
        eq(250L, PriceExtractor.perPiece(1000, 4), "pro stueck");
        eq(1000L, PriceExtractor.perPiece(1000, 1), "pro stueck 1");

        // Menü-Erkennung
        eq(Mode.AH, ModeDetector.detect("Auktionshaus", s).get(), "ah titel");
        eq(Mode.AH, ModeDetector.detect("§8AH » Seite 1", s).get(), "ah kurz");
        eq(Mode.ORDER, ModeDetector.detect("Orders", s).get(), "order titel");
        eq(true, ModeDetector.detect("Truhe", s).isEmpty(), "keine erkennung truhe");
        eq(true, ModeDetector.detect("Shop", s).isEmpty(), "ah nicht in shop");

        // Preis-Speicher: AH = niedrigster, Order = höchster
        PriceBook b = new PriceBook();
        long max = 15 * 60_000L;
        b.observe(Mode.AH, "x", 500, 0, max);
        b.observe(Mode.AH, "x", 300, 1000, max);
        b.observe(Mode.AH, "x", 400, 2000, max);
        eq(300L, b.get(Mode.AH, "x", 3000, max).get().price(), "ah niedrigster");
        b.observe(Mode.ORDER, "x", 100, 0, max);
        b.observe(Mode.ORDER, "x", 250, 1000, max);
        b.observe(Mode.ORDER, "x", 200, 2000, max);
        eq(250L, b.get(Mode.ORDER, "x", 3000, max).get().price(), "order hoechster");
        // Abgelaufen
        eq(true, b.get(Mode.AH, "x", max + 5000, max).isEmpty(), "abgelaufen");
        // Nach Ablauf wird ein neuer (auch teurerer) AH-Preis übernommen
        b.observe(Mode.AH, "x", 900, max + 6000, max);
        eq(900L, b.get(Mode.AH, "x", max + 7000, max).get().price(), "neu nach ablauf");

        // Sitzungen: innerhalb eines Menüs zählt der beste Wert, ein neues Menü ersetzt alte Werte
        PriceBook sb = new PriceBook();
        long day = 24 * 60 * 60_000L;
        sb.observe(Mode.ORDER, "stone", 50, 0, day, 1);
        sb.observe(Mode.ORDER, "stone", 30, 10, day, 1);
        eq(50L, sb.get(Mode.ORDER, "stone", 20, day).get().price(), "sitzung bester wert");
        sb.observe(Mode.ORDER, "stone", 40, 100, day, 2);
        eq(40L, sb.get(Mode.ORDER, "stone", 200, day).get().price(), "neue sitzung ersetzt");
        sb.observe(Mode.AH, "dirt", 7, 100, day, 2);

        // Speichern und Laden
        PriceBook loaded = new PriceBook();
        loaded.loadLines(sb.toLines(), 300, day);
        eq(40L, loaded.get(Mode.ORDER, "stone", 300, day).get().price(), "geladen order");
        eq(7L, loaded.get(Mode.AH, "dirt", 300, day).get().price(), "geladen ah");
        PriceBook old = new PriceBook();
        old.loadLines(sb.toLines(), day + 1000, day);
        eq(0, old.size(), "zu alte werte nicht geladen");
        PriceBook junk = new PriceBook();
        junk.loadLines(java.util.List.of("kaputt", "ORDER\tx\tabc\t1", "FOO\tx\t1\t1"), 0, day);
        eq(0, junk.size(), "kaputte zeilen ignoriert");

        // Kisten-Speicher
        ChestStore cs = new ChestStore();
        cs.put("srv|ow|1,64,1", java.util.Map.of("minecraft:diamond", 64, "minecraft:stone", 10), 1000);
        cs.put("srv|ow|5,64,5", java.util.Map.of("minecraft:diamond", 6), 2000);
        cs.put("other|ow|1,64,1", java.util.Map.of("minecraft:diamond", 999), 3000);
        eq(70, cs.totals("srv|").get("minecraft:diamond"), "kisten summe");
        eq(2, cs.chestCounts("srv|").get("minecraft:diamond"), "kisten anzahl");
        eq(2, cs.count("srv|"), "kisten pro server");
        cs.put("srv|ow|5,64,5", java.util.Map.of(), 4000);
        eq(64, cs.totals("srv|").get("minecraft:diamond"), "geleerte kiste");
        ChestStore cs2 = new ChestStore();
        cs2.loadLines(cs.toLines());
        eq(64, cs2.totals("srv|").get("minecraft:diamond"), "kisten geladen");
        eq(999, cs2.totals("other|").get("minecraft:diamond"), "anderer server getrennt");
        eq(2, cs2.clearPrefix("srv|"), "kisten geloescht");
        eq(0, cs2.count("srv|"), "kisten leer");

        // FriendsWithMoney-Statistik (gekürzte echte Antwort)
        String fwmJson = "{\"ok\":true,\"item\":{\"itemKey\":\"netherite_ingot\"},\"stats\":["
                + "{\"period\":\"1h\",\"periodMinutes\":60,\"ahAvgPrice\":\"1554920.18796992\","
                + "\"ordersAvgPrice\":\"1292759.93984962\",\"ordersMaxPrice\":\"1400000.00000000\","
                + "\"ahEntryCount\":\"132\",\"ordersEntryCount\":\"18\"},"
                + "{\"period\":\"24h\",\"periodMinutes\":1440,\"ahAvgPrice\":\"1555195.27\","
                + "\"ordersAvgPrice\":\"1343449.51\",\"ordersMaxPrice\":\"1492762.00000000\","
                + "\"ahEntryCount\":\"1088\",\"ordersEntryCount\":\"150\"}]}";
        FwmParser.Stats st = FwmParser.parse(fwmJson);
        eq(1400000L, st.orderMax().get().price(), "fwm order max 1h");
        eq("1 Std", st.orderMax().get().period(), "fwm zeitraum");
        eq(1292760L, st.orderAvg().get().price(), "fwm order avg gerundet");
        eq(1554920L, st.ahAvg().get().price(), "fwm ah avg");
        // 1h ohne Orders -> 24h wird genommen, AH bleibt bei 1h
        String fwmJson2 = fwmJson.replace("\"ordersEntryCount\":\"18\"", "\"ordersEntryCount\":\"0\"");
        FwmParser.Stats st2 = FwmParser.parse(fwmJson2);
        eq(1492762L, st2.orderMax().get().price(), "fwm fallback 24h");
        eq("24 Std", st2.orderMax().get().period(), "fwm fallback zeitraum");
        eq("1 Std", st2.ahAvg().get().period(), "fwm ah bleibt 1h");
        eq(true, FwmParser.parse("{\"ok\":false}").isEmpty(), "fwm ok false");
        eq(true, FwmParser.parse("{\"ok\":true,\"stats\":[]}").isEmpty(), "fwm leer");

        // Formatierung
        eq("$1.500.000", MoneyFormat.format(1500000, "$"), "format");
        eq("$750", MoneyFormat.format(750, "$"), "format klein");
        eq("$1.000", MoneyFormat.format(1000, "$"), "format tausend");
        eq("$0", MoneyFormat.format(0, "$"), "format null");
        eq("$950.000", MoneyFormat.compact(950000, "$"), "compact klein");
        eq("$1,4 Mio", MoneyFormat.compact(1400000, "$"), "compact mio");
        eq("$2 Mio", MoneyFormat.compact(2000000, "$"), "compact rund");
        eq("$448 Mio", MoneyFormat.compact(448000000, "$"), "compact gross");
        eq("$2,5 Mrd", MoneyFormat.compact(2500000000L, "$"), "compact mrd");

        System.out.println(passed + " Tests bestanden, " + failed + " fehlgeschlagen");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
