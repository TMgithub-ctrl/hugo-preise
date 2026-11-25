package de.hugoprices.logic;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Holt Preisstatistiken von friendswithmoney.net (mit Erlaubnis des Betreibers).
 * Rücksichtsvoll: ein Abruf nach dem anderen, mindestens 1 s Abstand, Zwischenspeicher (Standard 5 Min),
 * Pause nach Fehlern. lookup() blockiert nie.
 */
public final class FwmClient {
    private static final System.Logger LOG = System.getLogger("hugoprices");
    private static final String USER_AGENT = "CripzysHugoPreise/1.6 (Minecraft Fabric client mod)";
    private static final int MAX_PENDING = 6;
    private static final long MIN_GAP_MILLIS = 1000;
    private static final long ITEM_RETRY_MILLIS = 120_000;
    private static final long GLOBAL_PAUSE_MILLIS = 300_000;

    public record Result(FwmParser.Stats stats, long fetchedAtMillis) {
    }

    public record Lookup(Result result, boolean loading, String error) {
    }

    private static final class State {
        Result result;
        boolean inFlight;
        long retryAfterMillis;
        String lastError;
    }

    private final Settings settings;
    private final LongSupplier clock;
    private final HttpClient http;
    private final Map<String, State> states = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hugoprices-fwm");
        t.setDaemon(true);
        return t;
    });
    private int pending;
    private int consecutiveFailures;
    private long globalRetryAfterMillis;
    private String lastGlobalError = "unbekannt";
    private long lastRequestMillis;

    public FwmClient(Settings settings) {
        this(settings, System::currentTimeMillis);
    }

    public FwmClient(Settings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public synchronized Lookup lookup(String itemKey) {
        long now = clock.getAsLong();
        if (states.size() > 2000) {
            states.values().removeIf(s -> !s.inFlight);
        }
        State st = states.computeIfAbsent(itemKey, k -> new State());
        boolean fresh = st.result != null && now - st.result.fetchedAtMillis() <= settings.fwmCacheSeconds * 1000L;
        if (!fresh && !st.inFlight && now >= st.retryAfterMillis && now >= globalRetryAfterMillis
                && pending < MAX_PENDING) {
            st.inFlight = true;
            pending++;
            worker.execute(() -> run(itemKey));
        }
        String error = st.lastError;
        if (error == null && st.result == null && now < globalRetryAfterMillis) {
            error = "Pause nach Fehlern, letzter Grund: " + lastGlobalError;
        }
        return new Lookup(st.result, st.inFlight, error);
    }

    private void run(String itemKey) {
        Result result = null;
        Exception error = null;
        try {
            throttle();
            result = new Result(fetch(itemKey), clock.getAsLong());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = e;
        } catch (Exception e) {
            error = e;
        }
        synchronized (this) {
            State st = states.computeIfAbsent(itemKey, k -> new State());
            st.inFlight = false;
            pending--;
            long now = clock.getAsLong();
            if (result != null) {
                st.result = result;
                st.retryAfterMillis = 0;
                st.lastError = null;
                consecutiveFailures = 0;
            } else {
                st.lastError = MarketClient.describe(error);
                lastGlobalError = st.lastError;
                st.retryAfterMillis = now + ITEM_RETRY_MILLIS;
                if (++consecutiveFailures >= 3) {
                    globalRetryAfterMillis = now + GLOBAL_PAUSE_MILLIS;
                    consecutiveFailures = 0;
                }
                LOG.log(System.Logger.Level.WARNING, "FWM-Abruf fehlgeschlagen fuer " + itemKey + ": " + error);
            }
        }
    }

    private void throttle() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = clock.getAsLong();
            wait = lastRequestMillis + MIN_GAP_MILLIS - now;
            lastRequestMillis = Math.max(now, lastRequestMillis + MIN_GAP_MILLIS);
        }
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    private FwmParser.Stats fetch(String itemKey) throws IOException, InterruptedException {
        String url = settings.fwmBaseUrl + URLEncoder.encode(itemKey, StandardCharsets.UTF_8).replace("+", "%20")
                + "/stats";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            // Item gibt es dort nicht: kein Fehler, nur keine Daten.
            return new FwmParser.Stats(java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.empty());
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        String body = resp.body();
        if (body == null || body.isBlank() || body.trim().startsWith("<")) {
            throw new IOException("keine Daten erhalten");
        }
        return FwmParser.parse(body);
    }
}
