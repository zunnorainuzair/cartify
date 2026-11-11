package com.cartify.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.util.Locale;

/**
 * Keeps playback alive in the background and drives the system media controls.
 *
 * The notification carries the same transport the in-app player does: previous, play or
 * pause, next in the compact view, with shuffle and repeat in the expanded view. Its
 * progress line and elapsed clock are redrawn every tick so the shade stays accurate
 * while a track is playing.
 */
public class PlaybackService extends Service implements Playback.Listener {

    public static final String ACTION_SYNC = "com.cartify.app.PLAYBACK_SYNC";
    public static final String ACTION_TOGGLE = "com.cartify.app.PLAYBACK_TOGGLE";
    public static final String ACTION_NEXT = "com.cartify.app.PLAYBACK_NEXT";
    public static final String ACTION_PREV = "com.cartify.app.PLAYBACK_PREV";
    public static final String ACTION_SHUFFLE = "com.cartify.app.PLAYBACK_SHUFFLE";
    public static final String ACTION_REPEAT = "com.cartify.app.PLAYBACK_REPEAT";
    public static final String ACTION_SEEK = "com.cartify.app.PLAYBACK_SEEK";
    /** Jump backwards or forwards by {@link #SEEK_STEP_MS} from the notification. */
    public static final String ACTION_SEEK_BACK = "com.cartify.app.PLAYBACK_SEEK_BACK";
    public static final String ACTION_SEEK_FORWARD = "com.cartify.app.PLAYBACK_SEEK_FORWARD";
    /** 10 seconds: the step every podcast and music player uses for a nudge. */
    public static final long SEEK_STEP_MS = 10_000L;
    public static final String EXTRA_POSITION = "position";

    private static final String CHANNEL_ID = "cartify_playback";
    private static final int NOTIFICATION_ID = 4212;
    private static final long TICK_MS = 1000L;

    private Playback playback;
    private MediaSessionCompat session;
    private NotificationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean foreground;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (playback != null && playback.isPlaying()) {
                pushState();
                updateNotification();
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        playback = Playback.get(this);
        manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        playback.addListener(this);
        session = new MediaSessionCompat(this, "Cartify");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (!playback.isPlaying()) playback.toggle();
            }

            @Override
            public void onPause() {
                playback.pause();
            }

            @Override
            public void onSkipToNext() {
                playback.next(false);
            }

            @Override
            public void onSkipToPrevious() {
                playback.previous();
            }

            @Override
            public void onSeekTo(long pos) {
                playback.seekTo(pos);
                onPlaybackChanged();
            }

            @Override
            public void onSetShuffleMode(int mode) {
                playback.setShuffle(mode != PlaybackStateCompat.SHUFFLE_MODE_NONE);
            }

