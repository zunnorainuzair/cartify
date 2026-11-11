package com.cartify.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-app updates driven by GitHub releases.
 *
 * The app publishes its builds to the repository's release section, so the newest release
 * tag is the source of truth for "what version exists". A build older than the newest tag
 * shows an update banner with the release notes and a download button; the APK is fetched
 * into the private cache and handed to the system installer through a FileProvider, which
 * is the only way the installer can read a file the app owns.
 *
 * The check is deliberately quiet: a failed request, an unreachable network or a missing
 * release all resolve to "no update", never to an error dialog on launch.
 */
public final class Updater {

    private static final String TAG = "Cartify";
    /**
     * Replace with the published repository when it changes.
     *
     * The account was renamed from zunnorain700-beep to zunnorainuzair; GitHub still
     * answers the old name with a 301, but every check paid an extra round trip for it
     * and the redirect is not guaranteed to last.
     */
    public static final String REPO = "zunnorainuzair/cartify";

    private static final String API_LATEST = "https://api.github.com/repos/%s/releases/latest";
    private static final String KEY_LAST_PROMPT = "update_prompted_tag";
    private static final String KEY_LAST_CHECK = "update_checked_at";
    /**
     * The last release that was actually newer than the installed build.
     *
     * The six-hour throttle used to answer "no update" for every launch inside the
     * window, so the banner only ever appeared once and vanished on the next start.
     * Keeping the answer means a throttled check can still report it.
     */
    private static final String KEY_CACHED_TAG = "update_cached_tag";
    private static final String KEY_CACHED_NOTES = "update_cached_notes";
    private static final String KEY_CACHED_URL = "update_cached_url";
    private static final String KEY_CACHED_SIZE = "update_cached_size";
    /** One check per six hours is plenty and keeps the API well inside its rate limit. */
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "cartify-updater");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** What a release looks like once it has been read off the API. */
    public static final class Release {
        public String tag = "";
        public String name = "";
        public String notes = "";
        public String apkUrl = "";
        public long size = 0;
        /** "sha256:..." when the API provides one; empty when it does not. */
        public String digest = "";
        public String publishedAt = "";

        /** Numeric form of the tag, so 1.2.10 sorts above 1.2.9. */
        public int[] numbers() {
            String digits = tag.replaceAll("[^0-9.]", "");
            String[] parts = digits.isEmpty() ? new String[0] : digits.split("\\.");
            int[] out = new int[Math.max(3, parts.length)];
            for (int i = 0; i < parts.length && i < out.length; i++) {
                try {
                    out[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException ignored) {
                    out[i] = 0;
                }
            }
            return out;
        }

        /** "1.1.0 (3.4 MB)" — what the sheet shows under the title. */
        public String summary() {
            StringBuilder sb = new StringBuilder(tag);
            if (size > 0) sb.append("  \u00b7  ").append(formatSize(size));
            return sb.toString();
        }
    }

    /** Called on the main thread with the newer release, or with null when up to date. */
    public interface OnResult {
        void onResult(Release newer);
    }

    /**
     * Why a check produced nothing.
     *
     * The automatic check never shows these; a manual check does, because tapping
     * "check for updates" and getting silence reads as a broken button.
     */
    public enum Status {
        /** The API answered and the installed build is current. */
        UP_TO_DATE,
        /** The API answered with a newer release. */
        UPDATE_AVAILABLE,
        /** The device could not reach GitHub. */
        OFFLINE,
        /** GitHub refused the request (rate limit or HTTP error). */
        UNAVAILABLE
    }

    private Updater() {
    }

    /** Current version as {major, minor, patch}. */
    public static int[] currentVersion(Context context) {
        String name = appVersionName(context);
        String digits = name.replaceAll("[^0-9.]", "");
        String[] parts = digits.isEmpty() ? new String[0] : digits.split("\\.");
        int[] out = new int[Math.max(3, parts.length)];
        for (int i = 0; i < parts.length && i < out.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    public static String appVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /**
     * True when {@code remote} is a later version than {@code local}.
     *
     * Compares every segment rather than stopping at the shorter array: a tag like
     * 1.0.1.2 used to compare equal to 1.0.1 because the loop only reached the third
     * element, so a four-part hotfix would never have been offered to anyone.
     */
    private static boolean isNewer(int[] remote, int[] local) {
        int n = Math.max(remote.length, local.length);
        for (int i = 0; i < n; i++) {
            int r = i < remote.length ? remote[i] : 0;
            int l = i < local.length ? local[i] : 0;
            if (r != l) return r > l;
        }
        return false;
    }

    /**
     * Checks for a newer release and reports it, at most once per {@code CHECK_INTERVAL_MS}
     * unless {@code force} is set.
     */
    public static void check(Context context, boolean force, OnResult callback) {
        checkWithStatus(context, force, (release, status) -> {
            if (callback != null) callback.onResult(release);
        });
    }

    /** Status-reporting form of {@link #check(Context, boolean, OnResult)}. */
    public static void checkWithStatus(Context context, boolean force, OnChecked callback) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) {
            // Still answer from the last known result. Reporting null here made the
            // banner flash once and then disappear for six hours.
            final Release cached = cachedRelease(app);
            final Status st = cached != null ? Status.UPDATE_AVAILABLE : Status.UP_TO_DATE;
            if (callback != null) MAIN.post(() -> callback.onChecked(cached, st));
            return;
        }

        POOL.execute(() -> {
            Release newer = null;
            Status status = Status.UP_TO_DATE;
            boolean answered = false;
            try {
                final int[] code = {0};
                JSONObject o = fetchLatest(code);
                if (code[0] == 200) {
                    answered = true;
                    if (o != null) {
                        Release r = parse(o);
                        if (!r.tag.isEmpty() && !r.apkUrl.isEmpty()
                                && isNewer(r.numbers(), currentVersion(app))) {
                            newer = r;
                            status = Status.UPDATE_AVAILABLE;
                        }
                    }
                } else if (code[0] == 404) {
                    // The repository has no releases yet: that is "up to date", not an
                    // error, and it must not be retried on every launch.
                    answered = true;
                } else if (code[0] == 403 || code[0] == 429) {
                    status = Status.UNAVAILABLE;
                } else {
                    status = Status.UNAVAILABLE;
                }
            } catch (Exception e) {
                // A socket failure is the common case offline. It is NOT recorded as a
                // completed check: doing that used to silence the updater for six hours
                // after one launch without a connection.
                Log.w(TAG, "update check failed", e);
                status = e instanceof java.io.IOException ? Status.OFFLINE : Status.UNAVAILABLE;
            }
            // Only a check that actually reached GitHub may start the six-hour clock.
            if (answered) {
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            }
            if (newer != null) saveCachedRelease(app, newer);
            final Release result = newer;
            final Status st = status;
            if (callback != null) MAIN.post(() -> callback.onChecked(result, st));
        });
    }

    /** Status-reporting callback. */
    public interface OnChecked {
        void onChecked(Release newer, Status status);
    }

    /** Reads the last release that was newer than the installed build, if any. */
    private static Release cachedRelease(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE);
        String tag = prefs.getString(KEY_CACHED_TAG, "");
        String url = prefs.getString(KEY_CACHED_URL, "");
        if (tag == null || tag.isEmpty() || url == null || url.isEmpty()) return null;
        Release r = new Release();
        r.tag = tag;
        r.name = tag;
        r.notes = prefs.getString(KEY_CACHED_NOTES, "");
        r.apkUrl = url;
        r.size = prefs.getLong(KEY_CACHED_SIZE, 0L);
        // A cached tag can go stale in the other direction too: the user may have installed
        // the update from the browser, in which case it is no longer newer.
        return isNewer(r.numbers(), currentVersion(context)) ? r : null;
    }

    private static void saveCachedRelease(Context context, Release r) {
        context.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
                .putString(KEY_CACHED_TAG, r.tag)
                .putString(KEY_CACHED_NOTES, r.notes)
                .putString(KEY_CACHED_URL, r.apkUrl)
                .putLong(KEY_CACHED_SIZE, r.size)
                .apply();
    }

    /** True when this exact release has already been offered, so relaunching is quiet. */
    public static boolean alreadyOffered(Context context, String tag) {
        return tag != null && tag.equals(context
                .getSharedPreferences("updater", Context.MODE_PRIVATE)
                .getString(KEY_LAST_PROMPT, ""));
    }

    public static void markOffered(Context context, String tag) {
        context.getSharedPreferences("updater", Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_PROMPT, tag == null ? "" : tag).apply();
    }

    private static JSONObject fetchLatest(int[] codeOut) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(String.format(Locale.US, API_LATEST, REPO))
                    .openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "Cartify-Updater");
            int code = c.getResponseCode();
            if (codeOut != null && codeOut.length > 0) codeOut[0] = code;
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Picks the release's first .apk asset. */
    private static Release parse(JSONObject o) {
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", r.tag);
        r.notes = o.optString("body", "");
        r.publishedAt = o.optString("published_at", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets == null) return r;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                r.apkUrl = a.optString("browser_download_url", "");
                r.size = a.optLong("size", 0L);
                // Newer GitHub responses carry a digest; older ones do not. When it is
                // present the download is verified against it, so a corrupted or swapped
                // file is rejected before the installer ever sees it.
                r.digest = a.optString("digest", "");
                break;
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ release notes

    /**
     * Turns GitHub-flavoured markdown into something readable in a plain TextView.
     *
     * The notes used to be shown raw, so the sheet displayed literal "##" and "**"
     * around every line. Only the constructs that actually appear in these release
     * bodies are handled; anything else is left alone rather than mangled.
     */
    public static String plainNotes(String markdown) {
        if (markdown == null) return "";
        String s = markdown.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("(?s)<!--.*?-->", "");                 // HTML comments
        s = s.replaceAll("(?m)^\\s*#{1,6}\\s*", "");             // ATX headings
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "\u2022 ");       // bullets
        s = s.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");   // images
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");    // links
        s = s.replace("**", "").replace("__", "");               // bold
        s = s.replaceAll("`([^`]*)`", "$1");                     // inline code
        s = s.replaceAll("(?m)^\\s*(---|\\*\\*\\*)\\s*$", "");   // rules
        s = s.replaceAll("\n{3,}", "\n\n");                      // blank runs
        return s.trim();
    }

    /** "3.4 MB" for a byte count, so the sheet can say what the download will cost. */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.US, mb < 10 ? "%.1f MB" : "%.0f MB", mb);
    }

    // ------------------------------------------------------------------ download

    /** Progress of the download, delivered on the main thread. */
    public interface DownloadCallback {
        void onProgress(int percent);

        void onReady(File apk);

        void onFailed(String message);
    }

    /** The connection that is currently transferring, so it can be cancelled. */
    private static volatile HttpURLConnection activeDownload;
    private static final AtomicBoolean CANCELLED = new AtomicBoolean(false);

    /** Stops an in-flight download. The partial file is kept so a retry resumes. */
    public static void cancelDownload() {
        CANCELLED.set(true);
        HttpURLConnection c = activeDownload;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isDownloading() {
        return activeDownload != null;
    }

    /**
     * Downloads the release APK into cache/updates and reports when it can be installed.
     *
     * The transfer resumes rather than restarting: the bytes already on disk are kept in
     * a ".part" file and the next attempt asks for the rest with a Range header. On a
     * connection that drops every few megabytes this is the difference between an update
     * that eventually lands and one that never does.
     */
    public static void download(Context context, Release release, DownloadCallback cb) {
        if (context == null || release == null || cb == null) return;
        final Context app = context.getApplicationContext();
        CANCELLED.set(false);
        POOL.execute(() -> {
            HttpURLConnection c = null;
            File part = null;
            try {
                File dir = new File(app.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create update folder");
                // One release at a time: anything else in the folder is a leftover.
                cleanCache(dir, release.tag);

                final File out = new File(dir, "cartify-" + safeTag(release.tag) + ".apk");
                part = new File(out.getPath() + ".part");

                long already = part.exists() ? part.length() : 0L;
                if (already > 0 && release.size > 0 && already == release.size) {
                    // A previous run finished the bytes but died before the rename.
                    finishDownload(part, out, release, cb);
                    return;
                }
                if (already > 0 && release.size > 0 && already > release.size) {
                    // Corrupt or from a different build: start over.
                    if (!part.delete()) throw new Exception("Cannot reset the partial download");
                    already = 0L;
                }

                c = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Cartify-Updater");
                // GitHub serves release assets through a redirect to its CDN.
                c.setInstanceFollowRedirects(true);
                if (already > 0) {
                    c.setRequestProperty("Range", "bytes=" + already + "-");
                }
                activeDownload = c;
                int code = c.getResponseCode();
                boolean resumed = code == 206;
                if (code == 200 && already > 0) {
                    // The server ignored the range, so the partial file is useless.
                    if (!part.delete()) throw new Exception("Cannot reset the partial download");
                    already = 0L;
                } else if (code != 200 && code != 206) {
                    throw new Exception(code == 404 ? "The update file is no longer available"
                            : "Server returned " + code);
                }

                long total = c.getContentLengthLong();
                if (total <= 0) total = release.size - already;
                if (total <= 0) total = release.size;
                final long grandTotal = already + total;

                try (InputStream in = c.getInputStream();
                     FileOutputStream fos = new FileOutputStream(part, resumed)) {
                    byte[] buf = new byte[64 * 1024];
                    long read = already;
                    int n;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        if (CANCELLED.get()) throw new Cancelled();
                        fos.write(buf, 0, n);
                        read += n;
                        if (grandTotal > 0) {
                            int pct = (int) (read * 100 / grandTotal);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int p = pct;
                                MAIN.post(() -> cb.onProgress(p));
                            }
                        }
                    }
                    fos.flush();
                }

                if (release.size > 0 && part.length() != release.size) {
                    throw new Exception("The download ended early ("
                            + formatSize(part.length()) + " of " + formatSize(release.size) + ")");
                }
                finishDownload(part, out, release, cb);
            } catch (Cancelled e) {
                MAIN.post(() -> cb.onFailed("cancelled"));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage();
                Log.w(TAG, "update download failed", e);
                MAIN.post(() -> cb.onFailed(msg));
            } finally {
                activeDownload = null;
                if (c != null) c.disconnect();
            }
        });
    }

    /** Signals a user cancellation rather than a real failure. */
    private static final class Cancelled extends Exception {
    }

    /**
     * Validates the finished file and hands it to the caller.
     *
     * Two checks, both cheap and both worth having: the file must start with the ZIP
     * local-file header every APK begins with, and when GitHub published a digest the
     * bytes must match it. Android would reject a bad APK at install time anyway, but by
     * then the user has already been sent to the installer for nothing.
     */
    private static void finishDownload(File part, File out, Release release, DownloadCallback cb)
            throws Exception {
        if (!looksLikeApk(part)) {
            if (!part.delete()) Log.w(TAG, "cannot delete an invalid download");
            throw new Exception("The downloaded file is not an APK");
        }
        if (release.digest != null && release.digest.toLowerCase(Locale.US).startsWith("sha256:")) {
            String want = release.digest.substring(7).trim();
            String got = sha256(part);
            if (!want.equalsIgnoreCase(got)) {
                if (!part.delete()) Log.w(TAG, "cannot delete a mismatched download");
                throw new Exception("The update failed its integrity check");
            }
        }
        if (out.exists() && !out.delete()) {
            throw new Exception("Cannot replace the previous download");
        }
        if (!part.renameTo(out)) throw new Exception("Cannot finish the download");
        final File ready = out;
        MAIN.post(() -> cb.onReady(ready));
    }

    /** Every APK is a ZIP, and every ZIP starts with a local file header. */
    private static boolean looksLikeApk(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] head = new byte[4];
            if (in.read(head) != 4) return false;
            return head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    /**
     * Removes downloads that do not belong to {@code keepTag}.
     *
     * Without this every release ever downloaded stayed in the cache until Android
     * decided to reclaim the space, which on a phone is measured in tens of megabytes.
     */
    private static void cleanCache(File dir, String keepTag) {
        File[] files = dir.listFiles();
        if (files == null) return;
        String keep = "cartify-" + safeTag(keepTag) + ".apk";
        for (File f : files) {
            String n = f.getName();
            if (n.equals(keep) || n.equals(keep + ".part")) continue;
            if (n.endsWith(".apk") || n.endsWith(".part")) {
                if (!f.delete()) Log.w(TAG, "cannot delete stale update " + n);
            }
        }
    }

    /** A tag is used in a file name, so anything path-like is stripped. */
    private static String safeTag(String tag) {
        if (tag == null) return "update";
        String s = tag.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "update" : s;
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Android 8+ gates this behind "install unknown apps" per-app consent, so the first
     * attempt sends the user to that settings page instead of silently doing nothing.
     *
     * @return true when the installer was opened, false when permission is still missing
     */
    public static boolean install(Activity activity, File apk) {
        if (activity == null || apk == null || !apk.exists()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settingsIntent);
            } catch (Exception ignored) {
            }
            android.widget.Toast.makeText(activity,
                    "Allow Cartify to install apps, then the update continues",
                    android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".updates", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
            return true;
        } catch (Exception e) {
            android.widget.Toast.makeText(activity, "Could not open the installer",
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** A finished APK for this tag that is already on disk, or null. */
    public static File downloadedApk(Context context, String tag) {
        if (context == null || tag == null || tag.isEmpty()) return null;
        File f = new File(new File(context.getCacheDir(), "updates"),
                "cartify-" + safeTag(tag) + ".apk");
        return f.exists() && f.length() > 0 ? f : null;
    }
}
