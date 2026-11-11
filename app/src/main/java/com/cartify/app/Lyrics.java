package com.cartify.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics.
 *
 * Source is LRCLIB (lrclib.net): public, keyless, and returns LRC lines with real
 * timestamps, which is what lets the player highlight each line as the song plays.
 *
 * Matching is deliberately strict. A title-only lookup will happily return a different
 * song that shares a name ("Real Bad Man" has a dozen unrelated versions on the
 * database), and a wrong lyric sheet is worse than none, so a result is only accepted
 * when its artist agrees with ours and its length fits the file we are playing.
 */
public final class Lyrics {

    private static final String TAG = "Cartify";
    // v4: v3 keyed the cache on artist+title only, and its matcher never looked at the
    // track NAME at all. Two different songs by the same artist therefore shared one
    // cache slot, and one song's sheet was displayed under the other - the "intermixed
    // lyrics" report. The key now carries the duration and the matcher requires the
    // returned track name to be this song. Old entries are simply left behind.
    private static final String PREFS = "cartify_lyrics_v5";
    private static final String UA = "Cartify/1.0 (Android music player)";
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    /** Words that carry no identifying weight when comparing artist names. */
    private static final Set<String> ARTIST_NOISE = new HashSet<>(Arrays.asList(
            "the", "and", "feat", "ft", "featuring", "with", "vs", "x", "music",
            "official", "records", "record", "prod", "by"));

    /** One lyric line with its start time. */
    public static class Line {
        public final long timeMs;
        public final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private Lyrics() {
    }

    /**
     * Cache identity: artist, title AND length.
     *
     * Artist+title alone is not an identity. A soundtrack artist such as "Pritam" has
     * dozens of songs, and two uploads can even share a title while being different
     * recordings; either way one entry was served for the other, which is exactly how
     * one song's lines appeared under a different song. The length is coarse (whole
     * seconds) so a re-encode does not miss the cache, but distinct recordings differ.
     */
    private static String key(String artist, String title, long durationMs) {
        return key(null, artist, title, durationMs);
    }

    /**
     * Cache identity, preferring the song's own unique id when there is one.
     *
     * Artist, title and length still cannot separate two uploads of a song that share
     * all three (a re-record, two mixes of a soundtrack cue). Every Track that comes from
     * a catalogue now carries its ISRC/track id, and that IS unique per recording, so it
     * leads the key whenever it is present.
     */
    private static String key(Track track, String artist, String title, long durationMs) {
        String id = track == null || track.id == null ? "" : track.id.trim().toLowerCase(Locale.US);
        if (!id.isEmpty()) return "id:" + id;
        return (artist == null ? "" : artist.trim().toLowerCase(Locale.US))
                + "|" + (title == null ? "" : title.trim().toLowerCase(Locale.US))
                + "|" + (durationMs > 0 ? (durationMs / 1000L) : 0L);
    }