            @Override
            public void onSetRepeatMode(int mode) {
                int target = mapRepeat(mode);
                for (int guard = 0; guard < 3 && playback.repeatMode() != target; guard++) {
                    playback.cycleRepeat();
                }
            }
        });
        session.setActive(true);
        main.post(ticker);
    }

    /** Maps a session repeat mode onto Playback's 0/1/2 cycle. */
    private static int mapRepeat(int mode) {
        if (mode == PlaybackStateCompat.REPEAT_MODE_ONE) return Playback.REPEAT_ONE;
        if (mode == PlaybackStateCompat.REPEAT_MODE_ALL) return Playback.REPEAT_ALL;
        return Playback.REPEAT_OFF;
    }

    private static int sessionRepeat(int repeat) {
        if (repeat == Playback.REPEAT_ONE) return PlaybackStateCompat.REPEAT_MODE_ONE;
        if (repeat == Playback.REPEAT_ALL) return PlaybackStateCompat.REPEAT_MODE_ALL;
        return PlaybackStateCompat.REPEAT_MODE_NONE;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_TOGGLE:
                    playback.toggle();
                    break;
                case ACTION_NEXT:
                    playback.next(false);
                    break;
                case ACTION_PREV:
                    playback.previous();
                    break;
                case ACTION_SHUFFLE:
                    playback.setShuffle(!playback.isShuffle());
                    break;
                case ACTION_REPEAT:
                    playback.cycleRepeat();
                    break;
                case ACTION_SEEK:
                    playback.seekTo(intent.getLongExtra(EXTRA_POSITION, 0));
                    break;
                case ACTION_SEEK_BACK:
                    nudge(-SEEK_STEP_MS);
                    break;
                case ACTION_SEEK_FORWARD:
                    nudge(SEEK_STEP_MS);
                    break;
                default:
                    break;
            }
        }
        Notification n = build();
        if (!foreground) {
            startForeground(NOTIFICATION_ID, n);
            foreground = true;
        } else if (manager != null) {
            manager.notify(NOTIFICATION_ID, n);
        }
        pushState();
        return START_NOT_STICKY;
    }

    /**
     * Moves playback by {@code deltaMs}, clamped inside the track.
     *
     * Seeking straight to a negative position throws on some MediaPlayer builds and a
     * position past the end simply stops the song, so both ends are clamped here rather
     * than leaving the rounding to the player.
     */
    private void nudge(long deltaMs) {
        long dur = playback.duration();
        long target = playback.position() + deltaMs;
        if (target < 0) target = 0;
        if (dur > 0 && target > dur - 500L) target = Math.max(0, dur - 500L);
        playback.seekTo(target);
        onPlaybackChanged();
    }

    // ------------------------------------------------------------------ state

    @Override
    public void onPlaybackChanged() {
        pushState();
        if (manager != null) manager.notify(NOTIFICATION_ID, build());
    }

    @Override
    public void onQueueChanged() {
        pushState();
        if (manager != null) manager.notify(NOTIFICATION_ID, build());
    }

    private void pushState() {
        if (session == null || playback == null) return;
        Track t = playback.current();
        long pos = playback.position();
        long dur = playback.duration();
        int state = playback.isPlaying() ? PlaybackStateCompat.STATE_PLAYING
                : (t == null ? PlaybackStateCompat.STATE_STOPPED : PlaybackStateCompat.STATE_PAUSED);
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
                        | PlaybackStateCompat.ACTION_SET_REPEAT_MODE)
                .setState(state, pos, playback.isPlaying() ? 1f : 0f)
                .setBufferedPosition(dur)
                .build());
        session.setShuffleMode(playback.isShuffle()
                ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                : PlaybackStateCompat.SHUFFLE_MODE_NONE);
        session.setRepeatMode(sessionRepeat(playback.repeatMode()));
        if (t != null) {
            MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur);
            Bitmap art = Art.cached(t);
            if (art != null) md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(md.build());
        }
    }

    private void updateNotification() {
        if (manager != null && foreground) manager.notify(NOTIFICATION_ID, build());
    }

    // ------------------------------------------------------------------ notification

    private Notification build() {
        Track t = playback.current();
        boolean playing = playback.isPlaying();
        String title = t == null ? "Nothing playing" : t.title;
        long dur = playback.duration();
        long pos = playback.position();

        String artist = (t == null || t.artist == null || t.artist.isEmpty())
                ? "Cartify" : t.artist;
        String sub = (t != null && dur > 0)
                ? artist + "  \u00b7  " + clock(pos) + " / " + clock(dur)
                : artist;

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | pendingFlag());
        PendingIntent toggle = action(ACTION_TOGGLE, 1);
        PendingIntent next = action(ACTION_NEXT, 2);
        PendingIntent prev = action(ACTION_PREV, 3);
        PendingIntent shuffle = action(ACTION_SHUFFLE, 4);
        PendingIntent repeat = action(ACTION_REPEAT, 5);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(sub)
                .setContentIntent(open)
                .setOngoing(playing)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(R.drawable.ic_prev, "Previous", prev)
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "Pause" : "Play", toggle)
                .addAction(R.drawable.ic_next, "Next", next);

        // Shuffle and repeat live in the expanded view; the compact view keeps the three
        // transport buttons, matching the in-app player's primary row.
        b.addAction(R.drawable.ic_shuffle,
                playback.isShuffle() ? "Shuffle on" : "Shuffle off", shuffle);
        int repeatMode = playback.repeatMode();
        if (repeatMode == Playback.REPEAT_ONE) {
            b.addAction(R.drawable.ic_repeat_one, "Repeat one", repeat);
        } else {
            b.addAction(R.drawable.ic_repeat,
                    repeatMode == Playback.REPEAT_ALL ? "Repeat all" : "Repeat off", repeat);
        }
        // Ten-second nudges in the expanded view. The compact three stay transport only,
        // because scrubbing from the shade is what the progress bar is for. The circular
        // arrow icon is deliberate: reusing the prev/next chevrons here read as "another
        // track" rather than "ten seconds", which is the one thing a seek control must not do.
        b.addAction(R.drawable.ic_rewind_10, "Back 10s", action(ACTION_SEEK_BACK, 6));
        b.addAction(R.drawable.ic_forward_10, "Forward 10s", action(ACTION_SEEK_FORWARD, 7));

        Bitmap art = t == null ? null : Art.cached(t);
        if (art != null) b.setLargeIcon(art);
        if (t != null && dur > 0) b.setProgress((int) dur, (int) pos, !playing);

        b.setStyle(new MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private static String clock(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    private PendingIntent action(String name, int code) {
        return PendingIntent.getService(this, code,
                new Intent(this, PlaybackService.class).setAction(name),
                PendingIntent.FLAG_UPDATE_CURRENT | pendingFlag());
    }

    private static int pendingFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Playback",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            manager.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(ticker);
        playback.removeListener(this);
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        super.onDestroy();
    }
}
