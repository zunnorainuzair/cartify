package com.cartify.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny bitmap downloader with an in-memory cache (no image library needed). */
public final class Net {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Net() {
    }

    public static byte[] bytes(String url) {
        return bytes(url, 8000, 12000);
    }

    /**
     * Same fetch with explicit timeouts.
     *
     * The cover that is embedded in an ID3 tag is decorative: if it does not arrive quickly
     * the right move is to save the track without it, not to hold the whole download open.
     * The default 8 s + 12 s budget applied to that fetch meant a stalled cover could add
     * 20 s to a track that was otherwise finished - measured at 16.1 s on one run, with
     * artBytes=0 at the end of it.
     */
    public static byte[] bytes(String url, int connectMs, int readMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", "Cartify/1.0 (Android)");
            // Covers are JPEGs; gzip does not help images, but keep-alive reuses the
            // TLS connection for the next cover instead of a fresh handshake each time.
            c.setRequestProperty("Connection", "keep-alive");
            if (c.getResponseCode() >= 400) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Full-resolution artwork, for the player where the cover is drawn full-bleed.
     */
    /**
     * Cache-only lookup: never performs I/O.
     *
     * {@link #bitmap(String)} downloads on a cache miss. Calling it from
     * onBindViewHolder therefore did a network request on the UI thread every time a row
     * scrolled into view with an uncached cover - the exact stall that makes a list feel
     * sticky. A bind may only ask what is already in memory; anything else goes through the
     * existing background path.
     */
    public static Bitmap peek(String url) {
        if (url == null || url.isEmpty()) return null;
        return CACHE.get(url);
    }

    /** Cache-only lookup for a size-restricted decode. */
    public static Bitmap peek(String url, int targetPx) {
        if (url == null || url.isEmpty()) return null;
        return CACHE.get(targetPx > 0 ? (url + "#w" + targetPx) : url);
    }

    public static Bitmap bitmap(String url) {
        return bitmap(url, 0);
    }

    /**
     * Artwork decoded to roughly {@code targetPx} pixels wide.
     *
     * Every caller used to share one full-resolution decode, which meant a 54dp list
     * thumbnail held the same ~640px ARGB_8888 bitmap as the full-screen player cover -
     * about 1.6 MB each, and the single biggest contributor to this app's ~100 MB of
     * Graphics memory. Passing the real display size lets a thumbnail decode at a small
     * inSampleSize instead, which costs a fraction of that for an image nobody sees large.
     *
     * @param targetPx wanted width in pixels; 0 or less keeps full resolution.
     */
    public static Bitmap bitmap(String url, int targetPx) {
        if (url == null || url.isEmpty()) return null;
        // The cache is keyed by url + bucket, so a small decode never shadows a full one.
        final String key = targetPx > 0 ? (url + "#w" + targetPx) : url;
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;
        byte[] raw = bytes(url);
        if (raw == null) return null;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (targetPx > 0) {
            // First pass: read the intrinsic size without allocating pixels.
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int srcW = o.outWidth;
            // Second pass: power-of-two subsample until the result is close to the target.
            int sample = 1;
            if (srcW > 0) {
                while ((srcW / (sample * 2)) >= targetPx) sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
        }
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (b != null) CACHE.put(key, b);
        return b;
    }
}
