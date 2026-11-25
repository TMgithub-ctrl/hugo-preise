package de.hugoprices.logic;

/** Formatierung von Preisen und Zeiten für den Tooltip. */
public final class MoneyFormat {
    private MoneyFormat() {
    }

    /** 1500000 -> "$1.500.000" */
    public static String format(long value, String symbol) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder sb = new StringBuilder();
        int first = digits.length() % 3;
        if (first > 0) {
            sb.append(digits, 0, first);
        }
        for (int i = first; i < digits.length(); i += 3) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(digits, i, i + 3);
        }
        return (value < 0 ? "-" : "") + symbol + sb;
    }

    /** Kurzform für große Beträge: 950000 -> "$950.000", 1400000 -> "$1,4 Mio", 2500000000 -> "$2,5 Mrd". */
    public static String compact(long value, String symbol) {
        long abs = Math.abs(value);
        if (abs < 1_000_000L) {
            return format(value, symbol);
        }
        double v;
        String unit;
        if (abs >= 1_000_000_000L) {
            v = value / 1_000_000_000d;
            unit = " Mrd";
        } else {
            v = value / 1_000_000d;
            unit = " Mio";
        }
        String num = Math.abs(v) >= 100 ? String.format(java.util.Locale.GERMANY, "%.0f", v)
                : String.format(java.util.Locale.GERMANY, "%.1f", v);
        if (num.endsWith(",0")) {
            num = num.substring(0, num.length() - 2);
        }
        return (value < 0 ? "-" : "") + symbol + num.replace("-", "") + unit;
    }

    public static String age(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        if (minutes < 1) {
            return "gerade eben";
        }
        if (minutes < 60) {
            return "vor " + minutes + " Min";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "vor " + hours + " Std";
        }
        return "vor " + (hours / 24) + " Tg";
    }
}
