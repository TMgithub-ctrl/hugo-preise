package de.hugoprices.logic;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versionsnummern aus Dateinamen lesen und vergleichen ("cripzys-hugo-preise-1.13.0.jar" -> 1.13.0). */
public final class VersionUtil {
    private static final Pattern FILE = Pattern.compile("cripzys-hugo-preise-(\\d+(?:\\.\\d+)*)\\.jar");

    private VersionUtil() {
    }

    public static Optional<String> fromFileName(String name) {
        Matcher m = FILE.matcher(name);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** &gt; 0 wenn a neuer ist als b. Nicht-Zahlen werden ignoriert ("1.13.0-beta" = 1.13.0). */
    public static int compare(String a, String b) {
        String[] pa = a.split("[^0-9]+");
        String[] pb = b.split("[^0-9]+");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long x = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0;
            long y = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }
}
