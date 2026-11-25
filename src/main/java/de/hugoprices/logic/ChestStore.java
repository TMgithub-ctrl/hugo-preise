package de.hugoprices.logic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merkt sich den Inhalt geöffneter Kisten: pro Kiste (Server|Dimension|x,y,z) die Anzahl je Item.
 * Wird in einer Textdatei gespeichert. Ohne Minecraft-Klassen, damit es testbar ist.
 * Dateiformat pro Zeile: schluessel<TAB>gesehen-ms<TAB>item=anzahl;item=anzahl
 */
public final class ChestStore {
    public record Chest(long seenAtMillis, Map<String, Integer> items) {
    }

    private final Map<String, Chest> chests = new LinkedHashMap<>();
    private boolean dirty;

    public synchronized void put(String key, Map<String, Integer> items, long nowMillis) {
        Chest old = chests.get(key);
        if (old != null && old.items().equals(items)) {
            return; // nichts geändert
        }
        chests.put(key, new Chest(nowMillis, new TreeMap<>(items)));
        dirty = true;
    }

    public synchronized void remove(String key) {
        if (chests.remove(key) != null) {
            dirty = true;
        }
    }

    /** Löscht alle Kisten, deren Schlüssel so beginnt (z. B. alle eines Servers). */
    public synchronized int clearPrefix(String prefix) {
        int before = chests.size();
        chests.keySet().removeIf(k -> k.startsWith(prefix));
        if (chests.size() != before) {
            dirty = true;
        }
        return before - chests.size();
    }

    /** Summe je Item über alle Kisten mit diesem Schlüssel-Anfang. */
    public synchronized Map<String, Integer> totals(String prefix) {
        Map<String, Integer> sum = new HashMap<>();
        for (Map.Entry<String, Chest> e : chests.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            e.getValue().items().forEach((item, n) -> sum.merge(item, n, Integer::sum));
        }
        return sum;
    }

    /** In wie vielen Kisten (mit diesem Anfang) ein Item liegt. */
    public synchronized Map<String, Integer> chestCounts(String prefix) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Chest> e : chests.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                e.getValue().items().keySet().forEach(item -> out.merge(item, 1, Integer::sum));
            }
        }
        return out;
    }

    public synchronized int count(String prefix) {
        int n = 0;
        for (String k : chests.keySet()) {
            if (k.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    public synchronized boolean has(String key) {
        return chests.containsKey(key);
    }

    public synchronized List<String> toLines() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Chest> e : chests.entrySet()) {
            StringBuilder sb = new StringBuilder(e.getKey()).append('\t').append(e.getValue().seenAtMillis())
                    .append('\t');
            boolean first = true;
            for (Map.Entry<String, Integer> it : e.getValue().items().entrySet()) {
                if (!first) {
                    sb.append(';');
                }
                sb.append(it.getKey()).append('=').append(it.getValue());
                first = false;
            }
            out.add(sb.toString());
        }
        return out;
    }

    public synchronized void loadLines(List<String> lines) {
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p.length != 3) {
                continue;
            }
            try {
                long seen = Long.parseLong(p[1].trim());
                Map<String, Integer> items = new TreeMap<>();
                if (!p[2].isEmpty()) {
                    for (String part : p[2].split(";")) {
                        int eq = part.lastIndexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        items.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1).trim()));
                    }
                }
                chests.put(p[0], new Chest(seen, items));
            } catch (NumberFormatException e) {
                // kaputte Zeile überspringen
            }
        }
        dirty = false;
    }

    public void load(Path file) {
        try {
            if (Files.exists(file)) {
                loadLines(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            // leer starten
        }
    }

    public void saveIfDirty(Path file) {
        List<String> lines;
        synchronized (this) {
            if (!dirty) {
                return;
            }
            lines = toLines();
            dirty = false;
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                dirty = true;
            }
        }
    }
}
