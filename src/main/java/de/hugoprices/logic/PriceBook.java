package de.hugoprices.logic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merkt sich pro Item den günstigsten AH-Preis und den höchsten Order-Preis, den du im Spiel gesehen hast.
 * Innerhalb eines geöffneten Menüs (eine "Sitzung") zählt der beste Wert über alle Seiten.
 * Öffnest du das Menü später erneut, ersetzt der neue Wert den alten, damit veraltete Preise verschwinden.
 * Die Werte werden in einer Datei gespeichert und überleben einen Neustart.
 */
public final class PriceBook {
    public record Entry(long price, long seenAtMillis, long session) {
        public Entry(long price, long seenAtMillis) {
            this(price, seenAtMillis, 0);
        }
    }

    private final Map<String, Entry> ah = new HashMap<>();
    private final Map<String, Entry> order = new HashMap<>();
    private boolean dirty;

    private Map<String, Entry> map(Mode mode) {
        return mode == Mode.AH ? ah : order;
    }

    /** Alte Variante ohne Sitzung: behält immer den besseren Wert (bis er abläuft). */
    public synchronized void observe(Mode mode, String key, long price, long nowMillis, long maxAgeMillis) {
        observe(mode, key, price, nowMillis, maxAgeMillis, 0);
    }

    public synchronized void observe(Mode mode, String key, long price, long nowMillis, long maxAgeMillis,
            long session) {
        Map<String, Entry> m = map(mode);
        Entry cur = m.get(key);
        boolean expired = cur != null && nowMillis - cur.seenAtMillis() > maxAgeMillis;
        boolean newSession = cur != null && session != 0 && cur.session() != session;
        boolean better = cur == null || expired || newSession
                || (mode == Mode.AH ? price < cur.price() : price > cur.price());
        if (better || price == cur.price()) {
            m.put(key, new Entry(price, nowMillis, session));
            dirty = true;
        }
    }

    public synchronized Optional<Entry> get(Mode mode, String key, long nowMillis, long maxAgeMillis) {
        Entry e = map(mode).get(key);
        if (e == null || nowMillis - e.seenAtMillis() > maxAgeMillis) {
            return Optional.empty();
        }
        return Optional.of(e);
    }

    public synchronized void clear() {
        ah.clear();
        order.clear();
        dirty = true;
    }

    public synchronized int size() {
        return ah.size() + order.size();
    }

    // ---------- Speichern / Laden ----------
    // Format pro Zeile: MODUS<TAB>schluessel<TAB>preis<TAB>gesehen-um-ms

    public synchronized List<String> toLines() {
        List<String> out = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (Map.Entry<String, Entry> e : map(mode).entrySet()) {
                out.add(mode.name() + "\t" + e.getKey() + "\t" + e.getValue().price() + "\t"
                        + e.getValue().seenAtMillis());
            }
        }
        out.sort(null);
        return out;
    }

    public synchronized void loadLines(List<String> lines, long nowMillis, long maxAgeMillis) {
        for (String line : lines) {
            String[] p = line.split("\t");
            if (p.length != 4) {
                continue;
            }
            try {
                Mode mode = Mode.valueOf(p[0].trim());
                long price = Long.parseLong(p[2].trim());
                long seen = Long.parseLong(p[3].trim());
                if (nowMillis - seen > maxAgeMillis) {
                    continue; // zu alt, gar nicht erst laden
                }
                map(mode).put(p[1], new Entry(price, seen, 0));
            } catch (IllegalArgumentException e) {
                // kaputte Zeile überspringen
            }
        }
        dirty = false;
    }

    public void load(Path file, long nowMillis, long maxAgeMillis) {
        try {
            if (Files.exists(file)) {
                loadLines(Files.readAllLines(file, StandardCharsets.UTF_8), nowMillis, maxAgeMillis);
            }
        } catch (IOException | RuntimeException e) {
            // Datei kaputt: einfach leer starten
        }
    }

    /** Speichert nur, wenn sich seit dem letzten Speichern etwas geändert hat. */
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
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                dirty = true; // beim nächsten Mal erneut versuchen
            }
        }
    }
}
