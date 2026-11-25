package de.hugoprices.logic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Liest die Antwort von friendswithmoney.net /api/marketplace/items/&lt;item&gt;/stats.
 * Pro Zeitraum (1h, 24h, 3d, 7d, 31d) gibt es u. a. ordersMaxPrice, ordersAvgPrice, ahAvgPrice und Eintragszahlen.
 * Genommen wird jeweils der kürzeste Zeitraum, in dem es Einträge gibt (Order und AH getrennt).
 */
public final class FwmParser {
    private FwmParser() {
    }

    /** Ein Preis aus einem bestimmten Zeitraum, z. B. "1 Std". */
    public record Value(long price, String period) {
    }

    public record Stats(Optional<Value> orderMax, Optional<Value> orderAvg, Optional<Value> ahAvg) {
        public boolean isEmpty() {
            return orderMax.isEmpty() && orderAvg.isEmpty() && ahAvg.isEmpty();
        }
    }

    private static final List<String> ORDER = List.of("1h", "24h", "3d", "7d");

    @SuppressWarnings("unchecked")
    public static Stats parse(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("Antwort ist kein Objekt");
        }
        Object ok = m.get("ok");
        if (Boolean.FALSE.equals(ok)) {
            return new Stats(Optional.empty(), Optional.empty(), Optional.empty());
        }
        Object statsObj = m.get("stats");
        if (!(statsObj instanceof List<?> list)) {
            return new Stats(Optional.empty(), Optional.empty(), Optional.empty());
        }
        Optional<Value> orderMax = Optional.empty();
        Optional<Value> orderAvg = Optional.empty();
        Optional<Value> ahAvg = Optional.empty();
        for (String wanted : ORDER) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> p) || !wanted.equals(p.get("period"))) {
                    continue;
                }
                Map<String, Object> period = (Map<String, Object>) p;
                String label = label(wanted);
                if (orderMax.isEmpty() && count(period.get("ordersEntryCount")) > 0) {
                    orderMax = money(period.get("ordersMaxPrice")).map(v -> new Value(v, label));
                    orderAvg = money(period.get("ordersAvgPrice")).map(v -> new Value(v, label));
                }
                if (ahAvg.isEmpty() && count(period.get("ahEntryCount")) > 0) {
                    ahAvg = money(period.get("ahAvgPrice")).map(v -> new Value(v, label));
                }
            }
        }
        return new Stats(orderMax, orderAvg, ahAvg);
    }

    static String label(String period) {
        return switch (period) {
            case "1h" -> "1 Std";
            case "24h" -> "24 Std";
            case "3d" -> "3 Tage";
            case "7d" -> "7 Tage";
            default -> period;
        };
    }

    static long count(Object v) {
        Optional<BigDecimal> d = decimal(v);
        return d.map(BigDecimal::longValue).orElse(0L);
    }

    static Optional<Long> money(Object v) {
        return decimal(v).filter(d -> d.signum() > 0).map(d -> d.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static Optional<BigDecimal> decimal(Object v) {
        if (v == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(v.toString().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
