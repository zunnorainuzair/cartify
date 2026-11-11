package com.cartify.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Moves finished MP3s into the shared Music/Cartify folder and lists them back. */
public final class LibraryStore {

    private static final String TAG = "Cartify";
    public static final String RELATIVE_DIR = Environment.DIRECTORY_MUSIC + "/Cartify";

    private LibraryStore() {
    }

    public static class Item {
        public long id;
        public String uri;
        public String title;
        public String artist;
        public String album;
        public long duration;
        public String displayName;
        /** MediaStore DATE_ADDED, in seconds; drives the "newest first" library order. */
        public long addedAt;

        public Track toTrack() {
            Track t = new Track(title, artist, "");
            t.album = album;
            t.localUri = uri;
            t.durationMs = duration;
            t.addedAt = addedAt;
            t.status = Track.DONE;
            // A library song is identified by the file it is, not by its title. Two
            // different songs can share a name and one song can exist twice, so lyrics
            // and the "already saved" checks key on this; it must never be blank.
            t.id = "local:" + (uri == null ? "" : uri);
            return t;
        }
    }

    /** Copies a finished file into MediaStore and returns its content uri. */
    public static String importFile(Context context, File source, String title, String artist, String album) throws IOException {
        String safeArtist = sanitize(artist.isEmpty() ? "Unknown artist" : artist);
        String safeTitle = sanitize(title.isEmpty() ? source.getName() : title);
        String fileName = safeArtist + " - " + safeTitle + ".mp3";

        // Re-downloading the same song should replace it, not pile up "(1)" copies.
        removeExisting(context, title, artist);

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Audio.Media.TITLE, title);
        values.put(MediaStore.Audio.Media.ARTIST, artist);
        values.put(MediaStore.Audio.Media.ALBUM, album == null || album.isEmpty() ? "Cartify" : album);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
        values.put(MediaStore.Audio.Media.IS_MUSIC, 1);

        Uri collection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR);
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        } else {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Cartify");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File target = new File(dir, fileName);
            copy(source, target);
            android.media.MediaScannerConnection.scanFile(context,
                    new String[]{target.getAbsolutePath()}, new String[]{"audio/mpeg"}, null);
            values.put(MediaStore.Audio.Media.DATA, target.getAbsolutePath());
            Uri inserted = resolver.insert(collection, values);
            return inserted == null ? Uri.fromFile(target).toString() : inserted.toString();
        }

        Uri item = resolver.insert(collection, values);
        if (item == null) throw new IOException("MediaStore refused the new file");
        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) throw new IOException("Cannot open destination");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(item, done, null, null);
        return item.toString();
    }

    /**
     * Finds an already-saved copy of the same song.
     *
     * Matching is on title + artist, the same pair the importer uses to replace an
     * earlier copy, so pasting a link twice recognises the file that is already there
     * instead of downloading it again. The comparison is case- and space-insensitive
     * because the tagger and the resolver do not always agree on capitalisation.
     */
    public static Item findSaved(Context context, String title, String artist) {
        if (title == null || title.trim().isEmpty()) return null;
        String wantTitle = normalise(title);
        String wantArtist = normalise(artist);
        for (Item it : query(context)) {
            if (!normalise(it.title).equals(wantTitle)) continue;
            String haveArtist = normalise(it.artist);
            if (wantArtist.isEmpty() || haveArtist.equals(wantArtist)
                    || haveArtist.contains(wantArtist) || wantArtist.contains(haveArtist)) {
                return it;
            }
        }
        return null;
    }

    /** Lowercase, collapse whitespace and strip the punctuation that tags drop. */
    private static String normalise(String value) {
        if (value == null) return "";
        String s = value.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return s;
    }

    public static List<Item> query(Context context) {
        List<Item> out = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
        };
        String selection;
        String[] args;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{RELATIVE_DIR + "%"};
        } else {
            selection = MediaStore.Audio.Media.DATA + " LIKE ?";
            args = new String[]{"%/Music/Cartify/%"};
        }
        try (Cursor c = resolver.query(collection, projection, selection, args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.uri = ContentUris.withAppendedId(collection, it.id).toString();
                it.title = c.getString(1);
                it.artist = c.getString(2);
                it.album = c.getString(3);
                it.duration = c.getLong(4);
            // Some files report a duration that cannot be real: a ripped VBR header or
            // a truncated tag gave one row 686 minutes for a three-minute song. Anything
            // past an hour is treated as unknown so the row shows no time rather than a
            // number that is obviously wrong, and the player still seeks by real time.
            if (it.duration < 0 || it.duration > 60L * 60L * 1000L) it.duration = 0L;
                it.displayName = c.getString(5);
                it.addedAt = c.getLong(6);
                out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "library query failed", e);
        }
        return out;
    }

    /**
     * Deletes one saved file. Returns true on success. A false return means the app no longer
     * owns the MediaStore row (e.g. the song was saved before a reinstall), so the caller has
     * to ask the system for permission to remove it.
     */
    public static boolean delete(Context context, String uri) {
        try {
            return context.getContentResolver().delete(Uri.parse(uri), null, null) > 0;
        } catch (SecurityException e) {
            Log.w(TAG, "delete needs user consent", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "delete failed", e);
            return false;
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Deletes an earlier copy of the same song, if one is already saved. */
    private static void removeExisting(Context context, String title, String artist) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String selection;
        String[] args;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? AND "
                    + MediaStore.Audio.Media.TITLE + "=? AND " + MediaStore.Audio.Media.ARTIST + "=?";
            args = new String[]{RELATIVE_DIR + "%", title, artist};
        } else {
            selection = MediaStore.Audio.Media.DATA + " LIKE ? AND "
                    + MediaStore.Audio.Media.TITLE + "=? AND " + MediaStore.Audio.Media.ARTIST + "=?";
            args = new String[]{"%/Music/Cartify/%", title, artist};
        }
        try (Cursor c = resolver.query(collection, new String[]{MediaStore.Audio.Media._ID},
                selection, args, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                Uri item = ContentUris.withAppendedId(collection, c.getLong(0));
                resolver.delete(item, null, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "removeExisting failed", e);
        }
    }

    public static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (s.length() > 90) s = s.substring(0, 90).trim();
        return s.isEmpty() ? "Unknown" : s;
    }

}
