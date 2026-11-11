package com.cartify.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * Turns Spotify metadata into a local MP3 using the bundled yt-dlp + ffmpeg.
 *
 * Spotify itself streams DRM protected audio, so the audio is resolved from a
 * public provider through yt-dlp (the downloader framework), converted to MP3 by
 * the bundled ffmpeg, tagged with the Spotify metadata and copied into
 * Music/Cartify through MediaStore.
 */
public final class DownloadEngine {

    private static final String TAG = "Cartify";
    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);
    /** Resolved video urls, so retries and repeat downloads skip the search round trip. */
    /**
     * Search result per query, so a retry or a repeat download skips the lookup.
     *
     * Bounded: this used to grow for the lifetime of the process, which on a device that
     * downloads a lot (or is shared between listeners) is a slow leak. Oldest entries are
     * dropped once the cap is reached; a dropped entry only costs one more search.
     */
    private static final int PICK_CACHE_MAX = 400;
    private static final Map<String, String> PICKED =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                            return size() > PICK_CACHE_MAX;
                        }
                    });
    private static final String PREFS = "cartify_prefs";
    private static final String KEY_UPDATE_AT = "ytdlp_updated_at";
    private static final long UPDATE_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    /** Receives progress; returning {@code true} asks the engine to stop. */
    public interface Listener {
        boolean onProgress(int percent, String detail);
    }

    /**
     * One cancellable download job. {@link #cancel()} kills the yt-dlp process owned
     * by this job, so cancelling actually stops the transfer instead of letting it
     * finish in the background.
     */
    public static final class Task {
        private volatile boolean cancelled;
        private volatile String processId = "";

        public void cancel() {
            cancelled = true;
            destroy(processId);
        }

        public boolean isCancelled() {
            return cancelled;
        }

        void bind(String id) {
            processId = id == null ? "" : id;
            if (cancelled) destroy(processId);
        }

        void check() throws Failure {
            if (cancelled) throw new Failure("cancelled", "Cancelled");
        }

        private static void destroy(String id) {
            if (id == null || id.isEmpty()) return;
            try {
                YoutubeDL.getInstance().destroyProcessById(id);
            } catch (Throwable e) {
                Log.w(TAG, "could not stop " + id, e);
            }
        }
    }

    public static class Failure extends Exception {
        public final String stage;
        final boolean staleExtractor;
        /** The video resolved, but the chosen client offered no usable stream. */
        final boolean formatMiss;

        Failure(String stage, String message) {
            this(stage, message, false, false);
        }

        Failure(String stage, String message, boolean staleExtractor) {
            this(stage, message, staleExtractor, false);
        }

        Failure(String stage, String message, boolean staleExtractor, boolean formatMiss) {
            super(message);
            this.stage = stage;
            this.staleExtractor = staleExtractor;
            this.formatMiss = formatMiss;
        }
    }

    private DownloadEngine() {
    }

    public static synchronized void ensureInitialised(Context context) throws Exception {
        if (INITIALISED.get()) return;
        try {
            YoutubeDL.getInstance().init(context.getApplicationContext());
        } catch (Throwable e) {
            throw new Exception("yt-dlp failed to start: " + describe(e));
        }
        try {
            FFmpeg.getInstance().init(context.getApplicationContext());
        } catch (Throwable e) {
            throw new Exception("ffmpeg failed to start: " + describe(e));
        }
        INITIALISED.set(true);
        // The extractor refresh is a network round trip and must not sit in front of the
        // first download: it runs on a background thread and only forces an update when
        // its cached copy is actually stale.
        final Context app = context.getApplicationContext();
        new Thread(() -> refreshExtractor(app, false), "cartify-ytdlp-update").start();
    }

    /**
     * YouTube changes its player constantly, so the yt-dlp shipped inside the
     * library goes stale and downloads start failing with HTTP 403. Refreshing
     * the extractor from its own release feed keeps Cartify working without an
     * app update.
     */
    private static void refreshExtractor(Context context, boolean force) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_UPDATE_AT, 0L);
        if (!force && System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return;
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE.INSTANCE);
            prefs.edit().putLong(KEY_UPDATE_AT, System.currentTimeMillis()).apply();
            Log.d(TAG, "yt-dlp refreshed to " + YoutubeDL.getInstance().versionName(context));
        } catch (Throwable e) {
            Log.w(TAG, "yt-dlp refresh failed: " + describe(e));
        }
    }

    public static String download(Context context, Track track, Listener listener) throws Exception {
        return download(context, track, listener, 0, new Task());
    }

    /**
     * Resolves the upload for the first few tracks before the user taps Download.
     *
     * The search is the slowest step of a download and it only depends on metadata, so
     * doing it while the user is still reading the track list means the transfer itself
     * starts immediately on tap.
     */
    public static void prewarm(Context context, java.util.List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        new Thread(() -> {
            try {
                ensureInitialised(context.getApplicationContext());
            } catch (Exception e) {
                return;
            }
            for (int i = 0; i < Math.min(4, tracks.size()); i++) {
                Track t = tracks.get(i);
                String query = t.searchQuery();
                if (query.trim().isEmpty() || PICKED.containsKey(query)) continue;
                try {
                    PICKED.put(query, pickCandidate(query, t, new Task()));
                } catch (Throwable ignored) {
                }
            }
        }, "cartify-prewarm").start();
    }

    /**
     * Bounded pool for background stream pre-warms.
     *
     * Four, not two: a resolution costs 15-30 s on a slow link, and with only two
     * threads the previous screen's pre-warms held both of them, so a fresh search
     * sat in the queue and the first tap still paid the full price. Four lets a new
     * list start warming while the old one is still finishing.
     */
    private static final java.util.concurrent.ExecutorService STREAM_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "cartify-prewarm-stream");
                t.setDaemon(true);
                return t;
            });

    /**
     * Bumped whenever a new collection is shown.
     *
     * A pre-warm that belongs to a list the user has already left is pure waste: it
     * holds a pool thread for half a minute and delays the rows that are actually on
     * screen. Tasks compare their generation against this and give up early.
     */
    private static final java.util.concurrent.atomic.AtomicInteger PREWARM_GEN =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** Abandons every in-flight pre-warm; the next collection starts a new generation. */
    public static void cancelPrewarms() {
        PREWARM_GEN.incrementAndGet();
    }

    /**
     * Resolves a row's stream URL before the user taps play, so the tap lands on a
     * ready address instead of a 2-5 s spinner.
     *
     * The result is written back onto the Track ({@link Track#streamUrl}), so a later
     * {@code playQueue()} of the same objects opens it immediately. Fails silently: an
     * unresolvable track simply resolves again, visibly, when play is actually pressed.
     */
    public static void prewarmStream(final Context context, final Track track) {
        if (track == null) return;
        // Plays from disk, or is already resolved, or a resolution is already running.
        if (track.localUri != null && !track.localUri.isEmpty()) return;
        if (track.streamUrl != null && !track.streamUrl.isEmpty()) return;
        if (track.prefetching) return;
        track.prefetching = true;
        final int gen = PREWARM_GEN.get();
        STREAM_POOL.execute(() -> {
            try {
                // The list this row belonged to is gone; do not spend a thread on it.
                if (gen != PREWARM_GEN.get()) return;
                String url = resolveStream(context.getApplicationContext(), track, null);
                if (url != null && !url.isEmpty() && track.streamUrl.isEmpty()) {
                    track.streamUrl = url;
                    // Resolving only produces an address; the first byte still costs a
                    // DNS lookup, a TLS handshake and a TCP ramp-up. Doing that here,
                    // while the user is still reading the list, is what turns the tap
                    // into a near-instant start on a slow mobile link.
                    warmConnection(url);
                }
            } catch (Throwable ignored) {
                // On-demand resolution will try again, visibly, when play is pressed.
            } finally {
                track.prefetching = false;
            }
        });
    }

    /**
     * Primes DNS, TLS and the TCP window for a resolved stream url by asking for the
     * first few kilobytes and throwing them away.
     *
     * MediaPlayer opens its own connection, so this cannot be shared with it; what it
     * buys is a warm resolver cache and an established route, which is most of the
     * delay on a high-latency mobile network. Best effort: any failure is ignored and
     * the real playback request simply pays the cost it would have paid anyway.
     */
    private static void warmConnection(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Range", "bytes=0-16383");
            c.setRequestProperty("User-Agent", "com.cartify.app");
            c.setUseCaches(false);
            c.getResponseCode();
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            while (total < 16384) {
                int n = in.read(buf);
                if (n < 0) break;
                total += n;
            }
            in.close();
        } catch (Throwable ignored) {
            // A cold connection is not an error; playback will just open it itself.
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String download(Context context, Track track, Listener listener, int slot) throws Exception {
        return download(context, track, listener, slot, new Task());
    }

    /**
     * Downloads one track into a per-slot work folder.
     *
     * Parallel downloads share the process, so each worker needs its own directory;
     * using one folder would have two yt-dlp runs writing over each other's files.
     */
    public static String download(Context context, Track track, Listener listener, int slot, Task task)
            throws Exception {
        final Task job = task == null ? new Task() : task;
        ensureInitialised(context);
        job.check();
        File work = new File(context.getCacheDir(), "cartify-work-" + slot);
        if (!work.exists() && !work.mkdirs()) throw new Failure("setup", "Cannot create work folder");
        try {
            return downloadInto(context, track, listener, job, work);
        } finally {
            // A cancelled or failed transfer used to leave .part fragments behind in the
            // slot folder; the next run cleared them, but they sat on disk until then.
            clear(work);
        }
    }

    // ------------------------------------------------------------------ streaming

    /**
     * Resolves a direct audio URL for real-time playback, without downloading.
     *
     * The download path exists to produce an MP3 that outlives the app; this one exists so a
     * search result can be heard seconds after the tap. Both go through the same candidate
     * picker, so a stream and a later download of the same row are the same upload.
     *
     * Runs on a worker thread and returns the media URL yt-dlp prints with -g. MediaPlayer
     * plays that URL directly, which is why nothing is written to disk here.
     */
    public static String resolveStream(Context context, Track track, Task task) throws Exception {
        final Task job = task == null ? new Task() : task;
        ensureInitialised(context);
        job.check();
        String query = track.searchQuery();
        if (query.trim().isEmpty()) throw new Failure("search", "Track has no title");
        final long tResolveStart = android.os.SystemClock.elapsedRealtime();

        String picked = PICKED.get(query);
        if (picked == null) {
            picked = pickCandidate(query, track, job);
            PICKED.put(query, picked);
        }
        job.check();
        final long pickMs = android.os.SystemClock.elapsedRealtime() - tResolveStart;

        // A resolved CDN url is valid for hours, so re-playing a song, skipping back or
        // letting a queued track come round again must not run a second extraction. The
        // tap then goes straight to MediaPlayer instead of waiting 3-10 s on yt-dlp.
        String cached = streamCacheGet(picked);
        if (cached != null) {
            Log.i(TAG, "stream cache hit for \"" + track.title + "\" (pick=" + pickMs + "ms)");
            return cached;
        }

        Failure last = null;
        for (int attempt = 0; attempt < STREAM_CHAINS.length; attempt++) {
            String chain = STREAM_CHAINS[attempt];
            YoutubeDLRequest request = new YoutubeDLRequest(picked);
            // m4a first: it is the one container every MediaPlayer handles. A webm/opus
            // stream resolves just as well but is silent on older devices, which reads as
            // "streaming is broken" rather than "this device has no Opus decoder".
            request.addOption("-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio/best");
            request.addOption("-S", "abr,ext:m4a");
            request.addOption("--no-playlist");
            // The watch page is a large HTML document that yt-dlp parses only to find
            // the player config; the innertube clients used here get that from the API
            // directly, so on the first attempt the page is skipped. That removes two
            // round trips and a JS parse from the critical path. It is attempt 0 only:
            // if a client does need the page, the next chain runs without the skip and
            // still resolves, so the saving can never turn into a failure.
            String extractorArgs = "youtube:player_client=" + chain
                    + (attempt == 0 ? ";player_skip=webpage" : "");
            request.addOption("--extractor-args", extractorArgs);
            request.addOption("--no-warnings");
            // -g prints the media URL and stops; no transfer happens.
            request.addOption("-g");
            // Streaming is a speed game: the chain loop below is the real retry (it
            // switches client rather than repeating the same one), so an in-chain retry
            // only adds wall time. One is kept for a truly transient socket drop.
            request.addOption("--retries", "1");
            request.addOption("--socket-timeout", String.valueOf(streamSocketTimeout(context)));

            String id = "cartify-stream-" + System.nanoTime() + "-" + attempt;
            job.bind(id);
            YoutubeDLResponse response;
            try {
                response = YoutubeDL.getInstance().execute(request, id, null);
            } catch (Throwable e) {
                job.check();
                last = new Failure("stream", describe(e));
                continue;
            }
            job.bind("");
            job.check();
            String url = firstHttpUrl(response == null ? null : response.getOut());
            if (url != null) {
                streamCachePut(picked, url);
                long totalMs = android.os.SystemClock.elapsedRealtime() - tResolveStart;
                lastResolveMs = totalMs;
                Log.i(TAG, "stream resolved \"" + track.title + "\" in " + totalMs
                        + " ms (pick=" + pickMs + "ms attempt=" + attempt
                        + " chain=" + chain + ")");
                return url;
            }
            String err = response == null ? "" : errorLine(response.getErr());
            last = new Failure("stream", err.isEmpty() ? "No stream URL" : err);
        }
        throw last == null ? new Failure("stream", "Could not resolve a stream") : last;
    }

    /** Resolved CDN urls, keyed by the picked upload. */
    private static final long STREAM_TTL_MS = 45L * 60L * 1000L;
    private static final Map<String, String[]> STREAMS =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String[]>(32, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String[]> e) {
                            return size() > 24;
                        }
                    });

    /**
     * A previously resolved media url for this upload, or null.
     *
     * A YouTube media url stays valid for hours, so re-playing a song, skipping back or
     * letting a queued track come round again must not run a second extraction. The TTL
     * is well inside the real lifetime: an address that does expire is handled by the
     * player's existing one-shot re-resolve, so guessing long risks one retry while
     * guessing short throws the speed-up away.
     */
    private static String streamCacheGet(String picked) {
        String[] hit = STREAMS.get(picked);
        if (hit == null) return null;
        long age = System.currentTimeMillis() - Long.parseLong(hit[1]);
        if (age < 0 || age > STREAM_TTL_MS) {
            STREAMS.remove(picked);
            return null;
        }
        return hit[0];
    }

    private static void streamCachePut(String picked, String url) {
        if (picked == null || url == null || url.isEmpty()) return;
        STREAMS.put(picked, new String[]{url, String.valueOf(System.currentTimeMillis())});
    }

    /** The first http(s) line of yt-dlp's output; -g prints one URL per line. */
    private static String firstHttpUrl(String out) {
        if (out == null) return null;
        for (String line : out.split("\r?\n")) {
            String s = line.trim();
            if (s.startsWith("http://") || s.startsWith("https://")) return s;
        }
        return null;
    }

    /**
     * How long one client-chain resolution may wait before it is abandoned.
     *
     * Resolution latency is dominated by YouTube's answer, not the link, but the link
     * decides how long a stalled answer is worth waiting for: on a fast connection a
     * hung chain should be dropped quickly so the next client can be tried, while on a
     * slow one the same silence may just be the pipe crawling and deserves more time.
     */
    private static int streamSocketTimeout(Context context) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return 20;
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return 25;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return 25;
            int kbps = caps.getLinkDownstreamBandwidthKbps();
            if (kbps <= 0) return 20;
            if (kbps >= 8000) return 10;   // fast link: fail fast, next chain is one call away
            if (kbps >= 2000) return 15;   // typical 4G
            if (kbps >= 500) return 25;    // slow
            return 30;                     // 2G / poor signal
        } catch (Throwable e) {
            return 20;
        }
    }

    /**
     * How long the most recent successful resolution took, in milliseconds.
     *
     * The link's advertised bandwidth is a poor guide here: WiFi reports its PHY rate
     * (tens of Mbps) while the real throughput on a crowded 2.4 GHz network can be a
     * tenth of that, and the cost of a resolution is dominated by yt-dlp's own work
     * rather than by the bytes it moves. What actually predicts the next wait is how
     * long the last one took, so that is what the depth is based on.
     */
    private static volatile long lastResolveMs = 0;

    /**
     * How many rows of a fresh list to resolve ahead of the first tap.
     *
     * Each warm row is a separate yt-dlp process, and on a phone several of them at
     * once contend for the same CPU, so warming too many makes every one of them
     * slower — the opposite of the intent. The depth therefore follows the measured
     * cost: when resolutions are quick, four rows are nearly free and the third and
     * fourth tap become instant too; when they are slow, one row is warmed and the
     * rest of the bandwidth is left to the song the user is already hearing.
     */
    public static int prewarmDepth(Context context) {
        long last = lastResolveMs;
        if (last <= 0) {
            // Nothing measured yet: start conservative and let the first result tune it.
            return 2;
        }
        if (last <= 4000) return 4;    // quick link: warm four rows
        if (last <= 8000) return 3;
        if (last <= 15000) return 2;
        return 1;                      // slow: one row, keep the CPU for playback
    }

    private static String downloadInto(Context context, Track track, Listener listener,
                                       Task job, File work) throws Exception {
        final long tTrackStart = android.os.SystemClock.elapsedRealtime();
        String query = track.searchQuery();
        if (query.trim().isEmpty()) throw new Failure("search", "Track has no title");
        report(listener, 2, "Searching");

        File mp3 = null;
        Failure lastFailure = null;
        // The bar must never travel backwards. Each attempt starts above the best value
        // the previous one reached, so a retry reads as "still working", not as a reset.
        int[] highWater = {6};
        // Four attempts, not three: on a poor link the first tries are what discover that
        // the connection is poor, and the last one runs with a single fragment and long
        // timeouts. Failing a track that would have finished on attempt four is worse than
        // spending one more round on it.
        // Started once, before the retry loop, so a retry does not restart the fetch and the
        // cover is usually resolved by the time the transfer finishes. Measured without this:
        // 4.5 s and 8.8 s spent waiting on a decorative image after the song had arrived.
        final java.util.concurrent.Future<byte[]> artFuture = prefetchArt(track);
        for (int attempt = 0; attempt < 4 && mp3 == null; attempt++) {
            clear(work);
            try {
                mp3 = fetch(work, track, listener, attempt, job, highWater, context);
            } catch (Failure f) {
                if ("cancelled".equals(f.stage)) throw f;
                lastFailure = f;
                // Three things are worth another attempt: a stale extractor, a wrong-length
                // match, and a client that offered no usable stream for a video that
                // resolved fine. Each retry changes something real (refreshed extractor,
                // different candidate, different client chain) rather than repeating it.
                boolean retryable = f.staleExtractor || f.formatMiss || "verify".equals(f.stage);
                if (!retryable || attempt == 3) throw f;
                if (f.staleExtractor) {
                    report(listener, highWater[0], "Refreshing downloader");
                    refreshExtractor(context.getApplicationContext(), true);
                } else if (f.formatMiss) {
                    report(listener, highWater[0], "Trying another source");
                } else {
                    report(listener, highWater[0], "Trying another match");
                }
            }
        }
        if (mp3 == null) throw lastFailure == null ? new Failure("download", "Download failed") : lastFailure;

        job.check();
        final long afterFetch = android.os.SystemClock.elapsedRealtime();
        // The post-processing stages own the top of the bar: conversion ends at 92, tagging
        // and saving fill the rest. Every mark is lifted above the high-water value so no
        // stage can pull the number back down.
        highWater[0] = Math.max(highWater[0], 92);
        report(listener, highWater[0], "Tagging");
        byte[] art = null;
        final long tArtStart = android.os.SystemClock.elapsedRealtime();
        try {
            // Usually already resolved: the transfer took longer than the fetch did.
            art = artFuture.get(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable e) {
            // A cover is decoration. If it is late or missing the song is still saved; the
            // row simply has no embedded artwork.
            art = null;
        }
        if (art != null && art.length < 1024) art = null;
        final long artMs = android.os.SystemClock.elapsedRealtime() - tArtStart;
        final long tTagStart = android.os.SystemClock.elapsedRealtime();
        try {
            Id3Writer.tag(mp3, track.title, track.artist, track.album, art);
        } catch (Throwable e) {
            Log.w(TAG, "tagging failed", e);
        }
        final long tagMs = android.os.SystemClock.elapsedRealtime() - tTagStart;

        job.check();
        highWater[0] = Math.max(highWater[0], 95);
        report(listener, highWater[0], "Saving to Music/Cartify");
        String uri;
        final long tImportStart = android.os.SystemClock.elapsedRealtime();
        try {
            uri = LibraryStore.importFile(context, mp3, track.title, track.artist, track.album);
        } catch (Throwable e) {
            throw new Failure("save", "Could not save to storage: " + describe(e));
        }
        final long importMs = android.os.SystemClock.elapsedRealtime() - tImportStart;
        //noinspection ResultOfMethodCallIgnored
        mp3.delete();
        report(listener, 100, "Done");
        long finish = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "phase tagsave=" + (finish - afterFetch) + "ms total="
                + (finish - tTrackStart) + "ms title=\"" + track.title + "\""
                + " artFetch=" + artMs + "ms tag=" + tagMs + "ms import=" + importMs + "ms"
                + " artBytes=" + (art == null ? 0 : art.length));
        return uri;
    }

    /**
     * Resolves the best upload for a track, then downloads exactly that one.
     *
     * The candidates are inspected from metadata first: searching with ytsearch5 and
     * downloading the collection pulled five files per song (very slow, and every
     * rejected candidate flashed an error row). Here only the chosen URL is fetched.
     */
    /**
     * Player clients tried in order, per attempt.
     *
     * android is first because it skips the JavaScript challenge that made extraction
     * take 13-20 s. ios and web exist as fallbacks for the videos whose android
     * manifest carries no audio-only stream; the retry loop switches chain on a
     * format failure so a track that failed once gets a genuinely different client.
     */
    /**
     * Client order used when only a playable url is wanted, not a file.
     *
     * Deliberately different from {@link #CLIENT_CHAINS}: that order is tuned for
     * downloads, where an audio-only format matters because the bytes are kept forever.
     * A stream is thrown away when the song ends, so the only thing that matters is how
     * fast an address comes back. Measured on the same tracks, android,ios answers in
     * about 21 s against 27 s for tv_embedded, and the muxed format it returns plays
     * fine through MediaPlayer, so it goes first here and tv_embedded stays as the
     * fallback that is known to expose audio-only streams.
     */
    private static final String[] STREAM_CHAINS = {
            "android,ios",
            "tv_embedded",
            "ios,web",
            "web,android",
            "web,android,ios,mweb",
    };

    private static final String[] CLIENT_CHAINS = {
            // tv_embedded is first because it is the only client probed that exposes
            // audio-only streams (formats 139/140/251). android and ios expose none at all:
            // a format listing on those chains shows zero "audio only" rows, so
            // "bestaudio" matched nothing and every download silently fell through to
            // muxed format 18 - a 360p MP4 whose video ffmpeg then discarded. Measured on
            // one track: 5.32 MiB fetched for a 2.1 MB mp3.
            "tv_embedded",
            "android,ios",
            "ios,web",
            "web,android",
            // Last attempt uses the widest client set. It is the slowest to resolve, which
            // is exactly why it is the last resort rather than the default.
            "web,android,ios,mweb",
    };

    private static File fetch(File work, Track track, Listener listener, int attempt, Task task,
                              int[] highWater, Context context) throws Failure {
        String query = track.searchQuery();
        long expected = track.durationMs;
        task.check();

        String clientChain = CLIENT_CHAINS[Math.min(attempt, CLIENT_CHAINS.length - 1)];

        // How many parallel fragments this transfer may open. On a fast link more streams
        // win; on a slow one they starve each other and cause the timeouts that read as
        // failures. Each retry HALVES the count rather than shaving one off, because a
        // shared or congested connection can report a healthy link rate and still deliver
        // a fraction of it: 8 -> 4 -> 2 turns "times out forever" into "finishes slowly".
        int fragments = Math.max(1, fragmentsForSpeed(context) >> attempt);

        // Logged so a slow download can be diagnosed afterwards. Without the client
        // actually used and the fragment count there is no way to tell a bad network
        // from a bad client choice, which is exactly the question a 16 s track raises.
        Log.i(TAG, "attempt " + (attempt + 1) + " client=" + clientChain
                + " fragments=" + fragments + " quality=" + DownloadQuality.get(context));

        // Stages re-report a fixed mark (4 matching, 90 tagging, 95 saving). On a retry the
        // high-water mark is already far past those, so reporting them raw made the row
        // fall back to 4 and then climb again — visible as the number jumping around.
        int stage = Math.max(4, Math.min(85, highWater[0]));
        report(listener, stage, "Matching track");
        // Phase timing: a 47 s track needs to say WHICH stage owns the time. Search,
        // transfer and conversion are three very different problems with three very
        // different fixes, and without these marks the total is the only number available.
        final long tPickStart = android.os.SystemClock.elapsedRealtime();
        String picked = attempt == 0 ? PICKED.get(query) : null;
        if (picked == null) {
            picked = pickCandidate(query, track, task);
            PICKED.put(query, picked);
        }
        task.check();
        Log.i(TAG, "phase pick=" + (android.os.SystemClock.elapsedRealtime() - tPickStart)
                + "ms query=\"" + query + "\"");

        YoutubeDLRequest request = new YoutubeDLRequest(picked);
        // Format selection must never be empty. A single client can hand back a video
        // whose only streams are video+audio muxed, and "bestaudio/best" then matches
        // nothing at all: "Requested format is not available". The trailing "best" and
        // the wildcard keep a playable stream available on every client.
        request.addOption("-f", "bestaudio/best");
        // Sort by audio bitrate, highest first, so the chosen stream is the best
        // the source offers instead of an arbitrary format id.
        request.addOption("-S", "abr,acodec:opus");
        request.addOption("--no-playlist");
        // The default web client has to solve YouTube's JavaScript player challenge
        // before it can name a stream, which measured 13-20 s per track on this device
        // and was what the progress bar sat on at 6%. The android client returns the
        // same audio without that round trip: 16.5 s of extraction became 4.0 s on a
        // back-to-back test of the same network and device.
        //
        // A chain rather than a single client: some videos expose no audio-only stream
        // on android, and yt-dlp tries each in order until one yields a format.
        request.addOption("--extractor-args",
                "youtube:player_client=" + clientChain);

        request.addOption("--no-warnings");
        // Without --newline yt-dlp redraws its progress bar with carriage returns on one
        // line. The binding parses that stream line by line, so a whole transfer can arrive
        // as a single "0% ... 100%" update or be dropped entirely - measured as peakRate=0
        // with no intermediate callbacks at all, which is why the row sat at one percentage
        // for the entire download. --newline emits one line per update.
        request.addOption("--newline");
        request.addOption("--no-mtime");
        request.addOption("--extract-audio");
        request.addOption("--audio-format", "mp3");
        // V0 is LAME's best variable-bitrate preset (~245 kbps) and the least lossy MP3
        // this pipeline can produce. It used to be V5 (~130 kbps), which threw away
        // detail on top of the loss the source already carries.
        // The level comes from the user setting, which defaults to V0 (the old hardcoded
        // behaviour). 320 and 192 are constant-bitrate ids that yt-dlp accepts directly.
        request.addOption("--audio-quality", DownloadQuality.get(context));

        // ---------------------------------------------------------------- slow links
        //
        // The previous settings were tuned on a fast connection and actively hurt on a
        // weak one. Every option below exists because its opposite fails at low bandwidth:
        //
        // --concurrent-fragments 8 opened eight parallel streams. On a slow link those
        //   compete for the same narrow pipe, so each fragment times out waiting its turn
        //   and yt-dlp reports "Remote end closed connection" or simply stalls. Fewer
        //   streams finish more reliably than many starved ones, and the count is chosen
        //   from the measured link speed when one is known.
        //
        // --retries 3 with the default backoff gave up after a few seconds of a link that
        //   was merely slow, not broken. More attempts with fragment-level retries let a
        //   transfer resume instead of restarting the whole track.
        //
        // The socket timeout is what actually converts "slow" into "failed": yt-dlp's
        //   default read timeout is short enough that a 2G-class link trips it mid-file.
        request.addOption("--retries", "10");
        request.addOption("--fragment-retries", "10");
        request.addOption("--retry-sleep", "2");
        request.addOption("--socket-timeout", "30");
        request.addOption("--concurrent-fragments", String.valueOf(fragments));

        // Never abort a slow download for being slow. This is the difference between
        // "this track takes four minutes on a bad connection" and "this track failed".
        request.addOption("--no-abort-on-error");
        // The audio stream is already Opus/AAC at a fixed rate; re-encoding it at V0
        // cost real time per track for no audible gain.
        request.addOption("--postprocessor-args", "-threads 0");
        request.addOption("-o", new File(work, "track.%(ext)s").getAbsolutePath());

        final String processId = "cartify-" + System.nanoTime() + "-" + attempt;
        task.bind(processId);
        YoutubeDLResponse response;
        final long tTransferStart = android.os.SystemClock.elapsedRealtime();
        final ProgressFunction progressFn =
                new ProgressFunction(listener, task, highWater, tTransferStart);
        try {
            response = YoutubeDL.getInstance().execute(request, processId, progressFn);
        } catch (Throwable e) {
            task.check();
            // The library reports most failures as a non-zero exit code, but some are
            // thrown instead: "Requested format is not available" arrived here and was
            // reported as a plain download failure, so the retry loop never switched
            // client chain and the track stayed failed. Classifying the message here
            // makes both paths retry the same way.
            String msg = describe(e);
            String low = msg.toLowerCase(Locale.US);
            boolean formatMiss = low.contains("requested format is not available")
                    || low.contains("no video formats found")
                    || low.contains("requested format not available");
            boolean transientNet = low.contains("remote end closed connection")
                    || low.contains("connection reset")
                    || low.contains("connection aborted")
                    || low.contains("timed out")
                    || low.contains("did not get any data blocks")
                    || low.contains("unable to download video data")
                    || low.contains("incomplete read");
            if (formatMiss || transientNet) {
                throw new Failure("format", "Could not download this track (" + msg + ")",
                        false, true);
            }
            boolean stale = low.contains("403") || low.contains("sign in")
                    || low.contains("unable to extract");
            throw new Failure("download", "Could not download this track (" + msg + ")", stale);
        }
        task.bind("");
        final long transferMs = android.os.SystemClock.elapsedRealtime() - tTransferStart;
        Log.i(TAG, "phase transfer=" + transferMs + "ms client=" + clientChain
                + " fragments=" + fragments + " exit=" + response.getExitCode()
                + " peakRate=" + (progressFn == null ? -1 : progressFn.bestRate) + "pct/s");
        logDownloadSummary(response);
        task.check();
        if (response.getExitCode() != 0) {
            String err = errorLine(response.getErr());
            String lower = err.toLowerCase(Locale.US);
            // "format is not available" is not a stale extractor: the video resolved fine,
            // it just has no stream the chosen client offered. It is retried with a
            // different client chain instead of surfacing as a failed row.
            boolean formatMiss = lower.contains("requested format is not available")
                    || lower.contains("no video formats found")
                    || lower.contains("requested format not available");
            if (formatMiss) {
                throw new Failure("format", err, false, true);
            }
            // A dropped connection is retried on a different client chain too: the retry
            // opens a fresh CDN session instead of repeating the request that just failed.
            boolean transientNet = lower.contains("remote end closed connection")
                    || lower.contains("connection reset")
                    || lower.contains("connection aborted")
                    || lower.contains("timed out")
                    || lower.contains("temporary failure")
                    // YouTube sometimes hands back a stream URL that resolves but delivers
                    // nothing. The format is valid on paper, the CDN returns zero bytes.
                    // Another client gets a different URL, which is the actual fix.
                    || lower.contains("did not get any data blocks")
                    || lower.contains("unable to download video data")
                    || lower.contains("incomplete read");
            if (transientNet) {
                throw new Failure("format", err, false, true);
            }
            boolean stale = err.contains("403")
                    || lower.contains("sign in")
                    || lower.contains("confirm your age")
                    || lower.contains("unable to extract");
            throw new Failure("download", "Downloader error: " + err, stale);
        }
        report(listener, 94, "Converting");
        File mp3 = findMp3(work);
        if (mp3 != null) {
            // Bytes plus the client name is the number that shows whether the
            // requested quality was honoured, without needing a log of the format id.
            Log.i(TAG, "downloaded " + mp3.length() + " bytes, client=" + clientChain);
        }
        if (mp3 == null) throw new Failure("convert", "No MP3 was produced");
        report(listener, 97, "Tagging");
        return mp3;
    }

    /**
     * How many parallel fragments the current link can actually feed.
     *
     * The old build hardcoded 8, which is faster on a good connection and actively harmful
     * on a bad one: eight streams share one narrow pipe, each waits longer than its socket
     * timeout, and yt-dlp gives up on a link that was only slow. The negotiated downstream
     * rate is a good enough proxy — it is what the radio has agreed to, not a guess.
     *
     * Unknown links get 4, the middle of the range, so an unreadable connection is treated
     * as average rather than as either extreme.
     */
    private static int fragmentsForSpeed(Context context) {
        if (context == null) return 4;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return 4;
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return 2;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return 2;
            int kbps = caps.getLinkDownstreamBandwidthKbps();
            if (kbps <= 0) return 4;
            if (kbps < 500) return 1;       // ~2G / poor signal: one stream, no contention
            if (kbps < 2000) return 2;      // ~3G
            if (kbps < 8000) return 4;      // typical 4G
            return 8;                       // fast link: parallel fragments pay off
        } catch (Throwable e) {
            return 4;
        }
    }

    /**
     * Finds the upload that actually is this song.
     *
     * The previous version accepted anything within 20 seconds (or 12.5% of the track)
     * of the expected length and then fell back to the first search hit, which is how a
     * "Bonus Track" edit got saved as the real song. Matching is now a scored decision:
     * title words, artist words, duration and explicit variant tags all count, and a
     * candidate that does not clear the bar is rejected rather than guessed at.
     */
    /**
     * Finds a match, trying progressively looser searches.
     *
     * The full "title artist" query is the most precise thing to ask for, but it is also
     * the most fragile: one misspelled word from Spotify ("Dhoom Majale" for "Dhoom
     * Machale") makes YouTube return nothing relevant at all, and the track then fails
     * as if the song did not exist. Dropping the artist, then trimming to the first few
     * title words, keeps a search in play when the exact string does not.
     */
    /** Single daemon thread for cover prefetches; a download is one cover at a time. */
    private static final java.util.concurrent.ExecutorService ART_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cartify-art-prefetch");
                t.setDaemon(true);
                return t;
            });

    /**
     * Starts fetching a track's cover in the background.
     *
     * Returns a future that resolves to the raw image bytes, or null when there is no cover
     * or the fetch failed. Callers must not block on this longer than they can afford: the
     * whole point is that the download usually outlasts it.
     */
    private static java.util.concurrent.Future<byte[]> prefetchArt(Track track) {
        if (track == null || track.coverUrl == null || track.coverUrl.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        final String url = track.coverUrl;
        return ART_POOL.submit(() -> {
            try {
                // Same short budget as before: a cover must never hold up a save.
                return Net.bytes(url, 4000, 4000);
            } catch (Throwable e) {
                return null;
            }
        });
    }

    private static String pickCandidate(String query, Track track, Task task) throws Failure {
        try {
            return pickCandidateOnce(query, track, task);
        } catch (Failure first) {
            if (!"search".equals(first.stage)) throw first;
            for (String relaxed : relaxedQueries(track, query)) {
                if (relaxed.equalsIgnoreCase(query) || relaxed.trim().isEmpty()) continue;
                task.check();
                try {
                    Log.i(TAG, "retrying search with \"" + relaxed + "\"");
                    return pickCandidateOnce(relaxed, track, task);
                } catch (Failure again) {
                    if (!"search".equals(again.stage)) throw again;
                }
            }
            throw first;
        }
    }

    /**
     * Looser versions of the query, most specific first.
     *
     * Only the parts that are safe to drop are dropped: the artist, and any word past the
     * fourth, because a long title is usually a long title plus its release notes. The
     * song name itself is never shortened below three words, which would turn "Dhoom
     * Majale Dhoom" into "Dhoom" and match anything.
     */
    private static java.util.List<String> relaxedQueries(Track track, String query) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String title = track == null ? "" : stripQualifiers(track.title);
        if (title.trim().isEmpty()) title = stripQualifiers(query);
        if (!title.trim().isEmpty()) out.add(title.trim());
        String[] words = title.trim().split("\\s+");
        if (words.length > 4) {
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (head.length() > 0) head.append(" ");
                head.append(words[i]);
            }
            out.add(head.toString());
        }
        return out;
    }

    private static String pickCandidateOnce(String query, Track track, Task task) throws Failure {
        long expected = track == null ? 0L : track.durationMs;
        // Spotify titles carry catalogue qualifiers that no upload repeats: "Deva Deva
        // (From \"Brahmastra\")" is the song "Deva Deva". Leaving them in made the wanted
        // title look only 75% present in a correctly named upload, which cost real matches.
        // Order matters: normalise() deletes every bracket, so stripping the qualifier
        // afterwards could never match and "(From \"Brahmastra\")" stayed in the
        // wanted title. That made a correct upload look like a partial title match
        // (identity 33 instead of 50) and got "Deva Deva" rejected.
        String wantTitle = normalise(stripQualifiers(track == null ? query : track.title));
        String wantArtist = normalise(track == null ? "" : track.artist);
        String wantAlbum = normalise(track == null ? "" : track.album);

        // Ten candidates instead of four: the right upload is often not in the top four
        // once live versions, remixes and karaoke tracks are in the results, and this is
        // still a single metadata call.
        YoutubeDLRequest search = new YoutubeDLRequest("ytsearch10:" + query);
        search.addOption("--skip-download");
        search.addOption("--no-warnings");
        search.addOption("--flat-playlist");
        search.addOption("--extractor-retries", "1");
        search.addOption("--print",
                "%(webpage_url)s\t%(duration)s\t%(title)s\t%(uploader)s\t%(channel)s");
        search.addOption("--ignore-errors");

        String searchId = "cartify-pick-" + System.nanoTime();
        task.bind(searchId);
        YoutubeDLResponse res;
        try {
            res = YoutubeDL.getInstance().execute(search, searchId, null);
        } catch (Throwable e) {
            task.check();
            throw new Failure("search", "Search failed (" + describe(e) + ")");
        }
        task.bind("");
        task.check();
        String out = res == null ? null : res.getOut();
        // An empty answer is almost never "the song does not exist": it is a search
        // that was throttled or lost its connection, and it reads exactly like a
        // genuine miss to the user. One retry after a pause costs two seconds and
        // turns a false "No match found" into a download.
        if (out == null || out.trim().isEmpty()) {
            Log.w(TAG, "empty search, retrying: " + query);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            task.check();
            String retryId = "cartify-pick2-" + System.nanoTime();
            task.bind(retryId);
            try {
                res = YoutubeDL.getInstance().execute(search, retryId, null);
            } catch (Throwable e) {
                task.check();
            } finally {
                task.bind("");
            }
            out = res == null ? null : res.getOut();
        }
        if (out == null || out.trim().isEmpty()) {
            throw new Failure("search", "No match found for this song");
        }

        String bestUrl = "";
        int bestScore = Integer.MIN_VALUE;
        String bestTitle = "";
        String bestChannel = "";
        /** How certainly the best candidate IS this song, ignoring its length. */
        int bestIdentity = 0;
        long bestDiff = Long.MAX_VALUE;

        for (String line : out.split("\\r?\\n")) {
            String[] parts = line.split("\\t");
            if (parts.length < 3) continue;
            String url = parts[0].trim();
            if (!url.startsWith("http")) continue;

            long durMs = Math.round(parseSeconds(parts[1].trim()) * 1000.0);
            String title = parts[2].trim();
            String uploader = parts.length > 3 ? parts[3].trim() : "";
            String channel = parts.length > 4 ? parts[4].trim() : "";

            // Two-element out-parameter: [0] total score, [1] title+artist only.
            int[] scores = new int[2];
            int score = scoreCandidate(wantTitle, wantArtist, wantAlbum,
                    expected, title, uploader + " " + channel, durMs, scores);
            long diff = expected > 0 && durMs > 0 ? Math.abs(durMs - expected) : Long.MAX_VALUE;

            if (score > bestScore || (score == bestScore && diff < bestDiff)) {
                bestScore = score;
                bestUrl = url;
                bestTitle = title;
                bestDiff = diff;
                bestIdentity = scores[1];
                bestChannel = uploader + " " + channel;
            }
        }

        // A score below this means nothing in the results looked like the track. Saying so
        // is better than saving the wrong song under the right name.
        // Duration is the strongest signal when the uploads are ambiguous, but it must not
        // veto a song whose identity is already certain. A YouTube \"Full Song\" carries an
        // intro and outro the Spotify master does not, so a perfect match can run 20 s
        // longer and score nothing for length: \"Dil Diyan Gallan Atif Aslam\" matched an
        // upload titled exactly that, by exactly that artist, and was still rejected at 50.
        //
        // So a candidate whose title and artist clearly name this song is accepted whatever
        // its length, and length decides only between candidates that are otherwise unclear.
        boolean certain = bestIdentity >= IDENTITY_ACCEPT_SCORE;
        if (bestUrl.isEmpty() || (bestScore < MIN_ACCEPT_SCORE && !certain)) {
            // A silent rejection is indistinguishable from a broken downloader when the
            // user sees a failed row. Recording the query, the best candidate and its
            // score makes every failure explainable from the log alone.
            Log.w(TAG, "rejected match: query=\"" + query + "\" bestScore=" + bestScore
                    + " identity=" + bestIdentity + " best=\"" + bestTitle
                    + "\" channel=\"" + bestChannel + "\" expected=" + expected + "ms");
            throw new Failure("search", bestUrl.isEmpty()
                    ? "No match found for this song"
                    : "Only a different recording of \"" + (track == null ? query : track.title)
                      + "\" was found (best match \"" + bestTitle + "\")");
        }
        Log.d(TAG, "matched \"" + query + "\" -> \"" + bestTitle + "\" score=" + bestScore
                + " identity=" + bestIdentity);
        return bestUrl;
    }

    /** Score at which a candidate is trusted as the same recording. */
    private static final int MIN_ACCEPT_SCORE = 55;

    /**
     * Title plus artist score at which the song is identified beyond reasonable doubt.
     *
     * Title is worth 35 and artist 20, so 42 means the artist matches in full and most of
     * the title does, or the title matches in full and most of the artist does. Below that
     * the length still has to carry the decision.
     */
    private static final int IDENTITY_ACCEPT_SCORE = 42;

    /**
     * How much a search result looks like the requested track, 0-100.
     *
     * Duration is the strongest single signal because a variant edit is almost always a
     * different length, but it is not enough alone: a "Bonus Track" re-release can run
     * within a second of the original. Title and artist words carry the rest, and a
     * variant marker the user did not ask for subtracts heavily.
     */
    private static int scoreCandidate(String wantTitle, String wantArtist, String wantAlbum,
                                      long expectedMs, String candTitle, String candChannel,
                                      long candMs, int[] parts) {
        String title = normalise(candTitle);
        String channel = normalise(candChannel);
        if (title.isEmpty()) return 0;

        // --- duration (0-45)
        int durScore = 0;
        if (expectedMs > 0 && candMs > 0) {
            long diff = Math.abs(candMs - expectedMs);
            if (diff <= 1500) durScore = 45;
            else if (diff <= 3000) durScore = 38;
            else if (diff <= 5000) durScore = 28;
            else if (diff <= 8000) durScore = 16;
            else if (diff <= 15000) durScore = 6;
            else durScore = 0;
        } else {
            // No length to compare: do not punish, but do not reward either.
            durScore = 18;
        }

        // --- title words (0-35)
        int titleScore = 0;
        if (!wantTitle.isEmpty()) {
            if (title.equals(wantTitle)) titleScore = 35;
            else if (title.contains(wantTitle)) titleScore = 30;
            else {
                double cov = coverage(wantTitle, title);
                titleScore = (int) Math.round(cov * 26);
            }
        }

        // --- artist (0-20), looked for in the title or the channel name
        int artistScore = 0;
        if (!wantArtist.isEmpty()) {
            if (title.contains(wantArtist) || channel.contains(wantArtist)) artistScore = 20;
            else {
                double cov = Math.max(coverage(wantArtist, title), coverage(wantArtist, channel));
                artistScore = (int) Math.round(cov * 16);
            }
        } else {
            artistScore = 8;
        }

        // Reported separately so the caller can tell "this is the song but the length is
        // off" from "this may not be the song at all".
        //
        // A variant marker the user did not ask for zeroes the identity too, not just
        // the total. Identity is what the caller trusts over length, so leaving it
        // intact would let "Song (Live at Glastonbury)" with the right title and
        // artist clear the shortcut and be saved as the studio recording - exactly
        // the mistake the scoring exists to prevent.
        int identity = titleScore + artistScore;

        int score = durScore + titleScore + artistScore;

        // --- variant penalty
        // A marker the user did not ask for means this is a different recording, however
        // close its length happens to be.
        String asked = wantTitle + " " + wantAlbum;
        for (String marker : VARIANT_MARKERS) {
            boolean inCandidate = title.contains(marker) || channel.contains(marker);
            boolean inWanted = asked.contains(marker);
            if (!inCandidate || inWanted) continue;
            // A plain lyrics upload is the same recording in worse packaging, so it loses
            // less than a genuinely different performance would.
            boolean packagingOnly = marker.equals("lyrics") || marker.equals("lyric video")
                    || marker.equals("legendado") || marker.equals("letra")
                    || marker.equals("paroles") || marker.equals("subtitulado")
                    || marker.equals("video oficial legendado");
            // A TRANSLATED lyrics upload is not packaging. The words on screen are a
            // different language, which means the video was re-cut to fit them and the audio
            // is routinely pitched or time-stretched away from the master. It used to sit in
            // the packaging branch above, and on 4 November 2026 the phone accepted
            //   "Canyon City - Fix You (TRADUÇÃO: PT - BR)"
            // for a Fix You download: the title and artist alone scored identity 50, which
            // cleared IDENTITY_ACCEPT_SCORE and skipped the length check entirely. Treating
            // it as a different recording is the honest answer — if the user wanted the
            // Portuguese version they would have searched for it.
            boolean translated = isTranslatedMarker(marker);
            if (translated) {
                score -= 40;
                identity = 0;
                continue;
            }
            score -= packagingOnly ? 22 : 40;
            if (!packagingOnly) identity = 0;
        }

        // Official sources are the ones that carry the master audio. A Topic channel is
        // YouTube's auto-generated home for a label's catalogue, so it is the single best
        // signal that this upload is the released recording and not a re-upload.
        boolean official = channel.contains("topic") || channel.contains("vevo")
                || title.contains("official audio") || title.contains("official music video")
                || title.contains("official video");
        if (official) score += 12;
        if (title.contains("official audio")) score += 4;

        if (parts != null && parts.length > 1) parts[1] = identity;
        if (parts != null && parts.length > 0) parts[0] = score;
        return score;
    }

    /**
     * Drops a trailing parenthetical or bracket group from a normalised title.
     *
     * Spotify names a track after the release it came from: "Deva Deva (From \"Brahmastra\")",
     * "Song (Deluxe Edition)", "Track (feat. Someone)". No upload repeats that, so keeping
     * it made the wanted title look only partly present in a correct result. Only one group
     * is removed, and only from the end, so a title that genuinely contains brackets in the
     * middle of its name is left alone.
     */
    private static String stripQualifiers(String title) {
        if (title == null || title.isEmpty()) return "";
        String out = title.trim();
        java.util.regex.Pattern tail = java.util.regex.Pattern
                .compile("\\s*[\\[\\(][^\\[\\(\\)\\]]*[\\]\\)]\\s*$");
        // Repeat: "Track (Live) (Deluxe)" should lose both groups.
        for (int guard = 0; guard < 3; guard++) {
            String next = tail.matcher(out).replaceAll("").trim();
            if (next.equals(out) || next.isEmpty()) break;
            out = next;
        }
        return out;
    }

    /** Markers that mean the upload is a different recording unless it was asked for. */
    private static final String[] VARIANT_MARKERS = {
            "bonus track", "remix", "cover", "live", "instrumental", "karaoke",
            "sped up", "slowed", "nightcore", "8d", "reverb", "acoustic",
            "demo", "edit", "extended", "radio version", "reprise",
            // A lyrics video is a still image over audio that has usually been re-encoded
            // at least once. It scored 88 against 78 for the official audio in testing,
            // which is exactly backwards: it is a re-upload, not the source.
            "lyrics", "lyric video", "legendado", "letra", "paroles", "subtitulado",
            "video oficial legendado",
            // Explicitly TRANSLATED lyric videos are a different language on screen, which
            // normally means the audio was re-cut to fit it. See isTranslatedMarker().
            "tradução", "traducao", "traducido", "traduzione", "traduit", "tradotto",
            "übersetzt", "übersetzung",
            // Concert and festival captures are never the studio recording.
            "live at", "pinkpop", "glastonbury", "concert", "festival",
    };

    /**
     * True when the marker names an explicitly *translated* lyrics upload.
     *
     * A plain lyric video is the same recording in worse packaging, so it only loses 22
     * points and keeps its identity. A translated one is held to the stricter rule: the
     * words on screen are another language, which normally means the video was re-cut and
     * the audio pitched or time-stretched away from the master.
     *
     * Only unambiguous "translated" words are listed. Words such as "letra", "legendado"
     * or "paroles" mean "lyrics" or "subtitled" and are routinely put on uploads of the
     * original song, so treating them as translations would reject good matches.
     */
    private static boolean isTranslatedMarker(String marker) {
        return marker.equals("tradução") || marker.equals("traducao")
                || marker.equals("traducido") || marker.equals("traduzione")
                || marker.equals("traduit") || marker.equals("tradotto")
                || marker.equals("übersetzt") || marker.equals("übersetzung");
    }

    /**
     * Fraction of the wanted words that appear in the candidate, 0-1.
     *
     * Word-based rather than substring-based so "Silenced By The Night" still matches a
     * title that reads "Keane - Silenced by the Night (Official Video)".
     */
    private static double coverage(String wanted, String candidate) {
        String[] words = wanted.split(" ");
        if (words.length == 0) return 0;
        int hit = 0;
        int total = 0;
        for (String w : words) {
            if (w.isEmpty() || w.length() <= 1) continue;
            total++;
            if (candidate.contains(w)) {
                hit++;
            } else if (fuzzyWordPresent(w, candidate)) {
                // Half credit: the word is there in a different spelling.
                hit++;
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    /**
     * Whether {@code candidate} contains a word one or two letters away from {@code wanted}.
     *
     * Song titles are transliterated inconsistently, and the difference is invisible until
     * a download fails: Spotify has "Dhoom Majale Dhoom", YouTube has "Dhoom Machale", and
     * one changed consonant made the app reject the only correct upload. Two edits covers
     * the transliteration pairs that actually occur (j/c, s/sh, a/e) without letting an
     * unrelated word through, and only words of five letters or more are compared so short
     * words cannot drift into each other.
     */
    private static boolean fuzzyWordPresent(String wanted, String candidate) {
        if (wanted.length() < 5) return false;
        // Two edits from six letters up. Transliteration swaps are usually one
        // character (a/e, s/sh) but sometimes two (j/ch): "Majale" vs "Machale"
        // was rejected at one edit, and that was the only upload of the song.
        int allowed = wanted.length() >= 6 ? 2 : 1;
        for (String word : candidate.split(" ")) {
            if (word.isEmpty()) continue;
            if (Math.abs(word.length() - wanted.length()) > allowed) continue;
            if (editsWithin(wanted, word, allowed)) return true;
        }
        return false;
    }

    /** True when the two words differ by at most {@code max} insertions, deletions or swaps. */
    private static boolean editsWithin(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > max) return false;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            // The whole row already exceeds the budget: no later row can come back under it.
            if (rowMin > max) return false;
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[m] <= max;
    }

    /**
     * Lowercases, strips punctuation and folds the spelling variants that differ between
     * Spotify metadata and YouTube titles.
     */
    private static String normalise(String value) {
        if (value == null) return "";
        String s = value.toLowerCase(Locale.US);
        s = s.replace("&", " and ").replace("feat.", " ").replace("feat", " ")
                .replace("ft.", " ").replace("ft ", " ")
                .replace("’", "'").replace("‘", "'").replace("“", "\"")
                .replace("”", "\"").replace("–", "-").replace("—", "-");
        s = s.replaceAll("[^a-z0-9' ]+", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    private static long parseSeconds(String value) {
        try {
            double d = Double.parseDouble(value);
            return d <= 0 ? 0 : Math.round(d);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Bridges yt-dlp's Kotlin progress callback to the plain Java listener.
     *
     * yt-dlp reports 0..100 for the audio transfer, then restarts its own counter for
     * the ffmpeg conversion, which used to read as the bar jumping backwards and then
     * racing to the end. Two things fix that: the transfer owns 6..92 and the
     * conversion owns 92..99, and the emitted value can never decrease, so a repeated or
     * out-of-order callback leaves the bar where it is instead of snapping it back.
     */
    /**
     * Logs the few yt-dlp lines that actually explain a transfer.
     *
     * A wall-clock total cannot distinguish a slow network from a bad client choice: 7 MB
     * in 17 s is about 400 KB/s, which is plausible for either. yt-dlp itself reports the
     * chosen stream and a final "at NNN KiB/s" speed, and that speed is the number that
     * separates the two cases. Only lines that carry that information are kept, capped in
     * count and length, so the log stays readable in a bug report.
     */
    private static void logDownloadSummary(YoutubeDLResponse response) {
        String out = response.getOut() == null ? "" : response.getOut();
        String err = response.getErr() == null ? "" : response.getErr();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String raw : (out + "\n" + err).split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // Format selection is the one line that explains the byte count: a muxed
            // video container carries megabytes of video that are thrown away by the
            // audio extraction, so knowing the format id is knowing whether the transfer
            // was ever going to be small.
            boolean useful = line.contains("[download]") || line.contains("[ExtractAudio]")
                    || line.contains("[ffmpeg]") || line.contains("Destination:")
                    || line.contains("format(s):") || line.contains("Downloading 1 format");
            if (!useful) continue;
            if (kept >= 6) break;
            kept++;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(line.length() > 140 ? line.substring(0, 140) : line);
        }
        if (sb.length() > 0) {
            Log.i(TAG, "yt-dlp: " + sb);
        } else {
            Log.i(TAG, "yt-dlp: no download summary lines");
        }
    }

    /**
     * Matches yt-dlp's transfer line, e.g. "  0.4% of 3.56MiB at 1.75MiB/s ETA 00:02".
     *
     * Only the leading percentage is captured. The size and speed are useful for logs but
     * the bar needs the percentage, and taking it from the text is the only reliable source
     * here - the callback's own Float stayed at -1 for the whole transfer.
     */
    private static final java.util.regex.Pattern PROGRESS_LINE =
            java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)%\\s+of\\s+");

    private static final class ProgressFunction implements Function3<Float, Long, String, Unit> {
        private final Listener listener;
        private final Task task;
        /** Shared with the retry loop so a new attempt starts above the last one. */
        private final int[] highWater;
        private int lastEmitted = -1;
        /** Progress callbacks seen while yt-dlp is still resolving (progress == -1). */
        private int extractTicks;
        /** When the first real byte arrived; 0 until then. */
        private long firstByteAt;
        /** When yt-dlp last reported -1, i.e. the end of the extraction phase. */
        private long extractEndedAt;
        /** Percentage and clock from the previous callback, for a rate estimate. */
        private int lastPct = -1;
        private long lastPctAt;
        /** Fastest percentage-per-second seen, as a whole number scaled by 100. */
        private int bestRate = 0;

        private final long transferStartAt;

        ProgressFunction(Listener listener, Task task, int[] highWater, long transferStartAt) {
            this.listener = listener;
            this.task = task;
            this.highWater = highWater;
            this.transferStartAt = transferStartAt;
        }

        @Override
        public Unit invoke(Float progress, Long eta, String line) {
            if (task != null && task.isCancelled()) return Unit.INSTANCE;
            int raw = progress == null ? -1 : Math.round(progress);
            // The Float parameter does not carry intermediate values for this pipeline: it
            // arrived as -1 throughout extraction and then jumped straight to the end, so
            // the row sat on one number for the whole transfer (measured peakRate=0 across
            // four downloads). The percentage IS present in the line text - "  0.4% of
            // 3.56MiB at 1.75MiB/s" - so it is parsed from there when the Float is silent.
            if (raw < 0 && line != null) {
                java.util.regex.Matcher m = PROGRESS_LINE.matcher(line);
                if (m.find()) {
                    try {
                        raw = Math.round(Float.parseFloat(m.group(1)));
                    } catch (NumberFormatException ignored) {
                        raw = -1;
                    }
                }
            }

            // yt-dlp reports -1 while it is still resolving the media: running the
            // JavaScript player challenge, picking a format and opening the stream. That
            // phase takes 10-20 s and used to be clamped to 0 and then pinned at the 6%
            // floor, so the row sat on "6% - Working" for the whole extraction and only
            // moved once the (very fast) transfer began. Reporting it as its own creeping
            // stage is what makes the wait legible.
            if (raw < 0) {
                extractEndedAt = android.os.SystemClock.elapsedRealtime();
                if (listener != null) {
                    // Creep slowly inside 6..14 so the row visibly moves without ever
                    // pretending the transfer has started.
                    int creep = Math.min(14, 6 + (int) (extractTicks / 3));
                    extractTicks++;
                    listener.onProgress(Math.max(6, Math.min(creep, 14)), "Fetching audio");
                }
                return Unit.INSTANCE;
            }

            int pct = Math.max(0, Math.min(100, raw));
            // Rate estimate: percentage gained per second between two callbacks. This is
            // size-independent, so it works before the total is known and stays comparable
            // across tracks. A link that is starving shows a rate near zero while yt-dlp is
            // still "downloading", which is exactly the case the OS bandwidth estimate
            // misses - it reports the radio's capability, not what is actually arriving.
            long nowMs = android.os.SystemClock.elapsedRealtime();
            if (lastPct >= 0 && pct > lastPct) {
                long dt = nowMs - lastPctAt;
                if (dt > 200) {
                    int rate = (int) ((pct - lastPct) * 100000L / dt);
                    if (rate > bestRate) bestRate = rate;
                }
            }
            lastPct = pct;
            lastPctAt = nowMs;
            if (firstByteAt == 0L) {
                firstByteAt = android.os.SystemClock.elapsedRealtime();
                // The single number that matters for diagnosis: how much of the wall time
                // was spent resolving the media versus moving bytes. A 17 s transfer is a
                // different problem depending on which half it lands in.
                android.util.Log.i("Cartify", "phase extract="
                        + (extractEndedAt == 0L ? -1 : (firstByteAt - extractEndedAt))
                        + "ms beforeFirstByte=" + (firstByteAt - transferStartAt) + "ms");
            }
            extractTicks = 0;
            // A retry starts its own 0..100 counter, so it is lifted above the best value
            // the previous attempt reached. The floor is capped at the band ceiling: once
            // the mark is already 86, "86 - floor" would go negative and the scaled value
            // would sink below the floor it was supposed to respect.
            int floor = Math.max(6, Math.min(86, highWater[0]));
            int scaled;
            String detail;
            if (pct >= 100) {
                // yt-dlp parks at 100% while ffmpeg converts, which is the longest wait of
                // the whole job. Holding the transfer ceiling steady and naming the stage is
                // what stops it looking like a stall; creeping the number towards 92 made it
                // sit at a value that then jumped backwards when tagging began.
                scaled = Math.max(floor, 86);
                detail = "Converting";
            } else {
                scaled = floor + (int) (pct * (86 - floor) / 100.0);
                if (scaled < floor) scaled = floor;
                if (scaled > 86) scaled = 86;
                detail = pct > 0 ? pct + "%" : "Working";
            }
            // Repeated callbacks for the same percentage must not move the bar, and nothing
            // may ever travel backwards. The row reads the same number until it truly changes.
            if (scaled < lastEmitted) scaled = lastEmitted;
            lastEmitted = scaled;
            if (scaled > highWater[0]) highWater[0] = scaled;
            if (listener != null) listener.onProgress(scaled, detail);
            return Unit.INSTANCE;
        }
    }

    private static File findMp3(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().toLowerCase(Locale.US).endsWith(".mp3")) return f;
        }
        for (File f : files) {
            if (f.isFile() && !f.getName().endsWith(".part")) return f;
        }
        return null;
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** Picks the first ERROR line so the row shows a readable reason. */
    private static String errorLine(String text) {
        if (text == null || text.isEmpty()) return "unknown";
        String[] lines = text.trim().split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.startsWith("ERROR:")) return tidy(l);
        }
        return tidy(lines[lines.length - 1].trim());
    }

    private static String tidy(String line) {
        String out = line.replace("ERROR:", "").trim();
        if (out.length() > 110) out = out.substring(0, 110).trim() + "…";
        return out.isEmpty() ? "unknown" : out;
    }

    private static void report(Listener l, int pct, String detail) {
        if (l != null) l.onProgress(Math.min(100, pct), detail);
    }
}