    /** @return cached LRC text, "" when nothing was found, or null when never looked up. */
    public static String cached(Context context, String artist, String title, long durationMs) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(artist, title, durationMs), null);
    }

    /** Same lookup, keyed on the track's own id when it has one. */
    public static String cached(Context context, Track track) {
        if (track == null) return null;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(track, track.artist, track.title, track.durationMs), null);
    }

    /** Drops a cached answer so the next lookup asks the service again. */
    public static void forget(Context context, String artist, String title, long durationMs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(key(artist, title, durationMs)).apply();
    }

    /** Drops the cached answer for a track, under the id-first key. */
    public static void forget(Context context, Track track) {
        if (track == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(key(track, track.artist, track.title, track.durationMs)).apply();
    }

    /**
     * Fetches and caches synced lyrics; returns the LRC text or null.
     *
     * Only a definitive answer is cached. A temporary failure (server busy, no network)
     * must not be remembered, otherwise one 503 would hide the lyrics for that song
     * forever.
     */
    public static String fetch(Context context, Track track) {
        if (track == null) return null;
        String existing = cached(context, track);
        if (existing != null) return existing.isEmpty() ? null : existing;

        Result result = request(track);
        if (!result.definitive) return result.lrc;

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key(track, track.artist, track.title, track.durationMs),
                        result.lrc == null ? "" : result.lrc)
                .apply();
        return result.lrc;
    }

    /** A lookup outcome: the lyrics, and whether it is worth remembering. */
    private static final class Result {
        final String lrc;
        final boolean definitive;

        Result(String lrc, boolean definitive) {
            this.lrc = lrc;
            this.definitive = definitive;
        }
    }

    /** Parses LRC text into ordered, timestamped lines. */
    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return out;
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = TIME.matcher(raw);
            long time = -1;
            int end = 0;
            while (m.find()) {
                long minutes = Long.parseLong(m.group(1));
                long seconds = Long.parseLong(m.group(2));
                long fraction = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    while (f.length() < 3) f = f + "0";
                    fraction = Long.parseLong(f.substring(0, 3));
                }
                long t = minutes * 60000L + seconds * 1000L + fraction;
                if (time < 0) time = t;
                end = m.end();
            }
            if (time < 0) continue;
            String text = raw.substring(end).trim();
            if (text.isEmpty()) continue;
            out.add(new Line(time, text));
        }
        out.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    /** Index of the line that should be highlighted at {@code positionMs}, or -1. */
    public static int activeLine(List<Line> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int active = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs + 120) active = i;
            else break;
        }
        return active;
    }

    private static Result request(Track track) {
        // Artist-scoped first: it is the only query that can tell same-titled songs apart.
        Result byArtist = search(track, true);
        if (byArtist.lrc != null) return byArtist;
        if (!byArtist.definitive) return byArtist;   // transient failure: do not cache
        // A title-only pass catches featured-artist strings that never match the
        // database, but it still has to pass the artist check inside search().
        Result byTitle = search(track, false);
        if (byTitle.lrc != null) return byTitle;
        return new Result(null, byTitle.definitive);
    }

    /**
     * Queries LRCLIB and keeps the synced result that really is this song.
     *
     * @param withArtist when false only the title is sent, which catches featured-artist
     *                   strings that never match the database; the results are still
     *                   filtered on artist and length locally.
     */
    private static Result search(Track track, boolean withArtist) {
        StringBuilder url = new StringBuilder("https://lrclib.net/api/search?track_name=")
                .append(enc(cleanTitle(track.title)));
        if (withArtist && track.artist != null && !track.artist.isEmpty()) {
            url.append("&artist_name=").append(enc(track.artist.split(",")[0].trim()));
        }

        // LRCLIB sheds load with 503s; a couple of retries turns most of those into hits.
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpURLConnection c = null;
            try {
                if (attempt > 0) Thread.sleep(1500L * attempt);
                c = (HttpURLConnection) new URL(url.toString()).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 503 || code == 429 || code >= 500) {
                    continue;   // transient: try again, never cache
                }
                if (code >= 400) {
                    return new Result(null, true);   // real "nothing here" answer
                }

                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream();
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONArray results = new JSONArray(sb.toString());

                String best = null;
                long bestCost = Long.MAX_VALUE;
                for (int i = 0; i < results.length(); i++) {
                    JSONObject o = results.optJSONObject(i);
                    if (o == null) continue;
                    String synced = o.optString("syncedLyrics", "");
                    if (synced.trim().isEmpty()) continue;
                    if (!isRealLyrics(synced)) continue;
                    // The decisive filter: a different artist means a different song.
                    if (!sameArtist(track.artist, o.optString("artistName", ""))) continue;
                    // And the entry must actually be this song. Without this check the
                    // search ran on the title but nothing verified what came back, so any
                    // song by the same artist that happened to be a similar length was
                    // accepted - the "one song, another song's lyrics" bug.
                    if (!sameTitle(track.title, o.optString("trackName", ""))) continue;
                    long durMs = Math.round(o.optDouble("duration", 0) * 1000d);
                    if (!lengthFits(track.durationMs, durMs)) continue;
                    if (!timingFits(synced, track.durationMs)) continue;

                    // Prefer the closest length, then the closest title, so two versions
                    // of the same song do not flip-flop between runs.
                    long cost = track.durationMs > 0 && durMs > 0
                            ? Math.abs(durMs - track.durationMs)
                            : 0L;
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = synced;
                    }
                }
                return new Result(best, true);
            } catch (Exception e) {
                Log.d(TAG, "lyrics lookup failed for " + track.title + " (attempt " + attempt + ")");
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return new Result(null, false);   // could not reach the service: do not cache
    }

    /**
     * True when the database entry is by this artist.
     *
     * Compared as word sets so "Karan Aujla, DIVINE" still matches "Karan Aujla" and
     * "Ed Sheeran" matches "Ed Sheeran feat. Beyoncé", while "L2B" never matches a
     * Punjabi track that merely shares its title.
     */
    private static boolean sameArtist(String ours, String theirs) {
        Set<String> a = artistWords(ours);
        if (a.isEmpty()) return true;   // no artist metadata: nothing to check against
        Set<String> b = artistWords(theirs);
        if (b.isEmpty()) return false;
        // A single shared word is not agreement. "Karan Aujla" and "Karan Singh" share
        // one word and are different artists; the old any-word rule accepted them, and
        // with a similar length that was enough to display the wrong song's lines.
        // Requiring most of the shorter name settles the common cases: "Karan Aujla,
        // DIVINE" vs "Karan Aujla" agrees, "Ed Sheeran" vs "Ed Sheeran feat. Beyonce"
        // agrees, and the one-word coincidence does not.
        int shared = 0;
        for (String w : a) {
            if (b.contains(w)) shared++;
        }
        int smaller = Math.min(a.size(), b.size());
        return shared >= Math.max(1, smaller - (smaller > 2 ? 1 : 0)) && shared * 2 >= smaller;
    }

    /**
     * True when the database entry's track name is this song.
     *
     * Compared after stripping the qualifiers both sides like to add ("(From ...)",
     * "- Remastered", "feat. ..."), then as word sets so punctuation and a stray
     * "(Official Video)" do not reject a correct sheet. A title with no words in common
     * is a different song, and that is the case this check exists for.
     */
    private static boolean sameTitle(String ours, String theirs) {
        Set<String> a = titleWords(cleanTitle(ours));
        if (a.isEmpty()) return true;   // no title to compare: the artist check stands
        Set<String> b = titleWords(cleanTitle(theirs));
        if (b.isEmpty()) return false;
        // A single shared word is NOT the same title. "Kadi Te Has Bol" shares one word
        // with a dozen other Punjabi tracks, and with the search relaxed to a title-only
        // query that one word was enough to accept another song's sheet - the reported
        // "some songs have each other's lyrics". Most of the shorter title must agree,
        // exactly like the artist comparison, so a genuine variant ("... (Reprise)",
        // "... - Remastered") still passes while a different song does not.
        int shared = 0;
        for (String w : a) {
            if (b.contains(w)) shared++;
        }
        int smaller = Math.min(a.size(), b.size());
        if (shared == 0) return false;
        if (smaller == 1) return true;   // a one-word title can only be judged on itself
        return shared >= smaller - 1 && shared * 2 >= smaller + 1;
    }

    private static Set<String> titleWords(String title) {
        Set<String> out = new HashSet<>();
        if (title == null) return out;
        String cleaned = title.toLowerCase(Locale.US)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        for (String w : cleaned.split(" ")) {
            if (w.length() < 2) continue;
            if (ARTIST_NOISE.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    private static Set<String> artistWords(String name) {
        Set<String> out = new HashSet<>();
        if (name == null) return out;
        String cleaned = name.toLowerCase(Locale.US)
                .replaceAll("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        for (String w : cleaned.split(" ")) {
            if (w.length() < 2) continue;
            if (ARTIST_NOISE.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    /** Rejects a version whose length is nowhere near the file we are playing. */
    private static boolean lengthFits(long trackMs, long resultMs) {
        if (trackMs <= 0 || resultMs <= 0) return true;   // nothing to compare
        return Math.abs(resultMs - trackMs) <= Math.max(25000L, trackMs / 5L);
    }

    /**
     * Rejects a sheet whose timestamps cannot belong to this file.
     *
     * A live or extended cut of the same song drifts by a few seconds, but a sheet that
     * starts after the song is nearly over, or runs far past its end, is another
     * recording entirely.
     */
    private static boolean timingFits(String lrc, long trackMs) {
        if (trackMs <= 0) return true;
        List<Line> lines = parse(lrc);
        if (lines.isEmpty()) return false;
        long first = lines.get(0).timeMs;
        long last = lines.get(lines.size() - 1).timeMs;
        if (first > Math.max(45000L, trackMs / 2L)) return false;
        return last <= trackMs + 60000L;
    }

    /** Filters placeholder rows such as the single line "[00:00.00]probe". */
    private static boolean isRealLyrics(String lrc) {
        List<Line> lines = parse(lrc);
        if (lines.isEmpty()) return false;
        int real = 0;
        for (Line l : lines) {
            if (l.text.replaceAll("[^A-Za-z]", "").length() > 1) real++;
        }
        return real >= 4;
    }

    /** Strips the bracketed suffixes Spotify adds, which lyrics sites do not use. */
    private static String cleanTitle(String title) {
        if (title == null) return "";
        String t = title;
        t = t.replaceAll("(?i)\\s*[\\-\\u2013]\\s*(remaster(ed)?|remix|live|version|edit|mix|mono|stereo|acoustic)\\b.*$", "");
        t = t.replaceAll("\\([^)]*\\)", " ");
        t = t.replaceAll("\\[[^]]*]", " ");
        t = t.replaceAll("(?i)\\s*(feat\\.?|ft\\.?|featuring)\\s+.*$", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return v;
        }
    }
}
