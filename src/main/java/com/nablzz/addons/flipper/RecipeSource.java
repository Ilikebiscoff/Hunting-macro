package com.nablzz.addons.flipper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Gets the crafting recipes: from the cache in the config folder, and (first start, then once a week) by downloading the
 * NotEnoughUpdates item repo as a zip and reading the recipes out of it. A background thread does the download.
 */
public final class RecipeSource {
    private RecipeSource() {}

    private static final String ZIP = "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/refs/heads/master.zip";
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

    private static volatile String message = "starting ...";
    private static volatile long fetchedAt;
    private static volatile int version;
    private static volatile boolean busy;
    private static Path cache;

    public static String message() {
        return message;
    }

    public static long fetchedAt() {
        return fetchedAt;
    }

    public static int version() {
        return version;
    }

    public static boolean busy() {
        return busy;
    }

    public static synchronized void start(Path cacheFile) {
        if (cache != null) return;
        cache = cacheFile;
        try {
            if (Files.isRegularFile(cacheFile)) {
                fetchedAt = Recipes.fromJson(Files.readString(cacheFile));
                if (fetchedAt > 0) {
                    version++;
                    message = Recipes.count() + " recipes (saved copy)";
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // download instead
        }
        if (fetchedAt == 0 || System.currentTimeMillis() - fetchedAt > MAX_AGE_MS) refreshNow();
    }

    /** Downloads the recipes again (a few MB; takes some seconds). */
    public static synchronized void refreshNow() {
        if (busy) return;
        busy = true;
        message = "downloading recipes ...";
        Thread t = new Thread(RecipeSource::download, "nablz-recipes");
        t.setDaemon(true);
        t.start();
    }

    private static void download() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ZIP)).header("User-Agent", "nablz-addons").timeout(Duration.ofMinutes(5)).GET().build();
            HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            List<Recipe> all;
            try (InputStream in = r.body()) {
                all = Recipes.parseZip(in);
            }
            if (all.size() < 100) throw new IOException("only " + all.size() + " recipes found in the download");
            Recipes.set(all);
            fetchedAt = System.currentTimeMillis();
            version++;
            message = all.size() + " recipes (downloaded)";
            if (cache != null) Files.writeString(cache, Recipes.toJson(all, fetchedAt));
        } catch (Exception e) {
            String m = e.getMessage();
            message = "recipe download failed (" + (m == null ? e.getClass().getSimpleName() : m) + ")" + (Recipes.count() > 0 ? " - using the saved copy" : "");
        } finally {
            busy = false;
        }
    }
}
