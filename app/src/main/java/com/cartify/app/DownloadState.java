package com.cartify.app;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import org.json.JSONArray;

import androidx.core.content.ContextCompat;

/**
 * Process-wide view of "a download queue is running", plus the one call that starts a
 * single track from anywhere in the app.
 *
 * The download service keeps ONE queue and reports progress by position in it. The screen
 * that submitted the queue maps those positions back onto its own track objects, so a
 * second queue started from a different screen would hand the first screen indices that
 * name the wrong songs. One flag, checked before starting, prevents that and gives the
 * player an honest message instead of a silently ignored tap.
 */
public final class DownloadState {

    /** True while a submitted queue has not finished yet. */
    public static volatile boolean running;

    private DownloadState() {
    }

    /**
     * Queues one track for download from outside the main screen.
     *
     * Only the JSON payload is sent: the service re-creates the track, writes the file and
     * reports through {@link ProgressBus}. The screen that happens to be open needs no row
     * for it - the saved song simply appears in the library when the queue ends.
     *
     * @return true when the request was accepted, false when one is already running.
     */
    public static boolean download(Context context, Track track, String album) {
        if (track == null) return false;
        if (running) {
            Toast.makeText(context, "A download is already running",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        JSONArray arr = new JSONArray();
        try {
            arr.put(track.toJson());
        } catch (Exception e) {
            Toast.makeText(context, "Could not queue this track", Toast.LENGTH_SHORT).show();
            return false;
        }
        running = true;
        Intent intent = new Intent(context, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_TRACKS, arr.toString())
                .putExtra(DownloadService.EXTRA_ALBUM, album == null ? "Cartify" : album)
                .putExtra(DownloadService.EXTRA_TOTAL, 1);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable e) {
            running = false;
            Toast.makeText(context, "Could not start the downloader", Toast.LENGTH_SHORT).show();
            return false;
        }
        Toast.makeText(context, "Downloading " + track.title, Toast.LENGTH_SHORT).show();
        return true;
    }
}
