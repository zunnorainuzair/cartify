package com.cartify.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app music search, so a song can be found and downloaded without leaving Cartify.
 *
 * Spotify's own search endpoint requires an authenticated token and answers 401 to anyone
 * else, and the anonymous token the web player used to hand out now returns 403. Rather
 * than ship a client id and secret inside the APK (which anyone can extract, and which
 * would break every user at once when it is revoked), search uses Deezer's public catalog
 * with iTunes as a fallback. Both are keyless, return the same fields Cartify needs —
 * title, artist, album, length, large artwork — and are far less likely to rate limit a
 * phone doing a handful of searches.
 *
 * Results are converted into the same {@link Track} objects a resolved Spotify link
 * produces, so everything downstream (matching, downloading, tagging) is unchanged.
 */
public final class MusicSearch {

    private static final String TAG = "Cartify";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    /** Deezer and iTunes both cap a page at 25 for this endpoint. */
    private static final int LIMIT = 25;
    /** Playlist rows shown under the track results. */
    private static final int PLAYLIST_LIMIT = 8;
    /** Safety cap on how much of one playlist may be imported in a single tap. */
    private static final int MAX_PLAYLIST_TRACKS = 100;
    private static final int MAX_PLAYLIST_PAGES = 3;

    public interface Callback {
        void onResults(List<Track> tracks);

        void onFailed(String message);
    }

    private MusicSearch() {
    }

    /** Callback for playlist search, kept separate so track search stays unchanged. */
    public interface PlaylistCallback {
        void onResults(List<PlaylistResult> playlists);

        void onFailed(String message);
    }

