package de.hugoprices;

import de.hugoprices.logic.MiniJson;
import de.hugoprices.logic.VersionUtil;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Prüft beim Start, ob auf GitHub eine neuere Version liegt, und fragt im Hauptmenü, ob sie installiert werden soll.
 * Mods lassen sich nicht im laufenden Spiel austauschen: die neue Datei wird abgelegt und ist nach dem Neustart aktiv.
 * Die neue Version deaktiviert beim Start alle älteren Dateien dieser Mod (Endung .disabled).
 */
final class Updater {
    private static final String LIST_URL = "https://api.github.com/repos/TMgithub-ctrl/hugo-preise/contents/download";
    private static final String PREFIX = "cripzys-hugo-preise-";

    private record Remote(String version, String fileName, String url) {
    }

    private static volatile Remote available;
    private static volatile boolean asked;
    private static volatile String status;

    private Updater() {
    }

    static String currentVersion() {
        return FabricLoader.getInstance().getModContainer("hugoprices")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    private static Path modsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    /** Beim Start: ältere Dateien dieser Mod deaktivieren (die laufende Version ist die neueste). */
    static void cleanupOldVersions() {
        String mine = currentVersion();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir(), PREFIX + "*.jar")) {
            for (Path p : ds) {
                Optional<String> v = VersionUtil.fromFileName(p.getFileName().toString());
                if (v.isPresent() && VersionUtil.compare(v.get(), mine) < 0) {
                    try {
                        Files.move(p, p.resolveSibling(p.getFileName() + ".disabled"),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        // Datei gesperrt: beim nächsten Start nochmal
                    }
                }
            }
        } catch (Exception ignored) {
            // kein mods-Ordner o. Ä.
        }
    }

    /** Im Hintergrund nach einer neuen Version schauen. */
    static void checkAsync() {
        Thread t = new Thread(() -> {
            try {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(LIST_URL)).timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "CripzysHugoPreise-Updater").header("Accept", "application/json")
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    return;
                }
                Object root = MiniJson.parse(resp.body());
                if (!(root instanceof List<?> list)) {
                    return;
                }
                Remote best = null;
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    String name = String.valueOf(m.get("name"));
                    Object url = m.get("download_url");
                    Optional<String> v = VersionUtil.fromFileName(name);
                    if (v.isPresent() && url != null
                            && (best == null || VersionUtil.compare(v.get(), best.version()) > 0)) {
                        best = new Remote(v.get(), name, url.toString());
                    }
                }
                if (best != null && VersionUtil.compare(best.version(), currentVersion()) > 0) {
                    available = best;
                }
            } catch (Exception ignored) {
                // offline o. Ä.: einfach kein Update anbieten
            }
        }, "hugoprices-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Jeden Tick: im Hauptmenü einmalig nachfragen. */
    static void tick(Minecraft mc) {
        Remote r = available;
        Screen screen = mc.gui.screen();
        if (r == null || asked || !(screen instanceof TitleScreen title)) {
            return;
        }
        asked = true;
        mc.gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                download(r);
            }
            mc.gui.setScreen(title);
        }, Component.literal("Hugo Preise: Update auf " + r.version() + " verfuegbar"),
                Component.literal("Du hast " + currentVersion() + ". Jetzt herunterladen? Aktiv nach dem naechsten Neustart.")));
    }

    private static void download(Remote r) {
        Thread t = new Thread(() -> {
            try {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(r.url())).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "CripzysHugoPreise-Updater").GET().build();
                Path target = modsDir().resolve(r.fileName());
                Path tmp = modsDir().resolve(r.fileName() + ".download");
                HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
                if (resp.statusCode() != 200 || Files.size(tmp) < 10_000) {
                    Files.deleteIfExists(tmp);
                    status = "Update-Download fehlgeschlagen (HTTP " + resp.statusCode() + ")";
                    return;
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                status = "Update " + r.version() + " heruntergeladen. Minecraft neu starten, dann ist es aktiv.";
            } catch (Exception e) {
                status = "Update-Download fehlgeschlagen: " + e.getMessage();
            }
        }, "hugoprices-update-download");
        t.setDaemon(true);
        t.start();
    }

    /** Ergebnis des Downloads einmal im Chat/Hotbar zeigen, sobald man im Spiel ist. */
    static void showStatus(Minecraft mc) {
        String s = status;
        if (s != null && mc.player != null) {
            status = null;
            SellAutomation.msg(Component.literal(s));
        }
    }

    static String pendingStatus() {
        return status;
    }
}
