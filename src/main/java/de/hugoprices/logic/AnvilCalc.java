package de.hugoprices.logic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Berechnet, was es im Amboss kostet, einzelne Verzauberungsbücher auf ein neues Werkzeug zu bringen.
 * Sucht die günstigste Reihenfolge (Bücher vorher zusammenlegen oder einzeln aufs Werkzeug) über alle
 * Aufteilungen. Kosten in XP-Punkten (jeder Schritt von Level 0 aus gezahlt). Ein Schritt ab 40 Leveln
 * geht im Überlebensmodus nicht ("Zu teuer!").
 */
public final class AnvilCalc {
    private AnvilCalc() {
    }

    public record Enchant(String id, int level) {
    }

    public record Result(boolean possible, long xpPoints, int maxStepLevels, int steps) {
    }

    /** Buch-Multiplikator je Verzauberung (Minecraft-Wiki "Anvil mechanics", Buch als Opfer). */
    private static final Map<String, Integer> BOOK_MULT = new HashMap<>();

    static {
        for (String s : new String[] {"protection", "fire_protection", "feather_falling", "projectile_protection",
                "sharpness", "smite", "bane_of_arthropods", "knockback", "efficiency", "unbreaking", "power",
                "loyalty", "piercing", "quick_charge", "density"}) {
            BOOK_MULT.put(s, 1);
        }
        for (String s : new String[] {"blast_protection", "respiration", "depth_strider", "aqua_affinity",
                "fire_aspect", "looting", "fortune", "punch", "flame", "luck_of_the_sea", "lure", "frost_walker",
                "mending", "impaling", "riptide", "multishot", "sweeping_edge", "breach", "wind_burst"}) {
            BOOK_MULT.put(s, 2);
        }
        for (String s : new String[] {"thorns", "silk_touch", "infinity", "binding_curse", "vanishing_curse",
                "channeling", "soul_speed", "swift_sneak"}) {
            BOOK_MULT.put(s, 4);
        }
    }

    static int multiplier(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        return BOOK_MULT.getOrDefault(path, 2);
    }

    static int penalty(int work) {
        return (1 << work) - 1;
    }

    /** XP-Punkte, um von Level 0 auf Level n zu kommen. */
    public static long pointsForLevels(int n) {
        if (n <= 16) {
            return (long) n * n + 6L * n;
        }
        if (n <= 31) {
            return Math.round(2.5 * n * n - 40.5 * n + 360);
        }
        return Math.round(4.5 * n * n - 162.5 * n + 2220);
    }

    private record State(long points, int maxLevels, int steps) {
    }

    /** Bester Weg, alle Bücher auf ein neues (unverzaubertes, nie bearbeitetes) Werkzeug zu bringen. */
    public static Result cost(List<Enchant> enchants) {
        int n = enchants.size();
        if (n == 0) {
            return new Result(true, 0, 0, 0);
        }
        if (n > 10) {
            n = 10; // mehr gibt es praktisch nicht; Rechenzeit begrenzen
        }
        int full = (1 << n) - 1;
        int[] value = new int[1 << n]; // Summe Multiplikator*Level der Bücher in der Teilmenge
        for (int m = 1; m <= full; m++) {
            int low = Integer.numberOfTrailingZeros(m);
            Enchant e = enchants.get(low);
            value[m] = value[m & (m - 1)] + multiplier(e.id()) * e.level();
        }
        int maxWork = n + 1;
        // book[m][w]: ein Buch mit genau den Verzauberungen m und Arbeitsstufe w
        State[][] book = new State[1 << n][maxWork + 1];
        for (int i = 0; i < n; i++) {
            book[1 << i][0] = new State(0, 0, 0);
        }
        for (int m = 1; m <= full; m++) {
            if (Integer.bitCount(m) < 2) {
                continue;
            }
            // Ziel-Buch a + Opfer-Buch b
            for (int a = (m - 1) & m; a > 0; a = (a - 1) & m) {
                int b = m & ~a;
                for (int wa = 0; wa <= maxWork; wa++) {
                    State sa = book[a][wa];
                    if (sa == null) {
                        continue;
                    }
                    for (int wb = 0; wb <= maxWork; wb++) {
                        State sb = book[b][wb];
                        if (sb == null) {
                            continue;
                        }
                        int levels = value[b] + penalty(wa) + penalty(wb);
                        if (levels >= 40) {
                            continue;
                        }
                        int w = Math.min(maxWork, Math.max(wa, wb) + 1);
                        State s = new State(sa.points() + sb.points() + pointsForLevels(levels),
                                Math.max(levels, Math.max(sa.maxLevels(), sb.maxLevels())), sa.steps() + sb.steps() + 1);
                        if (book[m][w] == null || s.points() < book[m][w].points()) {
                            book[m][w] = s;
                        }
                    }
                }
            }
        }
        // tool[m][w]: Werkzeug, auf das schon die Bücher m gebracht wurden
        State[][] tool = new State[1 << n][maxWork + 1];
        tool[0][0] = new State(0, 0, 0);
        for (int m = 1; m <= full; m++) {
            for (int b = m; b > 0; b = (b - 1) & m) {
                int a = m & ~b;
                for (int wa = 0; wa <= maxWork; wa++) {
                    State ta = tool[a][wa];
                    if (ta == null) {
                        continue;
                    }
                    for (int wb = 0; wb <= maxWork; wb++) {
                        State sb = book[b][wb];
                        if (sb == null) {
                            continue;
                        }
                        int levels = value[b] + penalty(wa) + penalty(wb);
                        if (levels >= 40) {
                            continue;
                        }
                        int w = Math.min(maxWork, Math.max(wa, wb) + 1);
                        State s = new State(ta.points() + sb.points() + pointsForLevels(levels),
                                Math.max(levels, Math.max(ta.maxLevels(), sb.maxLevels())), ta.steps() + sb.steps() + 1);
                        if (tool[m][w] == null || s.points() < tool[m][w].points()) {
                            tool[m][w] = s;
                        }
                    }
                }
            }
        }
        State best = null;
        for (State s : tool[full]) {
            if (s != null && (best == null || s.points() < best.points())) {
                best = s;
            }
        }
        if (best == null) {
            return new Result(false, 0, 40, 0);
        }
        return new Result(true, best.points(), best.maxLevels(), best.steps());
    }
}