    /**
     * Searches Deezer's playlist index for {@code query}.
     *
     * Playlists live on a separate endpoint from tracks (`/search/playlist`) and the shapes
     * differ: a playlist carries `title`, `nb_tracks`, a nested `user.name` as its owner and
     * a `picture_xl` cover, none of which appear on a track row. They are therefore modelled
     * as {@link PlaylistResult} rather than squeezed into Track - a Track without a duration
     * or an audio stream would be a lie the rest of the app would have to special-case.
     *
     * Failures are reported rather than swallowed: unlike the home screen's recommendations,
     * this runs because the user asked for something, so an empty list needs a reason.
     */
    public static void searchPlaylists(final String query, final PlaylistCallback callback) {
        if (query == null || query.trim().isEmpty()) {
            if (callback != null) callback.onFailed("Type something to search for");
            return;
        }
        POOL.execute(() -> {
            List<PlaylistResult> out = new ArrayList<>();
            String failure = "No playlists found";
            try {
                String url = "https://api.deezer.com/search/playlist?q="
                        + URLEncoder.encode(query, "UTF-8") + "&limit=" + PLAYLIST_LIMIT;
                JSONObject root = new JSONObject(httpGet(url));
                JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        PlaylistResult p = deezerPlaylist(data.optJSONObject(i));
                        if (p != null) out.add(p);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "playlist search failed", e);
                failure = describe(e);
            }
            final List<PlaylistResult> result = out;
            final String message = failure;
            if (callback == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (result.isEmpty()) callback.onFailed(message);
                else callback.onResults(result);
            });
        });
    }

    /**
     * Loads the tracks of one playlist.
     *
     * Deezer paginates playlist tracks in chunks of up to 100, so this follows `next` until
     * it runs out or the safety cap is hit. The cap matters: a huge editorial playlist would
     * otherwise queue hundreds of downloads from a single tap, and every one of those becomes
     * a separate yt-dlp job.
     */
    public static void loadPlaylistTracks(final PlaylistResult playlist, final Callback callback) {
        if (playlist == null || playlist.id.isEmpty()) {
            if (callback != null) callback.onFailed("That playlist has no id");
            return;
        }
        POOL.execute(() -> {
            List<Track> out = new ArrayList<>();
            String failure = "Could not read that playlist";
            try {
                String next = "https://api.deezer.com/playlist/" + playlist.id + "/tracks?limit=100";
                int guard = 0;
                while (next != null && !next.isEmpty() && guard < MAX_PLAYLIST_PAGES) {
                    guard++;
                    JSONObject root = new JSONObject(httpGet(next));
                    JSONArray data = root.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            Track t = deezerTrack(data.optJSONObject(i));
                            if (t != null) out.add(t);
                            if (out.size() >= MAX_PLAYLIST_TRACKS) break;
                        }
                    }
                    if (out.size() >= MAX_PLAYLIST_TRACKS) break;
                    next = root.optString("next", "");
                }
            } catch (Exception e) {
                Log.w(TAG, "playlist tracks failed", e);
                failure = describe(e);
            }
            final List<Track> result = out;
            final String message = failure;
            if (callback == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (result.isEmpty()) callback.onFailed(message);
                else callback.onResults(result);
            });
        });
    }

    /** Maps one Deezer playlist object, or null when it has no title. */
    private static PlaylistResult deezerPlaylist(JSONObject row) {
        if (row == null) return null;
        String title = row.optString("title");
        if (title.isEmpty()) return null;
        PlaylistResult p = new PlaylistResult();
        p.id = String.valueOf(row.optLong("id"));
        p.title = title;
        JSONObject user = row.optJSONObject("user");
        p.owner = user == null ? "" : user.optString("name");
        p.trackCount = row.optInt("nb_tracks", 0);
        p.coverUrl = row.optString("picture_xl",
                row.optString("picture_big", row.optString("picture_medium", "")));
        return p;
    }

    /** Runs {@code query} off the main thread and always calls back exactly once. */
    public static void search(final String query, final Callback callback) {
        if (query == null || query.trim().isEmpty()) {
            if (callback != null) callback.onFailed("Type something to search for");
            return;
        }
        POOL.execute(() -> {
            List<Track> out = null;
            String failure = "Search failed";
            try {
                out = searchArtistFirst(query);
            } catch (Exception e) {
                Log.w(TAG, "artist search failed", e);
                failure = describe(e);
            }
            if (out == null || out.isEmpty()) {
                try {
                    out = searchDeezer(query);
                } catch (Exception e) {
                    Log.w(TAG, "track search failed", e);
                    failure = describe(e);
                }
            }
            if (out == null || out.isEmpty()) {
                try {
                    List<Track> alt = searchItunes(query);
                    if (alt != null && !alt.isEmpty()) out = alt;
                } catch (Exception e) {
                    Log.w(TAG, "itunes search failed", e);
                }
            }
            final List<Track> result = out;
            final String message = failure;
            if (callback == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (result == null || result.isEmpty()) callback.onFailed(message);
                else callback.onResults(result);
            });
        });
    }

    /**
     * Fresh suggestions for the empty search screen.
     *
     * Uses Deezer's public chart, the closest keyless equivalent to Spotify's home feed.
     * Cached for half an hour so relaunching does not hammer the endpoint, and failures
     * stay silent: an empty home screen is better than an error dialog on launch.
     */
    public static void recommendations(final Callback callback) {
        POOL.execute(() -> {
            List<Track> out = new ArrayList<>();
            try {
                long now = System.currentTimeMillis();
                if (chartCache != null && now - chartAt < CHART_TTL_MS) {
                    out.addAll(chartCache);
                } else {
                    JSONObject root = new JSONObject(
                            httpGet("https://api.deezer.com/chart/0/tracks?limit=" + LIMIT));
                    JSONArray data = root.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            Track t = deezerTrack(data.optJSONObject(i));
                            if (t != null) out.add(t);
                        }
                    }
                    chartCache = new ArrayList<>(out);
                    chartAt = now;
                }
            } catch (Exception e) {
                Log.w(TAG, "chart fetch failed", e);
            }
            final List<Track> result = out;
            if (callback == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (!result.isEmpty()) callback.onResults(result);
            });
        });
    }

    private static List<Track> chartCache;
    private static long chartAt = 0L;
    private static final long CHART_TTL_MS = 30 * 60 * 1000L;

    /**
     * Searches Deezer tracks and ranks them for the query the user actually typed.
     *
     * Deezer's own ordering is title-relevance, so "coldplay" surfaced songs literally
     * called "Cold Play" by unrelated artists. Asking for the artist's top tracks was
     * worse: that endpoint returned collaborations rather than the band's own hits.
     *
     * Ranking the 25 search hits locally is both cheap and far more accurate: a row by an
     * artist whose name matches the query jumps to the front, then rows whose title or
     * album matches, then everything else in Deezer's order.
     */
    /**
     * Finds music that is genuinely different from what is on screen, for the shelf.
     *
     * The shelf used to be filled with a copy of the search rows, which made the label
     * "More like this" a lie: it showed the same songs twice. This asks Deezer for artists
     * related to the one at the top of the current list, then pulls a couple of their top
     * tracks. The result is different music by different artists, which is what the label
     * promises.
     *
     * Deezer's related-artist and artist-top endpoints are keyless, so this adds no secret
     * to the APK. Every track that comes back already carries title, artist, duration and
     * artwork, so it can be downloaded exactly like a search result.
     *
     * @param seedArtist the artist the current list is about; blank skips the lookup.
     * @param excludeTitles titles already in the rows, so the shelf never repeats them.
     */
    public static void relatedTo(final String seedArtist, final java.util.Set<String> excludeTitles,
                                 final Callback callback) {
        if (seedArtist == null || seedArtist.trim().isEmpty()) {
            if (callback != null) callback.onFailed("No artist to base suggestions on");
            return;
        }
        POOL.execute(() -> {
            List<Track> out = new ArrayList<>();
            String failure = "No related music found";
            try {
                // 1. Resolve the seed artist to a Deezer id.
                String artUrl = "https://api.deezer.com/search/artist?q="
                        + URLEncoder.encode(seedArtist.trim(), "UTF-8") + "&limit=1";
                JSONObject artRoot = new JSONObject(httpGet(artUrl));
                JSONArray artData = artRoot.optJSONArray("data");
                if (artData == null || artData.length() == 0) {
                    if (callback != null) {
                        final String msg = failure;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(() -> callback.onFailed(msg));
                    }
                    return;
                }
                String artistId = artData.optJSONObject(0).optString("id");
                if (artistId.isEmpty()) {
                    if (callback != null) {
                        final String msg = failure;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(() -> callback.onFailed(msg));
                    }
                    return;
                }

                // 2. Ask for related artists, then take a few of their top tracks.
                JSONObject relRoot = new JSONObject(
                        httpGet("https://api.deezer.com/artist/" + artistId + "/related?limit=4"));
                JSONArray relData = relRoot.optJSONArray("data");
                if (relData != null) {
                    int wanted = Math.min(3, relData.length());
                    for (int i = 0; i < wanted; i++) {
                        JSONObject rel = relData.optJSONObject(i);
                        if (rel == null) continue;
                        String relId = rel.optString("id");
                        if (relId.isEmpty()) continue;
                        try {
                            JSONObject topRoot = new JSONObject(
                                    httpGet("https://api.deezer.com/artist/" + relId + "/top?limit=4"));
                            JSONArray topData = topRoot.optJSONArray("data");
                            if (topData == null) continue;
                            for (int j = 0; j < topData.length(); j++) {
                                Track t = deezerTrack(topData.optJSONObject(j));
                                if (t == null) continue;
                                String key = (t.title + "|" + t.artist).toLowerCase(Locale.US);
                                if (excludeTitles != null && excludeTitles.contains(key)) continue;
                                boolean dup = false;
                                for (Track have : out) {
                                    if (have.title.equalsIgnoreCase(t.title)
                                            && have.artist.equalsIgnoreCase(t.artist)) {
                                        dup = true;
                                        break;
                                    }
                                }
                                if (!dup) out.add(t);
                            }
                        } catch (Exception ignored) {
                            // One related artist failing must not lose the others.
                        }
                    }
                }
            } catch (Exception e) {
                failure = describe(e);
            }
            final List<Track> result = out;
            final String message = failure;
            if (callback == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (result.isEmpty()) callback.onFailed(message);
                else callback.onResults(result);
            });
        });
    }

    private static List<Track> searchArtistFirst(String query) throws Exception {
        String url = "https://api.deezer.com/search?q="
                + URLEncoder.encode(query, "UTF-8") + "&limit=" + LIMIT;
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray data = root.optJSONArray("data");
        List<Track> out = new ArrayList<>();
        if (data == null) return out;

        String want = query.toLowerCase(Locale.US).trim();
        List<Track> exactArtist = new ArrayList<>();
        List<Track> partialArtist = new ArrayList<>();
        List<Track> rest = new ArrayList<>();

        for (int i = 0; i < data.length(); i++) {
            Track t = deezerTrack(data.optJSONObject(i));
            if (t == null) continue;
            String artist = t.artist == null ? "" : t.artist.toLowerCase(Locale.US);
            if (artist.equals(want)) exactArtist.add(t);
            else if (artist.contains(want)) partialArtist.add(t);
            else if (t.title != null && t.title.toLowerCase(Locale.US).contains(want)) partialArtist.add(t);
            else rest.add(t);
        }

        out.addAll(exactArtist);
        out.addAll(partialArtist);
        out.addAll(rest);
        return out;
    }

    // ------------------------------------------------------------------ deezer

    private static List<Track> searchDeezer(String query) throws Exception {
        String url = "https://api.deezer.com/search?q="
                + URLEncoder.encode(query, "UTF-8") + "&limit=" + LIMIT;
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray data = root.optJSONArray("data");
        List<Track> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            Track t = deezerTrack(row);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Maps one Deezer track object onto a Track, or null when it has no title. */
    private static Track deezerTrack(JSONObject row) {
        if (row == null) return null;
        String title = row.optString("title");
        if (title.isEmpty()) return null;
        JSONObject artist = row.optJSONObject("artist");
        JSONObject album = row.optJSONObject("album");

        Track t = new Track();
        t.title = title;
        t.artist = artist == null ? "" : artist.optString("name");
        t.album = album == null ? "" : album.optString("title");
        // Deezer reports whole seconds.
        t.durationMs = row.optLong("duration") * 1000L;
        if (album != null) {
            // Prefer the largest cover: rows are small but the player is full-bleed.
            String big = album.optString("cover_xl");
            if (big.isEmpty()) big = album.optString("cover_big");
            if (big.isEmpty()) big = album.optString("cover_medium");
            t.coverUrl = big;
        }
        // Deezer's own track page is a stable public reference for the song.
        t.pageUrl = row.optString("link");
        String isrc = row.optString("isrc");
        t.id = !isrc.isEmpty() ? isrc : (row.optLong("id") > 0
                ? "deezer:" + row.optLong("id")
                : "deezer:" + title.toLowerCase(java.util.Locale.US) + "|"
                  + t.artist.toLowerCase(java.util.Locale.US) + "|" + t.durationMs);
        return t;
    }

    // ------------------------------------------------------------------ itunes

    private static List<Track> searchItunes(String query) throws Exception {
        String url = "https://itunes.apple.com/search?term="
                + URLEncoder.encode(query, "UTF-8") + "&entity=song&limit=" + LIMIT;
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray results = root.optJSONArray("results");
        List<Track> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject row = results.optJSONObject(i);
            if (row == null) continue;
            String title = row.optString("trackName");
            if (title.isEmpty()) continue;

            Track t = new Track();
            t.title = title;
            t.artist = row.optString("artistName");
            t.album = row.optString("collectionName");
            t.durationMs = row.optLong("trackTimeMillis");
            String art = row.optString("artworkUrl100");
            // iTunes hands back a 100px thumbnail; the same URL at 600px is the real cover.
            t.coverUrl = art.isEmpty() ? "" : art.replace("100x100", "600x600");
            t.pageUrl = row.optString("trackViewUrl");
            // Every result needs its own identity. This branch used to leave t.id empty,
            // so two songs from iTunes shared the blank id and anything keyed on it
            // (lyrics, "is this already saved") could answer for the wrong one.
            String isrc = row.optString("isrc");
            t.id = !isrc.isEmpty() ? isrc : (row.optLong("trackId") > 0
                    ? "itunes:" + row.optLong("trackId")
                    : "itunes:" + title.toLowerCase(java.util.Locale.US) + "|"
                      + t.artist.toLowerCase(java.util.Locale.US) + "|" + t.durationMs);
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ http

        /**
     * Turns a network exception into something a person can act on.
     *
     * The raw text was shown verbatim: "unable to resolve host api.deezer.com: no
     * address associated with hostname" is true, and useless to anyone holding a
     * phone with no signal. The common cases are named; anything else keeps the
     * original wording so a real bug is still reportable.
     */
    private static String explain(Throwable e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage());
        String low = msg.toLowerCase(java.util.Locale.US);
        if (low.contains("unable to resolve host") || low.contains("unknownhost")
                || low.contains("no address associated")) {
            return "No internet connection";
        }
        if (low.contains("timed out") || low.contains("timeout")) {
            return "The connection timed out";
        }
        if (low.contains("econnrefused") || low.contains("connection refused")) {
            return "The music service refused the request";
        }
        return msg.isEmpty() ? "Could not reach the music service" : msg;
    }

private static String httpGet(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Cartify/1.0 (Android)");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("Server replied " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), "UTF-8");
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        // explain() names the cases a person can act on (no signal, timeout) and
        // passes anything else through untouched, so a genuine bug is still legible.
        return explain(e);
    }
}
