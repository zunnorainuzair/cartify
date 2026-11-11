package com.cartify.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** A single song: Spotify metadata plus its local/download state. */
public class Track {

    public static final int IDLE = 0;
    public static final int QUEUED = 1;
    public static final int ACTIVE = 2;
    public static final int DONE = 3;
    public static final int ERROR = 4;

    public String id = "";
    public String title = "";
    public String artist = "";
    public String album = "";
    public String coverUrl = "";
    public String pageUrl = "";
    public long durationMs = 0L;
    /** When the file entered the library, used for the "newest first" order. */
    public long addedAt = 0L;

    public int status = IDLE;
    public int progress = 0;
    public String localUri = "";
    public String error = "";

    /**
     * Direct audio URL for real-time playback.
     *
     * Set by {@link DownloadEngine#resolveStream} while a row is streaming. It is a short
     * lived CDN link, so it is deliberately NOT persisted in {@link #toJson}: a url from
     * the last session is dead by the next launch, and a stale one would fail as "cannot
     * play" instead of being resolved again.
     */
    public String streamUrl = "";

    /** True while a background prefetch is resolving this row's stream url. */
    public transient boolean prefetching = false;

    /** True when this row is heard from the network rather than from a saved file. */
    public boolean isStreaming() {
        return streamUrl != null && !streamUrl.isEmpty();
    }

    /** The uri playback should open: the saved file when there is one, else the stream. */
    public String playbackUri() {
        if (localUri != null && !localUri.isEmpty()) return localUri;
        return streamUrl == null ? "" : streamUrl;
    }

    public Track() {
    }

    public Track(String title, String artist, String coverUrl) {
        this.title = title;
        this.artist = artist;
        this.coverUrl = coverUrl;
    }

    public boolean isDone() {
        return status == DONE && !localUri.isEmpty();
    }

    /** Search string used for the downloader lookup. */
    public String searchQuery() {
        String a = artist == null ? "" : artist;
        return (title + " " + a).trim();
    }

    public String durationText() {
        if (durationMs <= 0) return "";
        long total = durationMs / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("title", title);
        o.put("artist", artist);
        o.put("album", album);
        o.put("cover", coverUrl);
        o.put("id", id);
        o.put("url", pageUrl);
        o.put("duration", durationMs);
        return o;
    }

    public static Track fromJson(JSONObject o) {
        Track t = new Track();
        t.title = o.optString("title");
        t.artist = o.optString("artist");
        t.album = o.optString("album");
        t.coverUrl = o.optString("cover");
        t.id = o.optString("id");
        t.pageUrl = o.optString("url");
        t.durationMs = o.optLong("duration");
        return t;
    }
}
