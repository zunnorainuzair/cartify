package com.cartify.app;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cartify home: paste a Spotify link, download, browse the library, play. */
public class MainActivity extends AppCompatActivity implements ProgressBus.Listener, Playback.Listener {

    private static final int REQ_NOTIFICATIONS = 91;
    private static final int REQ_DELETE = 92;
    /** A bulk delete that has to go through the system consent dialog. */
    private static final int REQ_DELETE_BULK = 93;

    private Track pendingDeleteTrack;
    private String pendingDeleteUri = "";
    /** Uris waiting on the Android 11+ delete consent dialog for a multi-row delete. */
    private final List<String> pendingBulkDelete = new ArrayList<>();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText linkInput;
    private ImageButton clearButton;
    private Button resolveButton;
    private TextView statusText;
    private RecyclerView list;
    private RecyclerView recRail;
    private CardAdapter cardAdapter;
    private PlaylistResultAdapter playlistRailAdapter;
    private TextView playlistHeader;
    private androidx.recyclerview.widget.RecyclerView playlistRail;
    /** Last playlist search result, kept so opening one does not re-hit the network. */
    private final java.util.List<PlaylistResult> playlistResults = new java.util.ArrayList<>();
    private TextView recHeader;
    /**
     * The header + rail as one unit, revealed by scrolling the results.
     *
     * The shelf used to be always visible and always at the top, so on a search the first
     * thing the user saw was a rail of other songs and the rows they actually searched for
     * started below the fold. It now stays out of the way until the list is scrolled, and
     * then fades in together with its label.
     */
    private View shelfBlock;
    /** True when the current collection has a shelf worth showing. */
    private boolean shelfAvailable;
    private LinearLayout emptyState;

    private LinearLayout homeView;
    private LinearLayout libraryView;
    private LinearLayout playlistsView;
    private LinearLayout downloadsView;
    private RecyclerView downloadsList;
    private TextView downloadsStatus;
    private LinearLayout downloadsEmpty;
    private TextView downloadsClear;
    private TrackAdapter downloadsAdapter;
    /**
     * Tracks handed to the service, in queue order.
     *
     * Progress callbacks carry a queue index, not a position in any adapter. Keeping the
     * submitted order means a tick can be mapped back to the exact Track object it
     * belongs to instead of being applied to whatever row happens to sit at that index.
     */
    private final List<Track> submitted = new ArrayList<>();
    private TextView playlistsStatus;
    /** Which order the playlist shelf is in; see {@link #sortPlaylists}. */
    private int playlistsSortMode = 0;
    private TextView playlistsSort;
    private RecyclerView playlistsList;
    private LinearLayout playlistsEmpty;
    private PlaylistAdapter playlistAdapter;
    private TextView libraryStatus;
    private DragRecyclerView songsList;
    private LinearLayout libraryEmpty;
    private Button grantAccessButton;
    private TextView cancelAllButton;
    private TextView shuffleAllButton;
    private EditText librarySearch;

    /** Every saved song, before the library search filter is applied. */
    private final List<Track> libraryAll = new ArrayList<>();
    private String libraryQuery = "";

    /** Library ordering: 0 = newest first, 1 = title, 2 = artist. */
    private int librarySort = 0;
    /** Whether the library shows artist section headers. */
    private boolean libraryGrouped = false;

    /**
     * First visible row per tab.
     *
     * Switching to Playlists and back used to drop the reader at the top of a
     * thousand-song library, which is the difference between the tab bar being
     * useful and being something you avoid touching. Keyed by tab, so each keeps
     * its own place. A download landing in the list can shift indices; the saved
     * value is clamped on restore, so a stale index lands somewhere sensible
     * instead of crashing.
     */
    private final int[] lastScroll = new int[4];
    private TextView librarySortButton;

    /** Bulk-selection chrome, shown in place of the library header while songs are picked. */
    private View selectionBar;
    private TextView selectionCount;
    private LinearLayout libraryHeader;

    /** Transient message row shared by every tab; see {@link #showSnack}. */
    private View snackBar;
    private TextView snackText;
    private TextView snackAction;

    private View miniPlayer;
    private ImageView miniArt;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlayPause;
    private ProgressBar miniProgress;
    private LinearLayout navHome;
    private LinearLayout navLibrary;
    private ImageView navHomeIcon;
    private TextView navHomeLabel;
    private ImageView navLibraryIcon;
    private TextView navLibraryLabel;
    private LinearLayout navPlaylists;
    private ImageView navPlaylistsIcon;
    private TextView navPlaylistsLabel;
    private LinearLayout navDownloads;
    private ImageView navDownloadsIcon;
    private TextView navDownloadsLabel;

    private TrackAdapter adapter;
    private SongAdapter songAdapter;
    private SpotifyCollection collection;
    private Playback playback;

    /** The launch animation runs once per process, not on every return from the player. */
    /**
     * Whether the intro has already played for the current foreground session.
     *
     * This used to be a process-wide latch set once and never cleared, so the
     * animation played exactly once per process and never again - reopening the app
     * from Recents, or returning after it had been in the background, showed no
     * intro at all. It is now reset whenever the app leaves the foreground.
     */
    private boolean splashShown;
    /** Unused placeholder kept for source compatibility. */
    private SplashView splash;

    private final Runnable miniTicker = new Runnable() {
        @Override
        public void run() {
            updateMiniProgress();
            main.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        playback = Playback.get(this);
        bindViews();
        wireActions();
        setupLists();

        // Spotify throttles a device that resolves links in bursts. When it does, the
        // status line says so and counts down instead of the button appearing to do
        // nothing while the resolver sleeps.
        SpotifyResolver.setWaiter(seconds -> main.post(() -> {
            // Only while a link is actually being read. The resolver also runs for
            // artwork and artist lookups in the background, and those must not
            // overwrite the status line of a search that is already showing results.
            if (!isFinishing() && resolvingLink) {
                statusText.setText("Spotify is busy \u2014 retrying in " + seconds + "s");
            }
        }));
        ProgressBus.register(this);
        // A download may have started before this activity existed (or kept running while
        // the app was in the background), so pick up its live state immediately.
        ProgressBus.replay(this);
        // A queue that outlived the previous screen keeps its cancel button and its
        // "already running" guard on the way back in.
        if (ProgressBus.isRunning()) {
            queueRunning = true;
            cancelAllButton.setText("Cancel all");
            cancelAllButton.setOnClickListener(v -> cancelAllDownloads());
            cancelAllButton.setVisibility(View.VISIBLE);
        }
        playback.addListener(this);
        warmUpDownloader();
        requestNotificationPermission();
        handleShareIntent(getIntent());
        refreshLibrary();
        renderMini();
        main.post(miniTicker);
        // playIntro() is driven from onStart, which also runs on a cold launch.
        checkForUpdates();
        showGreeting();
        // Recently played first, chart only as a fallback. Both fill the same rows
        // and whichever runs first wins, so this order is the decision.
        loadRecentlyPlayed();
    }

    // ---------------------------------------------------------------- updates

    /** The release the banner is offering; null when nothing is pending. */
    private Updater.Release pendingUpdate;
    /** The APK once it has landed, so the banner can install it without a re-download. */
    private java.io.File downloadedUpdate;
    private boolean updateDownloading;
    /** True while the system install-unknown-apps page is open on our behalf. */
    private boolean awaitingInstallPermission;
    private View updateBanner;
    private TextView updateBannerTitle;
    private TextView updateBannerSub;
    private TextView updateBannerAction;
    /** The pulsing dot on the banner; cancelled when the banner hides. */
    private AnimatorSet updatePulse;

    /**
     * Looks for a newer GitHub release and offers it.
     *
     * Runs after the intro so it never competes with the first paint, and stays silent for
     * the common case: no network, no release, or a release the user has already dismissed.
     */
    private void checkForUpdates() {
        Updater.check(this, false, release -> {
            if (release == null || isFinishing()) return;
            // An APK that already finished downloading is worth surfacing even if the
            // user dismissed the banner earlier: the bytes are on disk and the only
            // thing left is the install.
            java.io.File ready = Updater.downloadedApk(this, release.tag);
            if (ready != null) {
                pendingUpdate = release;
                downloadedUpdate = ready;
                showUpdateBanner(release);
                if (updateBannerTitle != null) {
                    updateBannerTitle.setText("Update ready \u00b7 " + release.tag);
                }
                if (updateBannerSub != null) {
                    updateBannerSub.setText("Downloaded \u2014 tap to install");
                }
                if (updateBannerAction != null) updateBannerAction.setText("Install");
                return;
            }
            if (Updater.alreadyOffered(this, release.tag)) return;
            showUpdateBanner(release);
        });
    }

    /**
     * App-wide actions, reachable without opening a song first.
     *
     * Everything here was previously buried: the update check only existed inside a
     * single song's overflow menu, and there was no way at all to see how much space the
     * library occupies or how many songs are on the device.
     */
    private void showAppMenu(View anchor) {
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_download, "Check for updates",
                this::checkForUpdatesManually));
        items.add(new MenuSheet.Item(R.drawable.ic_info, "Library info",
                this::showLibraryInfo));
        items.add(new MenuSheet.Item(R.drawable.ic_library, "Rescan music folder",
                () -> {
                    refreshLibrary();
                    toast("Rescanning Music/Cartify");
                }));
        items.add(new MenuSheet.Item(R.drawable.ic_music_note,
                "Download quality \u00b7 " + DownloadQuality.labelFor(DownloadQuality.get(this)),
                this::showQualityPicker));
        if (!playback.queue().isEmpty()) {
            items.add(new MenuSheet.Item(R.drawable.ic_close, "Clear play queue", () -> {
                playback.clearQueue();
                toast("Queue cleared");
            }));
        }
        MenuSheet.show(this, "Cartify", "Version " + appVersionName(),
                items.toArray(new MenuSheet.Item[0]));
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * How many songs are saved and how much room they take.
     *
     * The size is summed on a worker thread: a large library is thousands of stat calls,
     * and doing that on the main thread is exactly the kind of thing that produces a
     * visible freeze for no reason.
     */
    /**
     * How much time has actually been listened to on this device.
     *
     * Derived from the play history rather than from wall-clock time in the app: opening
     * the app and reading the list is not listening. Sums the known durations of the last
     * dozen songs, which is the honest order of magnitude without keeping a timer running.
     */
    private String listeningTimeReport() {
        try {
            long ms = 0;
            for (String uri : Recent.all(this)) {
                for (Track t : libraryAll) {
                    if (uri.equals(t.localUri)) {
                        ms += Math.max(0, t.durationMs);
                        break;
                    }
                }
            }
            if (ms <= 0) return "";
            long minutes = ms / 60000L;
            if (minutes < 60) return " \u00b7 about " + minutes + " min listened";
            return " \u00b7 about " + (minutes / 60) + " h " + (minutes % 60) + " min listened";
        } catch (Exception e) {
            return "";
        }
    }

    private void showLibraryInfo() {
        final int count = libraryAll.size();
        io.execute(() -> {
            long bytes = 0L;
            for (Track t : new ArrayList<>(libraryAll)) {
                if (t.localUri == null || t.localUri.isEmpty()) continue;
                try (android.content.res.AssetFileDescriptor fd =
                             getContentResolver().openAssetFileDescriptor(
                                     android.net.Uri.parse(t.localUri), "r")) {
                    if (fd != null) bytes += Math.max(0L, fd.getLength());
                } catch (Exception ignored) {
                }
            }
            final long total = bytes;
            main.post(() -> {
                String size = total >= 1024L * 1024L * 1024L
                        ? String.format(Locale.US, "%.1f GB", total / (1024.0 * 1024 * 1024))
                        : String.format(Locale.US, "%.0f MB", total / (1024.0 * 1024));
                MenuSheet.show(this, "Library info",
                        count + (count == 1 ? " song" : " songs") + " \u00b7 " + size
                                + " in Music/Cartify" + listeningTimeReport(),
                        new MenuSheet.Item(R.drawable.ic_close, "Close", () -> {
                        }));
            });
        });
    }

    /**
     * Lets the user choose the MP3 quality used by the next download.
     *
     * Every level is one tap and the menu closes immediately: a settings screen would be
     * more machinery than five exclusive options warrant, and the current choice is already
     * shown in the subtitle.
     */
    private void showQualityPicker() {
        String current = DownloadQuality.get(this);
        List<MenuSheet.Item> items = new ArrayList<>();
        for (DownloadQuality.Level level : DownloadQuality.LEVELS) {
            boolean picked = level.id.equals(current);
            items.add(new MenuSheet.Item(R.drawable.ic_music_note,
                    level.label + (picked ? "  \u00b7  current" : ""),
                    () -> {
                        DownloadQuality.set(this, level.id);
                        toast("Downloads will be " + level.label);
                    }));
        }
        MenuSheet.show(this, "Download quality",
                DownloadQuality.labelFor(current) + " \u00b7 applies to new downloads",
                items.toArray(new MenuSheet.Item[0]));
    }

    /**
     * The user asked for an update check explicitly.
     *
     * The automatic check is deliberately silent, which is right on launch but wrong when
     * someone taps the item: saying nothing looks broken. This one always answers, either
     * with the update sheet or with "you are up to date".
     */
    private void checkForUpdatesManually() {
        toast("Checking for updates\u2026");
        Updater.checkWithStatus(this, true, (release, status) -> {
            if (isFinishing()) return;
            if (release != null) {
                // A manual check ignores the "already offered" flag: asking again is a
                // request to see it again.
                showUpdateSheet(release);
                return;
            }
            // Silence after an explicit tap reads as a broken button, so every outcome
            // says something. The automatic check stays quiet; this one does not.
            switch (status) {
                case OFFLINE:
                    toast("No connection \u2014 could not reach GitHub");
                    break;
                case UNAVAILABLE:
                    toast("GitHub is not answering right now \u2014 try again later");
                    break;
                default:
                    toast("Cartify " + Updater.appVersionName(this) + " is up to date");
                    break;
            }
        });
    }

    /** Builds the "update available" sheet with the release notes and a download button. */
    private void showUpdateSheet(final Updater.Release release) {
        // The notes are markdown on GitHub; shown raw they put literal "##" and "**"
        // around every line, so they are flattened first.
        String notes = Updater.plainNotes(release.notes);
        if (notes.length() > 600) notes = notes.substring(0, 600).trim() + "\u2026";
        String installed = "Installed " + Updater.appVersionName(this);
        String subtitle = installed + "  \u00b7  " + release.summary()
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        MenuSheet.show(this, "Update available \u00b7 " + release.tag, subtitle,
                new MenuSheet.Item(R.drawable.ic_download, "Download and install",
                        () -> startUpdate(release)),
                new MenuSheet.Item(R.drawable.ic_close, "Later",
                        () -> Updater.markOffered(this, release.tag)));
    }

    // ------------------------------------------------------------------ update banner

    /**
     * Wires the update banner that sits above every tab.
     *
     * The banner is the whole point of this screen's update path: the sheet it replaces was
     * only reachable from a menu, so a release that landed while the app was closed was
     * invisible unless the user went looking for it.
     */
    private void bindUpdateBanner() {
        updateBanner = findViewById(R.id.updateBanner);
        if (updateBanner == null) return;
        updateBannerTitle = findViewById(R.id.updateBannerTitle);
        updateBannerSub = findViewById(R.id.updateBannerSub);
        updateBannerAction = findViewById(R.id.updateBannerAction);
        updateBanner.setOnClickListener(v -> onUpdateBannerTapped());
        if (updateBannerAction != null) {
            updateBannerAction.setOnClickListener(v -> onUpdateBannerTapped());
        }
        View close = findViewById(R.id.updateBannerClose);
        if (close != null) close.setOnClickListener(v -> dismissUpdateBanner());
    }

    /** Shows the banner for a release, sliding it down from the top edge. */
    private void showUpdateBanner(final Updater.Release release) {
        if (updateBanner == null || release == null || isFinishing()) return;
        pendingUpdate = release;
        updateBannerTitle.setText("Update available \u00b7 " + release.tag);
        String notes = Updater.plainNotes(release.notes);
        int cut = notes.indexOf('\n');
        if (cut > 0) notes = notes.substring(0, cut).trim();
        if (notes.length() > 70) notes = notes.substring(0, 70).trim() + "\u2026";
        // The size is the one fact a user wants before starting a download on mobile
        // data, so it is shown even when the release has no notes.
        String size = Updater.formatSize(release.size);
        if (notes.isEmpty()) {
            updateBannerSub.setText(size.isEmpty() ? "Tap to update" : size + "  \u00b7  Tap to update");
        } else {
            updateBannerSub.setText(size.isEmpty() ? notes : notes + "  \u00b7  " + size);
        }
        updateBannerAction.setText("Update");

        if (updateBanner.getVisibility() != View.VISIBLE) {
            updateBanner.setVisibility(View.VISIBLE);
            updateBanner.setAlpha(0f);
            updateBanner.setTranslationY(-20f * getResources().getDisplayMetrics().density);
            updateBanner.animate().alpha(1f).translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        startUpdatePulse();
    }

    /** Hides the banner and remembers the dismissal for this release only. */
    private void dismissUpdateBanner() {
        if (updateBanner == null) return;
        stopUpdatePulse();
        if (pendingUpdate != null && !updateDownloading) {
            Updater.markOffered(this, pendingUpdate.tag);
        }
        updateBanner.animate().alpha(0f).translationY(-20f * getResources().getDisplayMetrics().density)
                .setDuration(180)
                .withEndAction(() -> {
                    if (updateBanner == null) return;
                    updateBanner.setVisibility(View.GONE);
                    updateBanner.setAlpha(1f);
                    updateBanner.setTranslationY(0f);
                })
                .start();
    }

    /**
     * Pulses the banner dot.
     *
     * A static badge is easy to read past on a screen that already has a progress bar and a
     * mini player animating on it; a slow breath on the dot is what makes it register as
     * "something is waiting for you".
     */
    private void startUpdatePulse() {
        View dot = findViewById(R.id.updateDot);
        if (dot == null) return;
        stopUpdatePulse();
        ObjectAnimator fade = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f);
        ObjectAnimator grow = ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 1.6f);
        ObjectAnimator growY = ObjectAnimator.ofFloat(dot, View.SCALE_Y, 1f, 1.6f);
        for (ObjectAnimator a : new ObjectAnimator[]{fade, grow, growY}) {
            a.setDuration(850);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        }
        updatePulse = new AnimatorSet();
        updatePulse.playTogether(fade, grow, growY);
        updatePulse.start();
    }

    private void stopUpdatePulse() {
        if (updatePulse != null) {
            updatePulse.cancel();
            updatePulse = null;
        }
        View dot = findViewById(R.id.updateDot);
        if (dot != null) {
            dot.setAlpha(1f);
            dot.setScaleX(1f);
            dot.setScaleY(1f);
        }
    }

    /** A tap anywhere on the banner either installs what has landed or asks to start. */
    private void onUpdateBannerTapped() {
        if (downloadedUpdate != null && downloadedUpdate.exists()) {
            if (!Updater.install(this, downloadedUpdate)) {
                awaitingInstallPermission = true;
            }
            return;
        }
        if (updateDownloading) {
            // A download that cannot be stopped is a download the user cannot get out
            // of; the partial file survives, so cancelling costs nothing.
            MenuSheet.show(this, "Downloading update",
                    "The download continues in the background. Cancelling keeps what has "
                            + "already arrived, so tapping Update again resumes it.",
                    new MenuSheet.Item(R.drawable.ic_close, "Cancel download", () -> {
                        Updater.cancelDownload();
                        toast("Update cancelled");
                    }),
                    new MenuSheet.Item(R.drawable.ic_download, "Keep downloading", () -> {
                    }));
            return;
        }
        confirmUpdate();
    }

    /**
     * Asks before doing anything irreversible.
     *
     * The download and the install both run without further taps, but the consent to let
     * the app replace itself is the user's to give, so it is asked once here in the app's
     * own sheet rather than as a surprise system dialog.
     */
    private void confirmUpdate() {
        if (pendingUpdate == null) {
            checkForUpdatesManually();
            return;
        }
        final Updater.Release release = pendingUpdate;
        MenuSheet.show(this, "Update to " + release.tag,
                "Cartify downloads the update in the background and installs it. "
                        + "Android asks once for permission to install.",
                new MenuSheet.Item(R.drawable.ic_download, "Allow and update",
                        () -> startUpdate(release)),
                new MenuSheet.Item(R.drawable.ic_close, "Not now", () -> {
                }));
    }

    /**
     * Starts the background update, asking for install permission first.
     *
     * Android 8+ will not let the app hand an APK to the installer until the user allows
     * that app to install unknown apps. Asking up front means the download is not wasted
     * behind a dialog the user then cancels.
     */
    private void startUpdate(final Updater.Release release) {
        if (release == null || isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            awaitingInstallPermission = true;
            toast("Allow Cartify to install apps, then the update continues");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                awaitingInstallPermission = false;
                toast("Could not open the install settings");
            }
            return;
        }
        awaitingInstallPermission = false;
        downloadUpdate(release);
    }

    /** Downloads the release APK in the background, driving the banner, then installs it. */
    private void downloadUpdate(final Updater.Release release) {
        if (release == null || isFinishing()) return;
        updateDownloading = true;
        downloadedUpdate = null;
        if (updateBanner != null) {
            updateBannerTitle.setText("Downloading update \u00b7 " + release.tag);
            updateBannerSub.setText("Starting\u2026");
            updateBannerAction.setText("0%");
        }
        statusText.setText("Downloading update\u2026");
        Updater.download(this, release, new Updater.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                if (isFinishing()) return;
                if (updateBanner != null) {
                    updateBannerSub.setText("Downloading in the background\u2026");
                    updateBannerAction.setText(percent + "%");
                }
                statusText.setText("Downloading update\u2026 " + percent + "%");
            }

            @Override
            public void onReady(java.io.File apk) {
                updateDownloading = false;
                downloadedUpdate = apk;
                if (isFinishing()) return;
                if (updateBanner != null) {
                    updateBannerTitle.setText("Update ready \u00b7 " + release.tag);
                    updateBannerSub.setText("Installing\u2026");
                    updateBannerAction.setText("Install");
                }
                statusText.setText("Update downloaded");
                // Straight to the installer: no second tap between download and install.
                // If the install permission is still missing, install() opens the settings
                // page and returns false; onResume then installs this same file instead of
                // downloading it again.
                if (!Updater.install(MainActivity.this, apk)) {
                    awaitingInstallPermission = true;
                    if (updateBanner != null) {
                        updateBannerSub.setText("Allow installs, then tap Install");
                    }
                }
            }

            @Override
            public void onFailed(String message) {
                updateDownloading = false;
                if (isFinishing()) return;
                if ("cancelled".equals(message)) {
                    // A cancel is not a failure: the partial file is kept, so the next
                    // attempt resumes from where this one stopped.
                    if (updateBanner != null) {
                        updateBannerTitle.setText("Update available \u00b7 " + release.tag);
                        updateBannerSub.setText("Download cancelled \u2014 tap to resume");
                        updateBannerAction.setText("Update");
                    }
                    statusText.setText("Update cancelled");
                    return;
                }
                if (updateBanner != null) {
                    updateBannerTitle.setText("Update failed");
                    updateBannerSub.setText(message == null ? "Tap to retry" : message);
                    updateBannerAction.setText("Retry");
                }
                statusText.setText("Update failed: " + message);
                toast("Could not download the update");
            }
        });
    }

    /**
     * Plays the logo morph once, on a cold open.
     *
     * The activity is fully built underneath the splash, so dismissing it reveals a
     * ready screen instead of a half-inflated one.
     */
    private void playIntro() {
        splash = findViewById(R.id.splash);
        if (splash == null) return;
        if (splashShown) {
            splash.setVisibility(View.GONE);
            return;
        }
        splashShown = true;
        // The intro is a full-bleed black beat, matching the black field of the logo
        // itself. The window normally wears the surface green, so both system bars are
        // held black for the duration and handed back in onFinished.
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        // Always from frame zero: a replay must not resume wherever the last run stopped.
        splash.reset();
        splash.setVisibility(View.VISIBLE);
        splash.setAlpha(1f);
        // The intro is a visual beat only; no startup sound is played.
        splash.setListener(new SplashView.Listener() {
            @Override
            public void onBrandShown() {
                // The word is being inked in right now, so the sound lands with it.
                // Silent on purpose: the brand beat replaced the chime.
            }

            @Override
            public void onFinished() {
                splash.animate()
                        .alpha(0f)
                        .setDuration(320)
                        .withEndAction(() -> {
                            splash.setVisibility(View.GONE);

                            // The bars return to the theme colour now the black beat is over.
                            getWindow().setStatusBarColor(
                                    getResources().getColor(R.color.surface));
                            getWindow().setNavigationBarColor(
                                    getResources().getColor(R.color.surface));

                            // The content settles in as the splash lifts, so the
                            // transition reads as one motion rather than a cut.
                            View root = findViewById(R.id.bottomBar);
                            if (root != null) {
                                root.setTranslationY(24f * getResources().getDisplayMetrics().density);
                                root.setAlpha(0f);
                                root.animate().translationY(0f).alpha(1f).setDuration(320).start();
                            }
                        })
                        .start();
            }
        });
        splash.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    private void bindViews() {
        bindUpdateBanner();
        linkInput = findViewById(R.id.linkInput);
        clearButton = findViewById(R.id.clearButton);
        resolveButton = findViewById(R.id.resolveButton);
        statusText = findViewById(R.id.statusText);
        list = findViewById(R.id.list);
        recRail = findViewById(R.id.recRail);
        recHeader = findViewById(R.id.recHeader);
        shelfBlock = findViewById(R.id.shelfBlock);
        if (recRail != null) {
            // A horizontal rail needs a horizontal layout manager; the default vertical one
            // would stack the cards down the page and break the shelf entirely.
            recRail.setLayoutManager(new LinearLayoutManager(this,
                    LinearLayoutManager.HORIZONTAL, false));
            cardAdapter = new CardAdapter(track -> {
                List<Track> one = single(track);
                startDownload(one);
            });
            recRail.setAdapter(cardAdapter);
        }
        playlistRail = findViewById(R.id.playlistRail);
        playlistHeader = findViewById(R.id.playlistHeader);
        if (playlistRail != null) {
            playlistRail.setLayoutManager(new LinearLayoutManager(this,
                    LinearLayoutManager.HORIZONTAL, false));
            playlistRailAdapter = new PlaylistResultAdapter(new PlaylistResultAdapter.Actions() {
                @Override
                public void onOpen(PlaylistResult playlist) {
                    openPlaylist(playlist);
                }

                @Override
                public void onDownload(PlaylistResult playlist) {
                    downloadPlaylist(playlist);
                }
            });
            playlistRail.setAdapter(playlistRailAdapter);
        }
        // These two used to be bound INSIDE the recRail null-check above, so a layout
        // without the rail would have left them null and every later use would have thrown.
        // The shelf is a sibling ABOVE the results list, which is weighted to fill the rest
        // of the pane. That means every pixel of shelf is a pixel taken away from the list:
        // once it appeared, it squeezed the rows the user was reading and they had to keep
        // scrolling inside a shrunken window.
        //
        // It is now a "peek" that gets out of the way. Scrolling down folds it away and the
        // list immediately reclaims the full height; scrolling back to the top brings it
        // back. Collapsing (height -> 0) is used rather than alpha, because alpha hides a
        // view while it keeps occupying its layout slot, which is exactly the problem.
        // The shelf fades away continuously with the scroll, from the very first pixel.
        //
        // Two earlier attempts are worth knowing about, because both failed for the same
        // reason and neither is what is here now:
        //   1. toggling a block's height in onScrolled() - the height change altered whether
        //      the list could scroll up, which fired another callback with the opposite
        //      direction, which toggled it back. A held finger oscillated it many times a
        //      second and the pane strobed.
        //   2. doing the same decision only on SCROLL_STATE_IDLE - no oscillation, but it
        //      meant nothing happened until the finger lifted, so it could only pop in and
        //      out, and it took a full screen of scrolling to trigger at all.
        //
        // What is here now reacts to scroll POSITION, not to direction or to state changes.
        // The shelf's alpha, its offset and the list's top padding are all one monotonic
        // function of how far the list has been scrolled, so scrolling back up unwinds it
        // exactly and no feedback loop is possible: nothing here depends on which way the
        // finger is moving, only on where the list is.
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                applyShelfFade(rv.computeVerticalScrollOffset());
            }
        });
        emptyState = findViewById(R.id.emptyState);

        homeView = findViewById(R.id.homeView);
        libraryView = findViewById(R.id.libraryView);
        libraryStatus = findViewById(R.id.libraryStatus);
        songsList = (DragRecyclerView) findViewById(R.id.songsList);
        libraryEmpty = findViewById(R.id.libraryEmpty);
        grantAccessButton = findViewById(R.id.grantAccessButton);
        grantAccessButton.setOnClickListener(v -> requestAudioAccessOrSettings());
        cancelAllButton = findViewById(R.id.cancelAllButton);
        shuffleAllButton = findViewById(R.id.shuffleAllButton);
        librarySortButton = findViewById(R.id.librarySort);
        if (librarySortButton != null) {
            librarySortButton.setOnClickListener(v -> cycleLibrarySort());
            // Grouping only makes sense with the artist ordering, so turning it on
            // switches the sort with it instead of showing headers in a list sorted
            // by date, where every artist would repeat.
            librarySortButton.setOnLongClickListener(v -> {
                setLibraryGrouped(!libraryGrouped);
                return true;
            });
        }
        View appMenu = findViewById(R.id.appMenu);
        if (appMenu != null) appMenu.setOnClickListener(this::showAppMenu);
        librarySearch = findViewById(R.id.librarySearch);

        libraryHeader = findViewById(R.id.libraryHeader);
        selectionBar = findViewById(R.id.selectionBar);
        selectionCount = findViewById(R.id.selectionCount);
        findViewById(R.id.selectionClose).setOnClickListener(v -> songAdapter.clearSelection());
        findViewById(R.id.selectionSelectAll).setOnClickListener(v -> {
            if (songAdapter.getItemCount() == 0) {
                toast("Nothing to select");
                return;
            }
            songAdapter.selectAll();
        });
        findViewById(R.id.selectionPlaylist).setOnClickListener(v -> addSelectedToPlaylist());
        findViewById(R.id.selectionQueue).setOnClickListener(v -> queueSelected());
        findViewById(R.id.selectionDelete).setOnClickListener(v -> confirmDeleteSelected());

        installBackHandling();

        snackBar = findViewById(R.id.snackBar);
        snackText = findViewById(R.id.snackText);
        snackAction = findViewById(R.id.snackAction);
        if (snackBar != null) snackBar.setOnClickListener(v -> hideSnack());

        miniPlayer = findViewById(R.id.miniPlayer);
        miniArt = findViewById(R.id.miniArt);
        miniTitle = findViewById(R.id.miniTitle);
        miniArtist = findViewById(R.id.miniArtist);
        miniPlayPause = findViewById(R.id.miniPlayPause);
        miniProgress = findViewById(R.id.miniProgress);

        navHome = findViewById(R.id.navHome);
        navLibrary = findViewById(R.id.navLibrary);
        navHomeIcon = (ImageView) ((LinearLayout) navHome).getChildAt(0);
        navHomeLabel = (TextView) ((LinearLayout) navHome).getChildAt(1);
        navLibraryIcon = (ImageView) ((LinearLayout) navLibrary).getChildAt(0);
        navLibraryLabel = (TextView) ((LinearLayout) navLibrary).getChildAt(1);
        navPlaylists = findViewById(R.id.navPlaylists);
        navPlaylistsIcon = (ImageView) ((LinearLayout) navPlaylists).getChildAt(0);
        navPlaylistsLabel = (TextView) ((LinearLayout) navPlaylists).getChildAt(1);
        navDownloads = findViewById(R.id.navDownloads);
        navDownloadsIcon = (ImageView) ((LinearLayout) navDownloads).getChildAt(0);
        navDownloadsLabel = (TextView) ((LinearLayout) navDownloads).getChildAt(1);

        playlistsView = findViewById(R.id.playlistsView);
        playlistsStatus = findViewById(R.id.playlistsStatus);
        playlistsSort = findViewById(R.id.playlistsSort);
        if (playlistsSort != null) {
            playlistsSort.setOnClickListener(v -> cyclePlaylistSort());
        }
        playlistsList = findViewById(R.id.playlistsList);
        playlistsEmpty = findViewById(R.id.playlistsEmpty);

        downloadsView = findViewById(R.id.downloadsView);
        downloadsList = findViewById(R.id.downloadsList);
        downloadsStatus = findViewById(R.id.downloadsStatus);
        downloadsEmpty = findViewById(R.id.downloadsEmpty);
        downloadsClear = findViewById(R.id.downloadsClear);

        statusText.setText("Ready \u2014 nothing loaded yet");
    }

    private void wireActions() {
        clearButton.setOnClickListener(v -> linkInput.setText(""));
        linkInput.addTextChangedListener(new SimpleWatcher(hasText ->
                clearButton.setVisibility(hasText ? View.VISIBLE : View.GONE)));

        resolveButton.setOnClickListener(v -> resolve());

        // Direct search: no link to copy, no browser round trip. Results become the same
        // Track rows a resolved Spotify link produces, so downloading is identical.
        final EditText searchInput = findViewById(R.id.searchInput);
        View searchGo = findViewById(R.id.searchGo);
        if (searchGo != null) searchGo.setOnClickListener(v -> runSearch(searchInput));
        // The clear affordance appears only once there is a query to clear, so the field
        // does not carry a dead control on an empty box.
        final View searchClear = findViewById(R.id.searchClear);
        if (searchClear != null) {
            searchClear.setOnClickListener(v -> {
                if (searchInput == null) return;
                searchInput.setText("");
                searchClear.animate().alpha(0f).setDuration(120).start();
            });
        }
        if (searchInput != null) {
            searchInput.addTextChangedListener(new SimpleWatcher(hasText -> {
                if (searchClear == null) return;
                searchClear.animate().cancel();
                searchClear.setAlpha(1f);
                searchClear.setVisibility(hasText ? View.VISIBLE : View.GONE);
            }));
            searchInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(searchInput);
                    return true;
                }
                return false;
            });
        }

        navHome.setOnClickListener(v -> showTab(TAB_HOME));
        navLibrary.setOnClickListener(v -> showTab(TAB_LIBRARY));
        navPlaylists.setOnClickListener(v -> showTab(TAB_PLAYLISTS));
        // The bar must agree with the pane on the FIRST frame, not after the first
        // tap. activeTab starts as TAB_HOME, so without this the bar shows whatever
        // the XML says - which is how the oval sat under Offline while the search
        // pane was the one on screen.
        syncNav();
        navDownloads.setOnClickListener(v -> showTab(TAB_DOWNLOADS));
        if (downloadsClear != null) downloadsClear.setOnClickListener(v -> clearFailedDownloads());
        findViewById(R.id.newPlaylistButton).setOnClickListener(v -> createPlaylist());
        findViewById(R.id.emptyNewPlaylist).setOnClickListener(v -> createPlaylist());


        cancelAllButton.setOnClickListener(v -> cancelAllDownloads());
        shuffleAllButton.setOnClickListener(v -> shuffleAll());
        librarySearch.addTextChangedListener(new SimpleWatcher(hasText -> {
            libraryQuery = librarySearch.getText().toString().trim().toLowerCase(Locale.US);
            applyLibraryFilter();
        }));

        findViewById(R.id.miniOpen).setOnClickListener(v -> openPlayer());
        miniPlayer.setOnClickListener(v -> openPlayer());
        miniPlayPause.setOnClickListener(v -> playback.toggle());
        findViewById(R.id.miniPrev).setOnClickListener(v -> playback.previous());
        findViewById(R.id.miniNext).setOnClickListener(v -> playback.next(false));
        findViewById(R.id.miniMore).setOnClickListener(this::showMiniMenu);
        // Saving the song that is playing, straight from the collapsed bar. It is the
        // same call the player's overflow makes, so a stream can be kept without
        // leaving the list or interrupting playback.
        findViewById(R.id.miniDownload).setOnClickListener(v -> downloadCurrentTrack());
    }

    /**
     * Queues the currently playing track for download.
     *
     * Deliberately does nothing to playback: the point is that the song keeps playing
     * from the network while its file is fetched, and the next time it starts it opens
     * from disk. Rows that already have a file are a no-op rather than an error.
     */
    private void downloadCurrentTrack() {
        Track t = playback.current();
        if (t == null) return;
        if (t.localUri != null && !t.localUri.isEmpty()) {
            toast("Already saved to your device");
            return;
        }
        boolean started = DownloadState.download(this, t,
                playback.source().isEmpty() ? "Cartify" : playback.source());
        if (started) renderMini();
    }

    private void setupLists() {
        adapter = new TrackAdapter(new TrackAdapter.Actions() {
            @Override
            public void onDownloadClicked(Track track, int index) {
                startDownload(single(track));
            }

            @Override
            public void onCancelClicked(Track track, int index) {
                cancelDownload(index);
            }

            @Override
            public void onPlayClicked(Track track, int index) {
                playFromList(index);
            }

            @Override
            public void onStreamClicked(Track track, int index) {
                streamFromList(index);
            }
        });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());

        songAdapter = new SongAdapter(new SongAdapter.Actions() {
            @Override
            public void onPlay(int index) {
                playLibraryFrom(index);
            }

            @Override
            public void onMore(View anchor, Track track) {
                showSongMenu(anchor, track);
            }

            @Override
            public void onLongPress(Track track) {
                songAdapter.beginSelection(track);
            }

            @Override
            public void onToggle(Track track) {
                songAdapter.toggle(track);
            }

            @Override
            public void onSelectionChanged(int count, boolean selecting) {
                renderSelection(count, selecting);
            }
        });
        songsList.setLayoutManager(new LinearLayoutManager(this));
        songsList.setAdapter(songAdapter);
        playlistAdapter = new PlaylistAdapter(new PlaylistAdapter.Actions() {
            @Override
            public void onOpen(Playlist playlist) {
                openPlaylist(playlist);
            }

            @Override
            public void onMore(View anchor, Playlist playlist) {
                showPlaylistMenu(playlist);
            }
        });
        playlistsList.setLayoutManager(new LinearLayoutManager(this));
        playlistsList.setAdapter(playlistAdapter);

        // The Downloads page reuses the same row renderer and the same Track objects, so a
        // cancel or retry there acts on the real queue rather than on a copy.
        downloadsAdapter = new TrackAdapter(new TrackAdapter.Actions() {
            @Override
            public void onDownloadClicked(Track track, int index) {
                startDownload(single(track));
            }

            @Override
            public void onCancelClicked(Track track, int index) {
                cancelSubmitted(track);
            }

            @Override
            public void onPlayClicked(Track track, int index) {
                playDownloadsFrom(index);
            }
        });
        downloadsList.setLayoutManager(new LinearLayoutManager(this));
        downloadsList.setAdapter(downloadsAdapter);
    }

    // ---------------------------------------------------------------- tabs

    private static final int TAB_HOME = 0;
    private static final int TAB_LIBRARY = 1;
    private static final int TAB_PLAYLISTS = 2;
    private static final int TAB_DOWNLOADS = 3;

    /** Library opens first: the songs you already own matter more than the paste box. */
    private int activeTab = TAB_HOME;


    /**
     * Back leaves selection mode before it leaves the screen.
     *
     * This has to go through the dispatcher, not {@code onBackPressed()}: the app targets
     * SDK 34, where back is routed through OnBackPressedDispatcher and the deprecated
     * override is never called. That is why a plain override left back exiting the screen
     * mid-selection instead of clearing it.
     */
    private void installBackHandling() {
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (songAdapter != null && songAdapter.isSelecting()) {
                            songAdapter.clearSelection();
                            return;
                        }
                        // Hand the press back to the system so the screen still closes.
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
    }

    /**
     * Tells a screen reader which tab just opened.
     *
     * The pill, the tint and the label weight all change on a tab switch, but none of that
     * is audible: to TalkBack the screen simply replaced its contents with no explanation.
     * Announcing the pane name is the smallest thing that makes the bar usable without sight.
     */
    private void announceTab(String name) {
        View root = findViewById(android.R.id.content);
        if (root != null) root.announceForAccessibility(name + " tab");
    }

    private void showTab(int tab) {
        rememberScroll();
        // Selection belongs to the library view; switching tabs drops it.
        if (songAdapter != null && songAdapter.isSelecting() && tab != TAB_LIBRARY) {
            songAdapter.clearSelection();
        }
        if (tab == TAB_PLAYLISTS) {
            showPlaylistsTab();
            announceTab("Playlists");
            return;
        }
        if (tab == TAB_DOWNLOADS) {
            showDownloadsTab();
            announceTab("Downloads");
            return;
        }
        activeTab = tab;
        boolean library = tab == TAB_LIBRARY;
        announceTab(library ? "Offline" : "Search");
        if (playlistsView != null) playlistsView.setVisibility(View.GONE);
        if (downloadsView != null) downloadsView.setVisibility(View.GONE);
        View incoming = library ? libraryView : homeView;
        View outgoing = library ? homeView : libraryView;

        // Cross-fade the two panes instead of a hard swap. The outgoing view is hidden
        // the moment it finishes fading so it never eats a touch on the way out.
        if (outgoing.getVisibility() == View.VISIBLE && incoming != outgoing) {
            outgoing.animate().cancel();
            outgoing.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                outgoing.setVisibility(View.GONE);
                outgoing.setAlpha(1f);
            }).start();
        } else {
            outgoing.setVisibility(View.GONE);
        }

        if (incoming.getVisibility() != View.VISIBLE) {
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(0f);
            incoming.setTranslationY(12f * getResources().getDisplayMetrics().density);
            incoming.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        if (library) refreshLibrary();
        restoreScroll(tab);
        syncNav();
    }

    /** Stores the scroll position of whatever tab is open right now. */
    private void rememberScroll() {
        if (activeTab < 0 || activeTab >= lastScroll.length) return;
        RecyclerView target = scrollTarget(activeTab);
        if (target == null) return;
        androidx.recyclerview.widget.RecyclerView.LayoutManager lm = target.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            lastScroll[activeTab] = ((LinearLayoutManager) lm)
                    .findFirstVisibleItemPosition();
        }
    }

    /**
     * Puts the list back where the user left it.
     *
     * Posted rather than called directly: the rows are submitted with a diff, so a
     * scrollToPosition during the same frame would run before the adapter has the
     * new items and would be discarded.
     */
    private void restoreScroll(int tab) {
        if (tab < 0 || tab >= lastScroll.length) return;
        final int pos = lastScroll[tab];
        final RecyclerView target = scrollTarget(tab);
        if (target == null) return;
        main.post(() -> {
            if (target.getAdapter() == null) return;
            int max = Math.max(0, target.getAdapter().getItemCount() - 1);
            target.scrollToPosition(Math.min(Math.max(0, pos), max));
        });
    }

    /**
     * Paints the bottom bar for the current tab.
     *
     * One routine for all three tabs means the pill, the tint and the label opacity can
     * never disagree about which tab is open.
     */
    private void syncNav() {
        styleNav(navHome, navHomeIcon, navHomeLabel, activeTab == TAB_HOME);
        styleNav(navLibrary, navLibraryIcon, navLibraryLabel, activeTab == TAB_LIBRARY);
        styleNav(navPlaylists, navPlaylistsIcon, navPlaylistsLabel, activeTab == TAB_PLAYLISTS);
        styleNav(navDownloads, navDownloadsIcon, navDownloadsLabel, activeTab == TAB_DOWNLOADS);
    }

    /** The list each tab scrolls, so scroll memory works without a nested ternary. */
    private RecyclerView scrollTarget(int tab) {
        if (tab == TAB_LIBRARY) return songsList;
        if (tab == TAB_PLAYLISTS) return playlistsList;
        if (tab == TAB_DOWNLOADS) return downloadsList;
        return list;
    }

    private void styleNav(LinearLayout pill, ImageView icon, TextView label, boolean active) {
        if (pill == null || icon == null || label == null) return;
        pill.setBackgroundResource(active ? R.drawable.bg_pill_ghost : 0);
        icon.setImageTintList(getColorStateList(active ? R.color.accent : R.color.text_secondary));
        label.setTextColor(getColor(active ? R.color.accent : R.color.text_secondary));
        label.animate().alpha(active ? 1f : 0.72f).setDuration(180).start();
    }

    /** The playlists pane: a shelf of playlists rather than a list of songs. */
    private void showPlaylistsTab() {
        rememberScroll();
        activeTab = TAB_PLAYLISTS;
        hideKeyboard();
        View[] panes = {homeView, libraryView, downloadsView};
        for (View pane : panes) {
            if (pane != null) {
                pane.animate().cancel();
                pane.setVisibility(View.GONE);
                pane.setAlpha(1f);
            }
        }
        playlistsView.setVisibility(View.VISIBLE);
        playlistsView.setAlpha(0f);
        playlistsView.setTranslationY(12f * getResources().getDisplayMetrics().density);
        playlistsView.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        refreshPlaylists();
        syncNav();
    }

    /** The downloads pane: only work that is queued, running or failed. */
    private void showDownloadsTab() {
        rememberScroll();
        activeTab = TAB_DOWNLOADS;
        hideKeyboard();
        View[] panes = {homeView, libraryView, playlistsView};
        for (View pane : panes) {
            if (pane != null) {
                pane.animate().cancel();
                pane.setVisibility(View.GONE);
                pane.setAlpha(1f);
            }
        }
        downloadsView.setVisibility(View.VISIBLE);
        downloadsView.setAlpha(0f);
        downloadsView.setTranslationY(12f * getResources().getDisplayMetrics().density);
        downloadsView.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        refreshDownloads();
        restoreScroll(TAB_DOWNLOADS);
        syncNav();
    }

    /**
     * Rebuilds the downloads rows from the submitted queue.
     *
     * The list is the submitted tracks filtered to unfinished work. Done rows leave the
     * page because a finished download belongs in the library, not in a live status view.
     */
    private void refreshDownloads() {
        if (downloadsAdapter == null) return;
        List<Track> shown = new ArrayList<>();
        int running = 0;
        int failed = 0;
        for (Track t : submitted) {
            if (t.status == Track.QUEUED || t.status == Track.ACTIVE) {
                shown.add(t);
                running++;
            } else if (t.status == Track.ERROR) {
                shown.add(t);
                failed++;
            }
        }
        downloadsAdapter.submit(shown);
        boolean empty = shown.isEmpty();
        if (downloadsEmpty != null) downloadsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (downloadsStatus != null) {
            if (empty) {
                downloadsStatus.setText("No active downloads");
            } else {
                String s = running + (running == 1 ? " downloading" : " downloading");
                if (failed > 0) s += " \u00b7 " + failed + " failed";
                downloadsStatus.setText(s);
            }
        }
        if (downloadsClear != null) {
            downloadsClear.setVisibility(failed > 0 ? View.VISIBLE : View.GONE);
        }
    }

    /** Drops failed rows from the page without touching their saved state. */
    private void clearFailedDownloads() {
        for (Track t : new ArrayList<>(submitted)) {
            if (t.status == Track.ERROR) submitted.remove(t);
        }
        refreshDownloads();
    }

    /**
     * Cancels the given track by translating it to its queue index.
     *
     * The service cancels by position in the list it was handed, so the index must be
     * looked up in the submitted order. Passing the adapter position would cancel a
     * different song whenever the two orders differ.
     */
    private void cancelSubmitted(Track track) {
        int index = submitted.indexOf(track);
        if (index < 0) return;
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_CANCEL_TRACK)
                .putExtra(DownloadService.EXTRA_INDEX, index);
        startService(intent);
        track.status = Track.IDLE;
        track.progress = 0;
        track.error = "";
        refreshDownloads();
        toast("Cancelled " + (track.title == null ? "track" : track.title));
    }

    /**
     * Plays a row from the Downloads page.
     *
     * The page only holds queued, running and failed rows, so a row that has finished is
     * not on it and the rows that are on it usually have no local file yet. The old code
     * looked only at those rows, found nothing playable, and answered every tap with
     * "Not downloaded yet" — even for a song that was already saved. It now falls back to
     * the library by title + artist, which is the same lookup the importer uses.
     */
    private void playDownloadsFrom(int index) {
        List<Track> items = downloadsAdapter.items();
        if (index < 0 || index >= items.size()) return;
        Track tapped = items.get(index);
        if (tapped.localUri == null || tapped.localUri.isEmpty()) {
            LibraryStore.Item saved = LibraryStore.findSaved(this, tapped.title, tapped.artist);
            if (saved == null) {
                toast("Not downloaded yet");
                return;
            }
            tapped.localUri = saved.uri;
        }
        List<Track> ready = new ArrayList<>();
        int start = -1;
        for (Track t : items) {
            if (t.localUri != null && !t.localUri.isEmpty()) {
                if (t == tapped) start = ready.size();
                ready.add(t);
            }
        }
        if (start < 0) {
            ready.add(tapped);
            start = 0;
        }
        playback.playQueue(ready, start, "Cartify");
        openPlayer();
    }

    private void refreshPlaylists() {
        if (playlistAdapter == null) return;
        List<Playlist> lists = PlaylistStore.all(this);
        sortPlaylists(lists);
        playlistAdapter.submit(lists);
        boolean empty = lists.isEmpty();
        playlistsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        playlistsStatus.setText(empty ? "Make a playlist" : lists.size() + (lists.size() == 1 ? " playlist" : " playlists"));
        if (playlistsSort != null) {
            playlistsSort.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Orders the playlist shelf.
     *
     * 0 Newest, 1 Name, 2 Song count, 3 Recently updated. The store returns insertion
     * order, which is not a decision the user made, so every mode sorts explicitly and
     * the list never depends on the order the files happen to be read in.
     */
    private void sortPlaylists(List<Playlist> lists) {
        switch (playlistsSortMode) {
            case 1:
                lists.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                        a.name == null ? "" : a.name, b.name == null ? "" : b.name));
                break;
            case 2:
                lists.sort((a, b) -> Integer.compare(b.songs.size(), a.songs.size()));
                break;
            case 3:
                lists.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
                break;
            default:
                lists.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
                break;
        }
    }

    /** Cycles Newest -> Name -> Songs -> Updated -> Newest. */
    private void cyclePlaylistSort() {
        playlistsSortMode = (playlistsSortMode + 1) % 4;
        if (playlistsSort != null) {
            playlistsSort.setText(new String[]{"Newest", "Name", "Songs", "Updated"}[playlistsSortMode]);
            playlistsSort.setTextColor(getColor(R.color.accent));
            playlistsSort.setAlpha(0.6f);
            playlistsSort.animate().alpha(1f).setDuration(220).start();
        }
        refreshPlaylists();
    }

    private void createPlaylist() {
        InputSheet.show(this, "New playlist", "Name it now \u2014 you can rename it later.",
                "", "Create", value -> {
                    Playlist p = new Playlist(value);
                    PlaylistStore.save(this, p);
                    refreshPlaylists();
                    toast("Playlist created");
                    openPlaylist(p);
                });
    }

    private void openPlaylist(Playlist playlist) {
        startActivity(new Intent(this, PlaylistActivity.class)
                .putExtra(PlaylistActivity.EXTRA_ID, playlist.id));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void showPlaylistMenu(Playlist playlist) {
        MenuSheet.show(this, playlist.name, playlist.summary(),
                new MenuSheet.Item(R.drawable.ic_play, "Play", () -> {
                    List<Track> tracks = new ArrayList<>();
                    for (Playlist.Entry e : playlist.songs) tracks.add(e.toTrack());
                    if (tracks.isEmpty()) {
                        toast("Add songs first");
                        return;
                    }
                    playback.playQueue(tracks, 0, playlist.name);
                    openPlayer();
                }),
                new MenuSheet.Item(R.drawable.ic_edit, "Rename playlist", () ->
                        PlaylistActions.rename(this, playlist, this::refreshPlaylists)),
                new MenuSheet.Item(R.drawable.ic_image, "Change cover", () ->
                        PlaylistActions.pickCover(this, playlist)),
                new MenuSheet.Item(R.drawable.ic_download, "Export as .m3u", () -> exportPlaylist(playlist)),
        new MenuSheet.Item(R.drawable.ic_delete, "Delete playlist", true, () ->
                        PlaylistActions.confirmDelete(this, playlist, this::refreshPlaylists)));
    }

    /** "Add to playlist" from a library song: pick an existing list or make a new one. */
    private void addToPlaylist(Track track) {
        List<Playlist> lists = PlaylistStore.all(this);
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_add, "New playlist\u2026", () ->
                InputSheet.show(this, "New playlist", null, "", "Create", value -> {
                    Playlist p = new Playlist(value);
                    p.add(track);
                    PlaylistStore.save(this, p);
                    refreshPlaylists();
                    toast("Added to " + p.name);
                })));
        for (Playlist p : lists) {
            items.add(new MenuSheet.Item(R.drawable.ic_queue,
                    p.contains(track.localUri) ? p.name + "  \u00b7  already added" : p.name, () -> {
                if (p.contains(track.localUri)) {
                    toast("Already in " + p.name);
                    return;
                }
                p.add(track);
                PlaylistStore.save(this, p);
                refreshPlaylists();
                toast("Added to " + p.name);
            }));
        }
        MenuSheet.show(this, "Add to playlist", track.title,
                items.toArray(new MenuSheet.Item[0]));
    }

    // ---------------------------------------------------------------- resolving

    /**
     * Searches the public catalog and shows the hits as normal download rows.
     *
     * Results are ordinary Track objects, so tapping Download all runs the exact same
     * matching and encoding path a Spotify link uses. Nothing in the download pipeline
     * needed to change to support this.
     */
    /**
     * Fills the empty search screen with suggested music.
     *
     * These are ordinary download rows, so a suggestion can be downloaded with the same
     * tap a search result uses. Loaded once per launch and only when the list is empty, so
     * it never overwrites something the user just searched for.
     */

    /** Greets by the phone's own clock, the way Spotify's home screen does. */
    private void showGreeting() {
        TextView greeting = findViewById(R.id.greetingText);
        if (greeting == null) return;
        greeting.setText(greetingForHour(java.util.Calendar.getInstance()
                .get(java.util.Calendar.HOUR_OF_DAY)));
    }

    /** Split out so the wording can be checked without a running activity. */
    static String greetingForHour(int hour) {
        if (hour >= 5 && hour < 12) return "Good morning";
        if (hour >= 12 && hour < 17) return "Good afternoon";
        if (hour >= 17 && hour < 22) return "Good evening";
        return "Good night";
    }
    /**
     * Hides the paste-a-link card once there are results to look at.
     *
     * The home pane is a vertical stack and the results list gets whatever height is left.
     * With the link card always expanded, the rows were squeezed to nothing and the shelf
     * appeared to overlap them. Collapsing the card the first time a list arrives gives the
     * rows the whole screen, and the search box above stays put.
     */
    /**
     * Folds the "More like this" shelf away, giving its height back to the results list.
     *
     * The shelf sits above the list in a vertical stack, so while it is open it physically
     * shrinks the rows underneath: the user sees fewer songs and has to scroll inside a
     * smaller window to read them. Animating the block's height to zero returns that space
     * to the list in the same frames as the fold, so the rows visibly move up instead of
     * being revealed only after a jump.
     *
     * Guarded by {@link #shelfOpen}: a scroll listener fires for every pixel, and starting
     * the animation again on each tick would make the fold stutter and never settle.
     */
    /**
     * How far the shelf has faded, 0 = fully visible, 1 = fully gone.
     *
     * Set from scroll position only, never from direction, which is what makes it stable.
     */
    private float shelfFade;
    /** Measured height of the shelf, used for the list padding and the travel distance. */
    private int shelfHeightPx;

    /**
     * Fades the shelf out and slides it up as the results are scrolled.
     *
     * @param scrollY the list's vertical scroll offset in pixels.
     *
     * The whole effect is a pure function of {@code scrollY}:
     *   - alpha 1 -> 0 over the first {@link #SHELF_FADE_PX}
     *   - translationY 0 -> -height over the same distance
     *   - the list's top padding shrinks by the same amount, so the rows rise into the
     *     space instead of sitting under a gap
     *
     * Nothing is animated and no layout is requested: transforms and a padding update are
     * cheap, they cannot fire scroll callbacks, and scrolling back up recomputes the exact
     * same values in reverse. That is what removes the flashing for good - the previous
     * versions changed the layout in response to scrolling, and a layout change during a
     * gesture is what produced both the oscillation and the "it just vanishes" jump.
     */
    private void applyShelfFade(int scrollY) {
        if (shelfBlock == null) return;
        if (shelfBlock.getVisibility() != View.VISIBLE) return;
        if (shelfHeightPx <= 0) shelfHeightPx = shelfBlock.getHeight();
        if (shelfHeightPx <= 0) return;

        float travel = Math.max(1, Math.min(SHELF_FADE_PX, shelfHeightPx));
        float t = Math.max(0f, Math.min(1f, scrollY / travel));
        if (Math.abs(t - shelfFade) < 0.004f) return;   // nothing to redraw
        shelfFade = t;

        shelfBlock.setAlpha(1f - t);
        shelfBlock.setTranslationY(-travelledHeight(t));
        // The list padding is deliberately NOT touched here. Changing it while the list is
        // scrolling fights the scroll: reducing top padding shifts the content back down by
        // the same amount, so the rows appeared to move the wrong way (the first row went
        // from y=1033 to y=1157 when scrolling down). The padding is set once in
        // prepareShelfOverlay() and the shelf simply fades over it.
    }

    private float travelledHeight(float t) {
        return shelfHeightPx * t;
    }

    /** Distance over which the shelf fades out. A short, deliberate travel: about one
     *  shelf-height of scrolling, so it is gone by the time the second row reaches the top
     *  rather than after a full screen of scrolling. */
    private static final int SHELF_FADE_PX = 360;

    /**
     * Prepares the shelf after a new collection is shown.
     *
     * The list gets a top padding equal to the shelf so the first row starts below it; the
     * shelf itself is positioned over that padding as an overlay. Layout is read after it
     * has settled, which is why this is posted rather than run inline.
     */
    /**
     * Fills the shelf with music that is genuinely different from the rows.
     *
     * The seed is the artist of the first result, because a search or a Spotify link is
     * almost always about one artist. Every title already in the rows is excluded, so the
     * shelf can never show a song the user is already looking at.
     *
     * The shelf is left hidden if nothing comes back. An empty rail would take the space
     * without giving anything in return, which is the opposite of the point.
     */
    private void loadRelatedShelf(SpotifyCollection resolved) {
        if (cardAdapter == null || resolved == null || resolved.tracks.isEmpty()) return;
        final String seed = resolved.tracks.get(0).artist;
        final java.util.Set<String> exclude = new java.util.HashSet<>();
        for (Track t : resolved.tracks) {
            exclude.add((t.title + "|" + (t.artist == null ? "" : t.artist)).toLowerCase(Locale.US));
        }
        MusicSearch.relatedTo(seed, exclude, new MusicSearch.Callback() {
            @Override
            public void onResults(List<Track> tracks) {
                if (isFinishing() || cardAdapter == null) return;
                // Playlists own the rail when they are present. This has to be the FIRST
                // thing checked: submitting to the adapter first left recRail painting its
                // cards underneath the playlists, which is what pushed the results off the
                // screen even after the shelf itself stopped being shown.
                boolean playlistsShowing = playlistRail != null
                        && playlistRail.getVisibility() == View.VISIBLE;
                if (playlistsShowing) {
                    cardAdapter.submit(new ArrayList<>());
                    if (recHeader != null) recHeader.setVisibility(View.GONE);
                    return;
                }
                cardAdapter.submit(tracks);
                if (recHeader != null) {
                    recHeader.setText("More like " + (seed == null || seed.isEmpty() ? "this" : seed));
                    recHeader.setVisibility(View.VISIBLE);
                }
                if (shelfBlock != null) {
                    shelfBlock.setVisibility(View.VISIBLE);
                    prepareShelfOverlay();
                    shelfBlock.setAlpha(0f);
                    shelfBlock.animate().alpha(1f).setDuration(220).start();
                }
            }

            @Override
            public void onFailed(String message) {
                // No related music: leave the shelf out of the way rather than show a gap.
                if (isFinishing()) return;
                shelfAvailable = false;
                if (cardAdapter != null) cardAdapter.submit(new ArrayList<>());
                if (recHeader != null) recHeader.setVisibility(View.GONE);
                hideShelfOverlay();
            }
        });
    }

    private void prepareShelfOverlay() {
        if (shelfBlock == null || list == null) return;
        shelfBlock.setVisibility(View.VISIBLE);
        // Measure explicitly instead of waiting for a layout pass.
        //
        // Retrying post() eight times did not work: every post lands in the same message
        // batch, so they all run before the next layout and each one still reads a height
        // of 0. Measuring the block by hand with the list width gives a real number
        // immediately, with no dependency on when the framework happens to lay out.
        int w = list.getWidth();
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        shelfBlock.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0,
                        android.view.View.MeasureSpec.UNSPECIFIED));
        int h = shelfBlock.getMeasuredHeight();
        if (h <= 0) return;
        shelfHeightPx = h;
        shelfFade = -1f;                     // force the next apply to run
        shelfBlock.setAlpha(1f);
        shelfBlock.setTranslationY(0f);
        list.setPadding(list.getPaddingLeft(), shelfHeightPx,
                list.getPaddingRight(), list.getPaddingBottom());
        // A new collection must start at the TOP of the list. The results list is reused
        // between searches, so it still carried the previous scroll offset - and the fade
        // is a function of that offset, so the shelf was being born at alpha 0 and never
        // seen. Resetting here also matches what a user expects from a fresh search.
        list.scrollToPosition(0);
        applyShelfFade(0);
    }


    /** Removes the shelf and its padding entirely, for a collection with nothing to show. */
    private void hideShelfOverlay() {
        if (shelfBlock == null) return;
        shelfBlock.setVisibility(View.GONE);
        shelfBlock.setAlpha(1f);
        shelfBlock.setTranslationY(0f);
        shelfHeightPx = 0;
        shelfFade = 0f;
        if (list != null) {
            list.setPadding(list.getPaddingLeft(), 0, list.getPaddingRight(), list.getPaddingBottom());
        }
    }

    private void collapsePasteBlock() {
        View card = findViewById(R.id.pasteBlock);
        if (card == null) return;
        if (card.getVisibility() == View.VISIBLE) {
            card.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                card.setVisibility(View.GONE);
                card.setAlpha(1f);
            }).start();
        }
    }

    /**
     * Writes the playlist next to the music as an .m3u file.
     *
     * Until now a playlist existed only inside the app. Exporting it means the list can be
     * opened by another player or copied off the phone, and the songs themselves are
     * already there because the export sits in the same folder.
     */
    private void exportPlaylist(Playlist playlist) {
        if (playlist == null) return;
        if (playlist.songs.isEmpty()) {
            toast("This playlist is empty");
            return;
        }
        toast("Exporting " + playlist.name + "\u2026");
        PlaylistExport.export(this, playlist, true, new PlaylistExport.Callback() {
            @Override
            public void onDone(String displayPath) {
                toast("Saved to " + displayPath);
            }

            @Override
            public void onFailed(String message) {
                toast("Export failed: " + message);
            }
        });
    }
    /**
     * Fills the home rows with the songs actually played last.
     *
     * This is the difference between an app that opens on a stranger's playlist and one
     * that opens on your own listening. Only songs still in the library are shown, so a
     * deleted file never leaves a row that cannot play, and the chart is only requested
     * when there is no history to show.
     */
    private void loadRecentlyPlayed() {
        final List<String> uris = Recent.all(this);
        if (uris.isEmpty()) {
            loadRecommendations();
            return;
        }
        io.execute(() -> {
            List<LibraryStore.Item> raw = LibraryStore.query(this);
            List<Track> out = new ArrayList<>();
            for (String uri : uris) {
                for (LibraryStore.Item it : raw) {
                    if (uri.equals(it.uri)) {
                        out.add(it.toTrack());
                        break;
                    }
                }
            }
            final List<Track> found = out;
            main.post(() -> {
                if (isFinishing() || adapter.getItemCount() > 0) return;
                if (found.isEmpty()) {
                    loadRecommendations();
                    return;
                }
                SpotifyCollection recent = new SpotifyCollection();
                recent.name = "Recently played";
                recent.type = "search";
                recent.tracks.addAll(found);
                showCollection(recent);
                setBusy(false, "Recently played \u00b7 tap Download");
            });
        });
    }

    private void loadRecommendations() {
        if (adapter == null || adapter.getItemCount() > 0) return;
        MusicSearch.recommendations(new MusicSearch.Callback() {
            @Override
            public void onResults(List<Track> tracks) {
                if (isFinishing() || adapter.getItemCount() > 0) return;
                SpotifyCollection suggested = new SpotifyCollection();
                suggested.name = "Recommended for you";
                suggested.type = "search";
                suggested.tracks.addAll(tracks);
                showCollection(suggested);
                setBusy(false, "Recommended for you · tap Download");
            }

            @Override
            public void onFailed(String message) {
                // Silent: an empty home screen is fine, an error on launch is not.
            }
        });
    }

    private void runSearch(EditText input) {
        if (input == null) return;
        if (queueRunning) {
            toast("A download is still running. Wait for it to finish, or cancel it.");
            return;
        }
        String query = input.getText().toString().trim();
        if (query.isEmpty()) {
            toast("Type a song, artist or album");
            return;
        }
        hideKeyboard();
        // Remembered before the request so a failed search is still one tap to retry.
        View pasteBlock = findViewById(R.id.pasteBlock);
        if (pasteBlock != null) pasteBlock.setVisibility(View.VISIBLE);
        showGreeting();
        setBusy(true, "Searching for " + query + "...");
        MusicSearch.search(query, new MusicSearch.Callback() {
            @Override
            public void onResults(List<Track> tracks) {
                SpotifyCollection found = new SpotifyCollection();
                found.name = query;
                found.type = "search";
                found.tracks.addAll(tracks);
                showCollection(found);
                // showCollection reports the count as if it were a resolved link; make it
                // read as a search result instead.
                setBusy(false, tracks.size() + (tracks.size() == 1 ? " result" : " results")
                        + " for " + query);
            }

            @Override
            public void onFailed(String message) {
                setBusy(false, "Search failed: " + message);
            }
        });
        // Playlists are a second, independent request. It is fired alongside the track
        // search rather than after it so the two latencies overlap: a playlist endpoint
        // that is slow must not delay the songs the user actually typed for.
        searchPlaylists(query);
    }

    /**
     * Fills the playlists rail under the results.
     *
     * Deliberately non-blocking and failure-tolerant: the track results are the primary
     * answer to a search, and a playlist endpoint being down must not turn a successful
     * search into an error. A failure simply leaves the rail hidden.
     */
    private void searchPlaylists(String query) {
        MusicSearch.searchPlaylists(query, new MusicSearch.PlaylistCallback() {
            @Override
            public void onResults(java.util.List<PlaylistResult> playlists) {
                playlistResults.clear();
                playlistResults.addAll(playlists);
                if (playlistRailAdapter != null) playlistRailAdapter.submit(playlists);
                showPlaylistRail(!playlists.isEmpty());
            }

            @Override
            public void onFailed(String message) {
                playlistResults.clear();
                if (playlistRailAdapter != null) {
                    playlistRailAdapter.submit(new java.util.ArrayList<>());
                }
                showPlaylistRail(false);
            }
        });
    }

    /** Shows or hides the playlists rail and re-measures the shelf that contains it. */
    private void showPlaylistRail(boolean visible) {
        if (playlistHeader == null || playlistRail == null) return;
        playlistHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        playlistRail.setVisibility(visible ? View.VISIBLE : View.GONE);
        // The related rail is hidden while playlists are up; bring it back when they go,
        // or the next search would silently lose "More like this" for good.
        if (!visible && recRail != null) recRail.setVisibility(View.VISIBLE);
        // Only one rail is shown at a time. Two stacked rails measured 1187px of a 1600px
        // pane, which left exactly two result rows visible above the fold - the search
        // results, which are the point of the screen, were pushed off it. Playlists win
        // because they are a specific answer to what was typed; "More like this" is a
        // suggestion and is hidden while they are present.
        if (shelfBlock == null) return;
        if (visible) {
            shelfAvailable = false;
            if (cardAdapter != null) cardAdapter.submit(new ArrayList<>());
            if (recHeader != null) recHeader.setVisibility(View.GONE);
            // GONE, not just empty: an empty rail still reserves its margins.
            if (recRail != null) recRail.setVisibility(View.GONE);
            shelfBlock.setVisibility(View.VISIBLE);
            shelfBlock.setAlpha(1f);
        }
        // The overlay's height drives the list padding, so any change is re-measured.
        prepareShelfOverlay();
    }

    /**
     * Opens a playlist: its tracks replace the current results.
     *
     * The playlist is NOT created locally here. Opening is a read; keeping a playlist is a
     * deliberate act (its own button, or downloading it), which is the same distinction the
     * app already makes for albums.
     */
    private void openPlaylist(PlaylistResult playlist) {
        if (playlist == null) return;
        setBusy(true, "Reading " + playlist.title + "...");
        MusicSearch.loadPlaylistTracks(playlist, new MusicSearch.Callback() {
            @Override
            public void onResults(List<Track> tracks) {
                SpotifyCollection found = new SpotifyCollection();
                found.name = playlist.title;
                found.type = "playlist";
                found.owner = playlist.owner;
                found.coverUrl = playlist.coverUrl;
                found.tracks.addAll(tracks);
                showCollection(found);
                // Named after the playlist, not the search box: the rows now belong to the
                // playlist, and a status line still showing the typed query would misdescribe
                // what is on screen.
                lastCollectionStatus = tracks.size() + " track" + (tracks.size() == 1 ? "" : "s")
                        + " from " + playlist.title;
                setBusy(false, lastCollectionStatus + " - tap Download");
            }

            @Override
            public void onFailed(String message) {
                setBusy(false, "Could not open that playlist: " + message);
            }
        });
    }

    /**
     * Downloads a playlist AND keeps it as a local playlist.
     *
     * The two halves are deliberately separate concerns: the download queue already saves
     * each track into Music/Cartify through MediaStore, and a Playlist is a list of those
     * saved URIs. Creating the Playlist first, empty, and filling it as tracks finish means
     * the user sees the playlist appear immediately and grow, instead of nothing happening
     * until the whole queue drains.
     *
     * Tracks that were already in the library are added to the playlist straight away and
     * are not re-downloaded - matching what the rows already show as "saved".
     */
    private void downloadPlaylist(PlaylistResult playlist) {
        if (playlist == null) return;
        if (queueRunning) {
            toast("A download is still running. Wait for it to finish, or cancel it.");
            return;
        }
        toast("Reading " + playlist.title + "...");
        MusicSearch.loadPlaylistTracks(playlist, new MusicSearch.Callback() {
            @Override
            public void onResults(List<Track> tracks) {
                if (tracks.isEmpty()) {
                    toast("That playlist has no tracks");
                    return;
                }
                // A fresh id each time: downloading the same playlist twice must produce a
                // second playlist rather than silently overwrite the first one's contents.
                Playlist local = new Playlist(playlist.title);
                local.createdAt = System.currentTimeMillis();
                pendingPlaylistId = local.id;
                PlaylistStore.save(getApplicationContext(), local);

                List<Track> toDownload = new ArrayList<>();
                for (Track t : tracks) {
                    // Already saved? Then only the playlist entry is missing.
                    LibraryStore.Item existing = LibraryStore.findSaved(MainActivity.this,
                            t.title, t.artist);
                    if (existing != null && existing.uri != null && !existing.uri.isEmpty()) {
                        t.localUri = existing.uri;
                        t.status = Track.DONE;
                        appendToPendingPlaylist(t);
                    } else {
                        toDownload.add(t);
                    }
                }
                toast(local.songs.size() + " already saved, "
                        + toDownload.size() + " to download");
                if (toDownload.isEmpty()) {
                    pendingPlaylistId = "";
                    refreshPlaylists();
                    toast("Playlist saved - nothing new to download");
                    return;
                }
                startDownload(toDownload);
            }

            @Override
            public void onFailed(String message) {
                toast("Could not read that playlist: " + message);
            }
        });
    }

    /** Id of the playlist being filled by the running queue, or "" when none. */
    private String pendingPlaylistId = "";

    /** Adds one finished track to the playlist that this queue is building. */
    private void appendToPendingPlaylist(Track t) {
        if (pendingPlaylistId == null || pendingPlaylistId.isEmpty()) return;
        if (t.localUri == null || t.localUri.isEmpty()) return;
        Playlist p = PlaylistStore.byId(getApplicationContext(), pendingPlaylistId);
        if (p == null) return;
        p.add(t);
        PlaylistStore.save(getApplicationContext(), p);
    }

    private void resolve() {
        if (queueRunning) {
            toast("A download is still running. Wait for it to finish, or cancel it.");
            return;
        }
        String raw = linkInput.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            toast("Paste a Spotify link first");
            setBusy(false, "Paste a Spotify link to get started");
            return;
        }
        String link = extractLink(raw);
        hideKeyboard();
        setBusy(true, "Reading Spotify link\u2026");
        resolvingLink = true;
        io.execute(() -> {
            try {
                SpotifyCollection resolved = SpotifyResolver.resolve(link);
                main.post(() -> {
                    resolvingLink = false;
                    showCollection(resolved);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> {
                    resolvingLink = false;
                    setBusy(false, "Could not read that link");
                    toast(message);
                });
            }
        });
    }

    private void showCollection(SpotifyCollection resolved) {
        collection = resolved;
        adapter.submit(resolved.tracks);
        // The shelf mirrors the rows: same songs, shown as artwork first. It opens fully
        // visible at rest so the reader can see it exists; scrolling the results folds it
        // away, which is what returns the full pane height to the rows.
        boolean isPlaylist = "playlist".equals(resolved.type);
        if (cardAdapter != null && shelfBlock != null) {
            if (isPlaylist) {
                // An opened playlist fills the pane. Its own tracks are what the user asked
                // for; a recommendation rail above them pushed all but two rows off screen.
                cardAdapter.submit(new ArrayList<>());
                shelfAvailable = false;
                if (recHeader != null) recHeader.setVisibility(View.GONE);
                hideShelfOverlay();
            } else if (resolved.tracks.size() > 1) {
                // The shelf is filled ASYNCHRONOUSLY with genuinely different music (see
                // loadRelatedShelf). Submitting resolved.tracks here was what made the
                // shelf a duplicate of the rows above it.
                shelfAvailable = true;
                if (recHeader != null) {
                    // The collection bar directly above already names this list, so the
                    // shelf header only adds value when it says something different.
                    String shelfLabel = resolved.tracks.isEmpty() ? "" : "More like this";
                    recHeader.setText(shelfLabel);
                    recHeader.setVisibility(shelfLabel.isEmpty() ? View.GONE : View.VISIBLE);
                }
                shelfBlock.setVisibility(View.VISIBLE);
                // Measured after layout so the list padding matches the real height.
                prepareShelfOverlay();
                loadRelatedShelf(resolved);
            } else {
                cardAdapter.submit(new ArrayList<>());
                shelfAvailable = false;
                if (recHeader != null) recHeader.setVisibility(View.GONE);
                hideShelfOverlay();
            }
        }
        // Rows appear without a cascade animation. The old code set a layout animation
        // and cleared it on a 900 ms timer, which crashed with a NullPointerException
        // inside ViewGroup.dispatchDraw when the timer fired mid-draw:
        // LayoutAnimationController.isDone() on a null reference. A cosmetic entrance is
        // not worth a crash on the most common action in the app.
        collapsePasteBlock();
        emptyState.setVisibility(View.GONE);
        // The collection bar that used to sit here is gone: it cost a full row of height
        // between the search box and the songs, and the count it carried is already in the
        // status line above ("10 results for Coldplay"). Removing it gives both the result
        // rows and the "More like this" shelf that height back.
        //
        // The long-press shortcut to the collection menu has to live somewhere, so it moves
        // onto the status line: long-press that line to keep an album or playlist locally or
        // to queue everything at once.
        if (statusText != null) {
            statusText.setOnLongClickListener(v -> {
                showCollectionMenu();
                return true;
            });
        }
        if (!resolved.coverUrl.isEmpty()) {

        }
        lastCollectionStatus = resolved.tracks.size() + " track" + (resolved.tracks.size() == 1 ? "" : "s");
        setBusy(false, lastCollectionStatus + " ready - tap Download");

        markAlreadySaved(resolved.tracks);
        // Per-track covers arrive in the background, one every few hundred milliseconds.
        // Passing notifyDataSetChanged directly here meant EVERY arriving cover rebound
        // the whole list, and TrackAdapter blanks a row's cover when its URL changes — so
        // a 25-track search flashed 25 times while the artwork trickled in. Coalescing the
        // repaints into one at the end of the current frame turns that into a single quiet
        // update, and the artwork still appears as it lands.
        SpotifyResolver.attachTrackArtwork(resolved, this::repaintResultsSoon);
        // Resolve the first uploads now so tapping Download starts transferring at once.
        DownloadEngine.prewarm(getApplicationContext(), resolved.tracks);
        // ...and the first rows' stream urls too, so the first play tap on a fresh list
        // lands on a ready address instead of a 2-5 s resolution spinner. The first
        // couple of rows are the likeliest first taps; the rest resolve on demand or
        // are prefetched by the player once the queue exists.
        // How many rows to warm depends on the link: on a fast connection the extra
        // resolutions are nearly free and make the third and fourth tap instant too,
        // while on a slow one they would compete with the song the user is already
        // hearing, so only the very first row is worth spending bandwidth on.
        // A new list makes the previous list's pre-warms worthless: drop them so their
        // pool threads go to the rows the user is actually looking at now.
        DownloadEngine.cancelPrewarms();
        int streamPrewarm = Math.min(DownloadEngine.prewarmDepth(this), resolved.tracks.size());
        for (int i = 0; i < streamPrewarm; i++) {
            DownloadEngine.prewarmStream(getApplicationContext(), resolved.tracks.get(i));
        }
    }

    /**
     * One pending results repaint, at most one per frame.
     *
     * Background work (arriving artwork, the already-saved scan) wants to refresh the list
     * whenever it makes progress. Calling notifyDataSetChanged for each of those events
     * rebinds every visible row, and because a row blanks its cover when the artwork URL
     * changes, a burst of callbacks reads on screen as a flicker. Posting one repaint and
     * dropping the rest until it runs keeps the list calm without delaying the data.
     */
    private final Runnable repaintResults = () -> {
        if (adapter != null) adapter.notifyDataSetChanged();
    };

    /** Schedules a results repaint, coalescing any that are already queued. */
    private void repaintResultsSoon() {
        if (list == null) return;
        list.removeCallbacks(repaintResults);
        list.post(repaintResults);
    }

    /** Actions for the open collection: keep it, or download what is missing. */
    private void showCollectionMenu() {
        if (collection == null) return;
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_queue, "Save as playlist",
                this::saveCollectionAsPlaylist));
        final boolean playlist = "playlist".equals(collection.type);
        items.add(new MenuSheet.Item(R.drawable.ic_download,
                playlist ? "Download all & keep playlist" : "Download all",
                () -> {
                    // Downloading a playlist must leave a playlist behind, not just files:
                    // that is the difference between "I downloaded 30 songs" and "I have
                    // this playlist offline". Albums and searches keep plain behaviour.
                    if (playlist) downloadCollectionAsPlaylist();
                    else startDownload(collection.tracks);
                }));
        MenuSheet.show(this, collection.name, collection.tracks.size()
                + (collection.tracks.size() == 1 ? " track" : " tracks"),
                items.toArray(new MenuSheet.Item[0]));
    }

    /**
     * Downloads an already-open playlist and keeps it locally.
     *
     * The search rail path ({@link #downloadPlaylist}) re-fetches the tracks; this one
     * already has them on screen, so it only does the queueing half. Splitting it this way
     * avoids a second network round trip for something the user is looking at.
     */
    private void downloadCollectionAsPlaylist() {
        if (collection == null || collection.tracks.isEmpty()) return;
        if (queueRunning) {
            toast("A download is still running. Wait for it to finish, or cancel it.");
            return;
        }
        Playlist local = new Playlist(collection.name);
        local.createdAt = System.currentTimeMillis();
        pendingPlaylistId = local.id;

        List<Track> toDownload = new ArrayList<>();
        for (Track t : new ArrayList<>(collection.tracks)) {
            LibraryStore.Item hit = LibraryStore.findSaved(this, t.title, t.artist);
            if (hit != null && hit.uri != null && !hit.uri.isEmpty()) {
                t.localUri = hit.uri;
                t.status = Track.DONE;
                local.add(t);
            } else {
                toDownload.add(t);
            }
        }
        // Saved once, with everything already on disk included: the playlist appears in the
        // Playlists tab immediately and grows as the queue finishes the rest.
        PlaylistStore.save(getApplicationContext(), local);
        pendingPlaylistId = local.id;
        refreshPlaylists();
        if (toDownload.isEmpty()) {
            pendingPlaylistId = "";
            toast("Playlist saved - everything was already downloaded");
            return;
        }
        toast("Playlist saved - downloading " + toDownload.size());
        startDownload(toDownload);
    }

    /**
     * Copies the open collection into a local playlist.
     *
     * Only songs that are actually on the device are added: a playlist entry points at
     * a file, so queuing the ones that were never downloaded would create rows that
     * cannot play. The toast says how many were skipped, so an album that is half
     * downloaded does not look like it silently lost half its songs.
     */
    private void saveCollectionAsPlaylist() {
        if (collection == null) return;
        io.execute(() -> {
            Playlist p = new Playlist(collection.name);
            int added = 0;
            int missing = 0;
            for (Track t : new ArrayList<>(collection.tracks)) {
                LibraryStore.Item hit = LibraryStore.findSaved(this, t.title, t.artist);
                if (hit == null) {
                    missing++;
                    continue;
                }
                t.localUri = hit.uri;
                if (p.add(t)) added++;
            }
            final int got = added;
            final int skipped = missing;
            if (got == 0) {
                main.post(() -> toast("Nothing downloaded from this list yet"));
                return;
            }
            PlaylistStore.save(this, p);
            main.post(() -> {
                refreshPlaylists();
                toast("Saved \"" + p.name + "\" \u00b7 " + got
                        + (skipped > 0 ? ", " + skipped + " not downloaded" : ""));
            });
        });
    }

    /**
     * Flags rows whose song is already on the device.
     *
     * Pasting the same link twice used to re-download everything and leave "(1)" copies
     * behind. The resolve step now checks the saved library and marks those rows as
     * already downloaded, so Download all skips them and the row says so plainly.
     */
    /** The status line showCollection wrote, so markAlreadySaved can append to it. */
    private String lastCollectionStatus = "";

    private void markAlreadySaved(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        io.execute(() -> {
            List<Track> found = new ArrayList<>();
            List<LibraryStore.Item> hits = new ArrayList<>();
            for (Track t : tracks) {
                LibraryStore.Item hit = LibraryStore.findSaved(this, t.title, t.artist);
                if (hit != null) {
                    found.add(t);
                    hits.add(hit);
                }
            }
            if (found.isEmpty()) return;
            main.post(() -> {
                for (int i = 0; i < found.size() && i < hits.size(); i++) {
                    Track t = found.get(i);
                    t.status = Track.DONE;
                    t.progress = 100;
                    t.localUri = hits.get(i).uri;
                }
                adapter.notifyDataSetChanged();
                int n = found.size();
                // Appended rather than replacing the headline, so a search result still
                // reads N results for X with the saved count after it.
                // Reads as a suffix, so the headline never repeats the word "track".
                String suffix = n + (n == 1 ? " is" : " are") + " already saved";
                statusText.setText(lastCollectionStatus.isEmpty()
                        ? suffix : lastCollectionStatus + "  ·  " + suffix);
            });
        });
    }

    /**
     * Re-runs every row that ended in an error.
     *
     * A failed track used to be a dead end: the only way to try again was to paste the
     * link over and hope the duplicate guard had not already marked the rest saved. The
     * failures are usually transient (a dropped connection, a client that offered no
     * stream), so retrying just those rows is worth one tap.
     */
    private void retryFailed() {
        List<Track> failed = new ArrayList<>();
        for (Track t : adapter.items()) {
            if (t.status == Track.ERROR) failed.add(t);
        }
        if (failed.isEmpty()) {
            toast("Nothing to retry");
            return;
        }
        toast(failed.size() == 1 ? "Retrying 1 track" : "Retrying " + failed.size() + " tracks");
        startDownload(failed);
    }

    /**
     * True while a queue is running.
     *
     * Cancellation is keyed by row index, so replacing the list mid-queue shifted those
     * indices and could cancel a completely different song. Resolving a new link is now
     * refused until the queue drains, which keeps the mapping honest. The alternative —
     * keying every progress and cancel event by a stable track id — is a larger change to
     * the service protocol, and this removes the failure mode today.
     */
    private volatile boolean queueRunning;

    /** True only while a pasted Spotify link is being read. */
    private volatile boolean resolvingLink;

    private void startDownload(List<Track> tracks) {
        if (tracks.isEmpty()) {
            toast("Nothing to download");
            return;
        }
        // A queue started from the player (or one that is still running) owns the
        // service's single queue. Starting a second one here would overwrite its
        // positions and paint progress onto the wrong rows.
        if (DownloadState.running && !queueRunning) {
            toast("A download is already running");
            return;
        }
        queueRunning = true;
        DownloadState.running = true;
        for (Track t : tracks) {
            t.status = Track.QUEUED;
            t.progress = 0;
            t.error = null;
        }
        // Record the exact order handed to the service: every progress callback carries
        // an index into this list, not into any adapter.
        submitted.clear();
        submitted.addAll(tracks);
        refreshDownloads();
        adapter.notifyDataSetChanged();
        emptyState.setVisibility(View.GONE);
        statusText.setText("Starting downloader\u2026");
        // The button doubles as "Cancel all" and "Retry failed". A fresh queue must get
        // the cancel behaviour back, otherwise a stale retry handler would be attached to
        // a download that is already running.
        cancelAllButton.setText("Cancel all");
        cancelAllButton.setOnClickListener(v -> cancelAllDownloads());
        cancelAllButton.setVisibility(View.VISIBLE);

        JSONArray arr = new JSONArray();
        for (Track t : tracks) {
            try {
                arr.put(t.toJson());
            } catch (Exception ignored) {
            }
        }
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_TRACKS, arr.toString())
                .putExtra(DownloadService.EXTRA_ALBUM, collection == null ? "Cartify" : collection.name)
                .putExtra(DownloadService.EXTRA_TOTAL, tracks.size());
        ContextCompat.startForegroundService(this, intent);
    }

    /**
     * Cancels exactly one row. The service kills that track's yt-dlp process, so the
     * transfer really stops instead of finishing silently in the background.
     */
    private void cancelDownload(int index) {
        if (index < 0 || index >= adapter.getItemCount()) return;
        Track t = adapter.items().get(index);
        // The service cancels by queue position, so the adapter position must be
        // translated first; otherwise a different song is killed.
        int queueIndex = submitted.indexOf(t);
        if (queueIndex >= 0) {
            Intent intent = new Intent(this, DownloadService.class)
                    .setAction(DownloadService.ACTION_CANCEL_TRACK)
                    .putExtra(DownloadService.EXTRA_INDEX, queueIndex);
            startService(intent);
        }
        adapter.reset(index);
        t.status = Track.IDLE;
        t.progress = 0;
        refreshDownloads();
        statusText.setText("Cancelled one download");
    }

    private void cancelAllDownloads() {
        queueRunning = false;
        DownloadState.running = false;
        startService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_CANCEL));
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Track t = adapter.items().get(i);
            if (t.status == Track.QUEUED || t.status == Track.ACTIVE) adapter.reset(i);
        }
        statusText.setText("All downloads cancelled");
    }

    private void warmUpDownloader() {
        io.execute(() -> {
            try {
                DownloadEngine.ensureInitialised(getApplicationContext());
            } catch (Exception e) {
                main.post(() -> statusText.setText("Downloader engine: " + e.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------- download callbacks

    /**
     * Resolves a queue index to the Track the service is really working on.
     *
     * The service numbers the list it was handed. The search list is rebuilt on every
     * resolve and the downloads page holds a filtered subset, so applying a queue index
     * directly to either adapter painted the wrong row and made per-row actions look
     * broken. Identity is the only stable link between the two.
     */
    private Track trackForQueueIndex(int index) {
        if (index < 0 || index >= submitted.size()) return null;
        return submitted.get(index);
    }

    /** Writes one state change onto a track wherever it is currently shown. */
    private void applyQueueState(Track t, int status, int percent, String detail,
                                 String uri, String error) {
        if (t == null) return;
        t.status = status;
        t.progress = percent;
        if (uri != null) t.localUri = uri;
        if (error != null) t.error = error;
        int pos = adapter.items().indexOf(t);
        if (pos >= 0) adapter.update(pos, status, percent, detail, uri, error);
        // Only rebuild the Downloads list when its MEMBERSHIP changes. A progress tick
        // used to submit() the whole adapter, rebinding every visible row several times a
        // second for no reason; a row already on the page only needs its own progress
        // payload repainted.
        if (downloadsAdapter != null) {
            int dpos = downloadsAdapter.items().indexOf(t);
            boolean leaves = (status == Track.DONE || status == Track.IDLE);
            boolean enters = (status == Track.ERROR);
            if (dpos < 0 || leaves || enters) {
                refreshDownloads();
            } else {
                downloadsAdapter.update(dpos, status, percent, detail, uri, error);
            }
        }
    }

    @Override
    public void onTrackProgress(int index, int percent, String detail) {
        Track t = trackForQueueIndex(index);
        if (t == null) return;
        applyQueueState(t, Track.ACTIVE, percent, detail, null, null);
        statusText.setText(String.format(Locale.US, "Downloading %d of %d \u2014 %s",
                index + 1, submitted.size(), detail));
        cancelAllButton.setVisibility(View.VISIBLE);
    }

    @Override
    public void onTrackFinished(int index, String uri, String error) {
        Track t = trackForQueueIndex(index);
        if (t == null) return;
        if (error == null) {
            applyQueueState(t, Track.DONE, 100, "saved", uri, null);
            // A queue started by downloading a playlist fills that playlist as it goes,
            // so the user watches it grow instead of waiting for the whole queue to drain.
            t.localUri = uri;
            appendToPendingPlaylist(t);
        } else {
            applyQueueState(t, Track.ERROR, 0, error, null, error);
        }
    }

    @Override
    public void onTrackCancelled(int index) {
        Track t = trackForQueueIndex(index);
        if (t == null) return;
        applyQueueState(t, Track.IDLE, 0, "", null, "");
        statusText.setText("Download cancelled");
    }

    @Override
    public void onQueueFinished(int completed, int failed) {
        String summary;
        if (completed == 0 && failed == 0) {
            // A cancelled queue is not a failure: say so instead of "0 saved".
            summary = "Download cancelled";
        } else if (failed == 0) {
            summary = completed + " track" + (completed == 1 ? "" : "s") + " saved to Music/Cartify";
        } else {
            summary = completed + " saved \u00b7 " + failed + " failed";
        }
        statusText.setText(summary);
        queueRunning = false;
        DownloadState.running = false;
        // The queue that was building a playlist has drained: refresh the Playlists tab so
        // it shows the new list, and clear the id so a later single-track download does not
        // keep appending to it.
        if (pendingPlaylistId != null && !pendingPlaylistId.isEmpty()) {
            pendingPlaylistId = "";
            refreshPlaylists();
        }
        for (Track t : new ArrayList<>(submitted)) {
            if (t.status == Track.QUEUED || t.status == Track.ACTIVE) {
                applyQueueState(t, Track.IDLE, 0, "", null, "");
            }
        }
        // Finished tracks belong to the library; the page only tracks live work.
        submitted.clear();
        refreshDownloads();
        // "Cancel all" makes no sense once the queue has drained; when something failed,
        // the same slot offers a retry instead, so the user is never left with a dead end.
        if (failed > 0) {
            cancelAllButton.setText("Retry " + failed + " failed");
            cancelAllButton.setVisibility(View.VISIBLE);
            cancelAllButton.setOnClickListener(v -> retryFailed());
        } else {
            cancelAllButton.setVisibility(View.GONE);
            cancelAllButton.setOnClickListener(v -> cancelAllDownloads());
        }
        refreshLibrary();
    }

    // ---------------------------------------------------------------- library

    private void refreshLibrary() {
        io.execute(() -> {
            List<LibraryStore.Item> raw = LibraryStore.query(this);
            List<Track> songs = new ArrayList<>();
            for (LibraryStore.Item it : raw) songs.add(it.toTrack());
            main.post(() -> {
                libraryAll.clear();
                libraryAll.addAll(songs);
                applyLibraryFilter();
                syncNowPlayingHighlight();
            });
        });
    }

    /** Rebuilds the visible library rows from the saved songs and the search box. */
    private void applyLibraryFilter() {
        // Searching while rows are selected rebuilds the visible list, so the selection is
        // dropped rather than left pointing at songs the user can no longer see.
        if (songAdapter.isSelecting() && !libraryQuery.isEmpty()) songAdapter.clearSelection();
        List<Track> shown = new ArrayList<>();
        for (Track t : libraryAll) {
            if (libraryQuery.isEmpty()
                    || (t.title != null && t.title.toLowerCase(Locale.US).contains(libraryQuery))
                    || (t.artist != null && t.artist.toLowerCase(Locale.US).contains(libraryQuery))) {
                shown.add(t);
            }
        }
        sortLibrary(shown);
        songAdapter.setGrouped(libraryGrouped, shown);
        String suffix = libraryQuery.isEmpty() ? "" : " \u00b7 filtered";
        libraryStatus.setText(libraryAll.size() + " song" + (libraryAll.size() == 1 ? "" : "s") + suffix);
        updateLibraryEmpty();
    }

    /**
     * Orders the library rows.
     *
     * "Newest" relies on the MediaStore date the store already records, so a fresh
     * download appears at the top where the user expects it.
     */
    private void sortLibrary(List<Track> songs) {
        switch (librarySort) {
            case 1:
                songs.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                        a.title == null ? "" : a.title, b.title == null ? "" : b.title));
                break;
            case 2:
                songs.sort((a, b) -> {
                    int byArtist = String.CASE_INSENSITIVE_ORDER.compare(
                            a.artist == null ? "" : a.artist, b.artist == null ? "" : b.artist);
                    return byArtist != 0 ? byArtist : String.CASE_INSENSITIVE_ORDER.compare(
                            a.title == null ? "" : a.title, b.title == null ? "" : b.title);
                });
                break;
            default:
                songs.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
                break;
        }
    }

    /**
     * Turns artist section headers on or off.
     *
     * Reached by long-pressing the sort chip: headers are a way of reading the same
     * list, not a different one, so they belong next to the ordering control rather
     * than buried in the app menu.
     */
    private void setLibraryGrouped(boolean on) {
        libraryGrouped = on;
        if (on) librarySort = 2;
        if (librarySortButton != null) {
            librarySortButton.setText(libraryGrouped ? "Grouped"
                    : librarySort == 1 ? "Title" : librarySort == 2 ? "Artist" : "Newest");
            librarySortButton.setTextColor(getColor(R.color.accent));
            librarySortButton.setAlpha(0.6f);
            librarySortButton.animate().alpha(1f).setDuration(200).start();
        }
        if (songAdapter != null) songAdapter.setGrouped(libraryGrouped, null);
        applyLibraryFilter();
    }

    /** Cycles Newest -> Title -> Artist -> Newest. */
    private void cycleLibrarySort() {
        librarySort = (librarySort + 1) % 3;
        // Picking a different order while grouped would repeat artists down the
        // page, so the headers switch themselves off rather than lie.
        if (libraryGrouped && librarySort != 2) {
            libraryGrouped = false;
            if (songAdapter != null) songAdapter.setGrouped(false, null);
        }
        if (librarySortButton != null) {
            librarySortButton.setText(librarySort == 1 ? "Title" : librarySort == 2 ? "Artist" : "Newest");
            librarySortButton.setTextColor(getColor(R.color.accent));
            librarySortButton.setAlpha(0.6f);
            librarySortButton.animate().alpha(1f).setDuration(220).start();
        }
        applyLibraryFilter();
    }

    /** Starts the whole library in a random order. */
    private void shuffleAll() {
        if (libraryAll.isEmpty()) {
            toast("Your library is empty");
            return;
        }
        List<Track> shuffled = new ArrayList<>(libraryAll);
        java.util.Collections.shuffle(shuffled);
        playback.playQueue(shuffled, 0, "Library");
        playback.setShuffle(true);
        openPlayer();
    }

    private void updateLibraryEmpty() {
        boolean empty = songAdapter.getItemCount() == 0;
        libraryEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        grantAccessButton.setVisibility(View.GONE);
        if (empty) {
            boolean blocked = !hasAudioAccess();
            ((TextView) libraryEmpty.getChildAt(0)).setText(blocked
                    ? "Allow access to your music"
                    : "Your library is empty");
            ((TextView) libraryEmpty.getChildAt(1)).setText(blocked
                    ? "Cartify needs permission to read the songs saved in Music/Cartify."
                    : "Downloaded songs appear here automatically.");
            if (blocked) grantAccessButton.setVisibility(View.VISIBLE);
        }
    }

    private void showSongMenu(View anchor, Track track) {
        MenuSheet.show(this, track.title,
                track.artist == null || track.artist.isEmpty() ? "Unknown artist" : track.artist,
                new MenuSheet.Item(R.drawable.ic_play, "Play now", () -> {
                    playback.playQueue(new ArrayList<>(songAdapter.items()),
                            indexOfUri(track.localUri), "Library");
                    openPlayer();
                }),
                new MenuSheet.Item(R.drawable.ic_next, "Play next", () -> {
                    playback.playNext(track);
                    toast("Playing next");
                }),
                new MenuSheet.Item(R.drawable.ic_add, "Add to queue", () -> {
                    playback.append(track);
                    toast("Added to queue");
                }),
                new MenuSheet.Item(R.drawable.ic_add, "Add to playlist",
                        () -> addToPlaylist(track)),
                new MenuSheet.Item(R.drawable.ic_delete, "Delete from device", true,
                        () -> confirmDelete(track)));
    }

    // ---------------------------------------------------------------- bulk selection

    /** Swaps the library header for the bulk bar and back. */
    private void renderSelection(int count, boolean selecting) {
        if (selectionBar == null) return;
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (libraryHeader != null) libraryHeader.setVisibility(selecting ? View.GONE : View.VISIBLE);
        if (selecting && selectionCount != null) {
            selectionCount.setText(count + (count == 1 ? " selected" : " selected"));
            selectionCount.animate().cancel();
            selectionCount.setAlpha(0.55f);
            selectionCount.animate().alpha(1f).setDuration(160).start();
        }
    }

    /** "Add to playlist" for every selected row, with a running already-present count. */
    private void addSelectedToPlaylist() {
        final List<Track> picked = songAdapter.selectedTracks();
        if (picked.isEmpty()) {
            toast("Select at least one song");
            return;
        }
        final List<Playlist> lists = PlaylistStore.all(this);
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_add, "New playlist\u2026", () ->
                InputSheet.show(this, "New playlist", null, "", "Create", value -> {
                    Playlist p = new Playlist(value);
                    int added = 0;
                    for (Track t : picked) if (p.add(t)) added++;
                    PlaylistStore.save(this, p);
                    refreshPlaylists();
                    finishBulkAdd(p.name, added, picked.size());
                })));
        for (Playlist p : lists) {
            int already = 0;
            for (Track t : picked) if (p.contains(t.localUri)) already++;
            String label = already == 0 ? p.name
                    : p.name + "  \u00b7  " + already + " already added";
            items.add(new MenuSheet.Item(R.drawable.ic_queue, label, () -> {
                int added = 0;
                for (Track t : picked) if (p.add(t)) added++;
                if (added == 0) {
                    toast("Already in " + p.name);
                    return;
                }
                PlaylistStore.save(this, p);
                refreshPlaylists();
                finishBulkAdd(p.name, added, picked.size());
            }));
        }
        MenuSheet.show(this, "Add to playlist",
                picked.size() + (picked.size() == 1 ? " song selected" : " songs selected"),
                items.toArray(new MenuSheet.Item[0]));
    }

    private void finishBulkAdd(String playlistName, int added, int total) {
        toast(added == total
                ? "Added " + added + " to " + playlistName
                : "Added " + added + " of " + total + " to " + playlistName);
        songAdapter.clearSelection();
    }

    /** Appends every selected row to the queue instead of replacing what is playing. */
    private void queueSelected() {
        final List<Track> picked = songAdapter.selectedTracks();
        if (picked.isEmpty()) {
            toast("Select at least one song");
            return;
        }
        for (Track t : picked) playback.append(t);
        toast(picked.size() == 1 ? "Added to queue" : "Added " + picked.size() + " to queue");
        songAdapter.clearSelection();
    }

    /** Deleting is irreversible, so the sheet spells out how many files leave the device. */
    private void confirmDeleteSelected() {
        final List<Track> picked = songAdapter.selectedTracks();
        if (picked.isEmpty()) {
            toast("Select at least one song");
            return;
        }
        String what = picked.size() == 1
                ? "\"" + picked.get(0).title + "\""
                : picked.size() + " songs";
        MenuSheet.show(this, "Delete " + what + "?",
                "They will be removed from this device and from Music/Cartify. This cannot be undone.",
                new MenuSheet.Item(R.drawable.ic_delete, "Delete " + picked.size(), true,
                        () -> deleteSelected(picked)),
                new MenuSheet.Item(R.drawable.ic_close, "Cancel", () -> {
                }));
    }

    /**
     * Deletes rows one by one on a worker thread.
     *
     * A file this install owns disappears straight away; one saved by an earlier install
     * can only be removed by the system, so the whole batch is collected and handed to a
     * single consent dialog instead of prompting per song.
     */
    private void deleteSelected(final List<Track> picked) {
        io.execute(() -> {
            final List<Track> gone = new ArrayList<>();
            final List<String> denied = new ArrayList<>();
            for (Track t : picked) {
                if (t.localUri == null || t.localUri.isEmpty()) continue;
                if (LibraryStore.delete(this, t.localUri)) gone.add(t);
                else denied.add(t.localUri);
            }
            main.post(() -> {
                for (Track t : gone) {
                    detachFromQueue(t.localUri);
                    pruneFromPlaylists(t.localUri);
                }
                if (!denied.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestSystemDeleteBulk(denied, gone.size());
                } else {
                    reportBulkDeleted(gone.size(), denied.size());
                }
            });
        });
    }

    private void requestSystemDeleteBulk(List<String> uris, int alreadyGone) {
        try {
            List<Uri> parsed = new ArrayList<>();
            for (String u : uris) parsed.add(Uri.parse(u));
            android.app.PendingIntent request =
                    MediaStore.createDeleteRequest(getContentResolver(), parsed);
            pendingBulkDelete.clear();
            pendingBulkDelete.addAll(uris);
            pendingBulkDeletedCount = alreadyGone;
            startIntentSenderForResult(request.getIntentSender(), REQ_DELETE_BULK, null, 0, 0, 0);
        } catch (Exception e) {
            reportBulkDeleted(alreadyGone, uris.size());
        }
    }

    /** Running total for a bulk delete that spans the direct delete and the consent dialog. */
    private int pendingBulkDeletedCount;

    private void reportBulkDeleted(int deleted, int failed) {
        pendingBulkDelete.clear();
        pendingBulkDeletedCount = 0;
        songAdapter.clearSelection();
        if (deleted > 0) {
            toast(failed == 0
                    ? "Deleted " + deleted + (deleted == 1 ? " song" : " songs")
                    : "Deleted " + deleted + " \u00b7 " + failed + " kept");
        } else if (failed > 0) {
            toast("Could not delete " + failed + (failed == 1 ? " song" : " songs"));
        }
        refreshLibrary();
    }

    /** Deleting is irreversible, so ask first in the same glass sheet, saying exactly what goes. */
    private void confirmDelete(Track track) {
        MenuSheet.show(this, "Delete \"" + track.title + "\"?",
                deleteWarning(track),
                new MenuSheet.Item(R.drawable.ic_delete, "Delete from device", true,
                        () -> deleteSong(track)),
                new MenuSheet.Item(R.drawable.ic_close, "Cancel", () -> {
                }));
    }

    /**
     * Spells out the real cost of a delete.
     *
     * A song can sit in several playlists, and those entries keep pointing at a file that
     * no longer exists once it is deleted. Counting them here means the warning names the
     * playlists instead of leaving the user to discover empty rows afterwards.
     */
    private String deleteWarning(Track track) {
        String base = "It will be removed from this device and from Music/Cartify.";
        if (track.localUri == null || track.localUri.isEmpty()) return base;
        try {
            StringBuilder names = new StringBuilder();
            for (Playlist p : PlaylistStore.all(this)) {
                if (!p.contains(track.localUri)) continue;
                if (names.length() > 0) names.append(", ");
                names.append(p.name);
            }
            if (names.length() == 0) return base + " This cannot be undone.";
            return base + " It is also in " + names + ", where it will stop playing."
                    + " This cannot be undone.";
        } catch (Exception e) {
            return base + " This cannot be undone.";
        }
    }

    private void deleteSong(Track track) {
        if (track.localUri == null || track.localUri.isEmpty()) {
            toast("Nothing to delete");
            return;
        }
        // Ten seconds of grace before the file is really gone.
        //
        // Deleting a song was the one irreversible action in the app, and the
        // confirmation sheet only protects against a mis-tap on the row above it,
        // not against picking the wrong song from a long list. Holding the delete
        // briefly makes it undoable without any new screen: the row disappears now,
        // and a toast offers to put it back.
        final String uri = track.localUri;
        final Track snapshot = track;
        final boolean[] undone = {false};
        onSongRemoved(snapshot, uri);
        toastUndo("Deleted \"" + track.title + "\"", () -> {
            undone[0] = true;
            restoreDeleted(snapshot);
        });
        main.postDelayed(() -> {
            if (undone[0]) return;
            io.execute(() -> {
                boolean removed = LibraryStore.delete(this, uri);
                main.post(() -> {
                    if (!removed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // The file was saved by an earlier install, so only the
                        // system can delete it.
                        requestSystemDelete(snapshot, uri);
                    } else if (!removed) {
                        toast("Could not delete \"" + snapshot.title + "\"");
                    }
                    // Now that the window has closed, playlist entries that point at
                    // this file can go too; before this they had to stay so an undo
                    // could put the song back where it was.
                    if (removed) pruneFromPlaylists(uri);
                });
            });
        }, 10000L);
    }

    /**
     * Puts a song back into the visible library after an undo.
     *
     * The file was never touched, so nothing has to be restored on disk: the row
     * simply comes back into the list. Playlists were not pruned until the grace
     * window expires, which is why they are untouched here too.
     */
    private void restoreDeleted(Track track) {
        libraryAll.add(0, track);
        applyLibraryFilter();
        toast("Restored \"" + track.title + "\"");
    }

    /**
     * A toast with one action attached.
     *
     * Android has no action-toast API, so this inflates the same row as a plain
     * toast and wires the action text by hand. Long duration because the whole
     * point is to give the user time to react.
     */
    /**
     * Shows a message that can be acted on, for a few seconds.
     *
     * Replaces the status-line trick used for the first version of undo: the status
     * line is inside the home pane, so the prompt was invisible from the library,
     * which is exactly where deletions happen. This floats above the tab bar instead
     * and is reachable from every screen.
     */
    private void showSnack(String message, String actionLabel, Runnable action) {
        if (snackBar == null) return;
        snackText.setText(message);
        if (actionLabel != null && action != null) {
            snackAction.setText(actionLabel);
            snackAction.setVisibility(View.VISIBLE);
            snackAction.setOnClickListener(v -> {
                hideSnack();
                action.run();
            });
        } else {
            snackAction.setVisibility(View.GONE);
            snackAction.setOnClickListener(null);
        }
        snackBar.setVisibility(View.VISIBLE);
        snackBar.setAlpha(0f);
        snackBar.setTranslationY(16f * getResources().getDisplayMetrics().density);
        snackBar.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    private void hideSnack() {
        if (snackBar == null || snackBar.getVisibility() != View.VISIBLE) return;
        snackBar.animate().alpha(0f).setDuration(140)
                .withEndAction(() -> snackBar.setVisibility(View.GONE)).start();
    }

    private void toastUndo(String message, Runnable action) {
        // A toast cannot be tapped, so claiming "UNDO" in one would be a lie. The
        // status line is already on screen, already styled as the app talking, and
        // is a real view that can take a click for the length of the window.
        undoAction = action;
        showSnack(message, "UNDO", this::undoPendingDelete);
        main.removeCallbacks(clearUndo);
        main.postDelayed(clearUndo, 10000L);
    }

    /** The action a tap on the mini player's undo affordance would run. */
    private Runnable undoAction;
    private final Runnable clearUndo = () -> {
        undoAction = null;
        hideSnack();
    };

    private void undoPendingDelete() {
        Runnable a = undoAction;
        undoAction = null;
        if (a != null) a.run();
    }

    /** Android 11+: hand the deletion to the system consent dialog. */
    private void requestSystemDelete(Track track, String uri) {
        try {
            android.app.PendingIntent request = MediaStore.createDeleteRequest(
                    getContentResolver(), java.util.Collections.singletonList(Uri.parse(uri)));
            pendingDeleteTrack = track;
            pendingDeleteUri = uri;
            startIntentSenderForResult(request.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
        } catch (Exception e) {
            toast("Could not delete \"" + track.title + "\"");
        }
    }

    private void onSongRemoved(Track track, String uri) {
        detachFromQueue(uri);
        // Playlists are pruned by the deferred delete, after the undo window; doing
        // it here would make an undo unable to restore the playlist entries.
        libraryAll.remove(track);
        applyLibraryFilter();
    }

    /** Stop a deleted file from being played out of a queue that no longer owns it. */
    private void detachFromQueue(String uri) {
        if (uri == null || uri.isEmpty()) return;
        List<Track> queue = playback.queue();
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (uri.equals(queue.get(i).localUri)) {
                playback.removeAt(i);
                return;
            }
        }
    }

    /**
     * Drops a deleted file from every playlist that still lists it.
     *
     * Playlist entries are stored by uri, so deleting the MP3 from the library used to
     * leave the playlist pointing at a row that could never play. Rewriting the affected
     * playlists keeps the two views agreeing about what exists.
     */
    private void pruneFromPlaylists(String uri) {
        if (uri == null || uri.isEmpty()) return;
        boolean changed = false;
        for (Playlist p : PlaylistStore.all(this)) {
            if (p.removeUri(uri)) {
                PlaylistStore.save(this, p);
                changed = true;
            }
        }
        if (changed) refreshPlaylists();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (PlaylistActions.onActivityResult(this, requestCode, resultCode, data,
                this::refreshPlaylists)) return;
        if (requestCode == REQ_DELETE_BULK) {
            int already = pendingBulkDeletedCount;
            int allowed = 0;
            if (resultCode == RESULT_OK) {
                for (String u : pendingBulkDelete) {
                    detachFromQueue(u);
                    allowed++;
                }
            }
            reportBulkDeleted(already + allowed, pendingBulkDelete.size() - allowed);
            return;
        }
        if (requestCode != REQ_DELETE) return;
        Track track = pendingDeleteTrack;
        String uri = pendingDeleteUri;
        pendingDeleteTrack = null;
        pendingDeleteUri = "";
        if (track == null) return;
        if (resultCode == RESULT_OK) {
            pruneFromPlaylists(uri);
            toast("Deleted \"" + track.title + "\"");
            refreshLibrary();
        } else toast("Delete cancelled");
    }

    private int indexOfUri(String uri) {
        List<Track> items = songAdapter.items();
        for (int i = 0; i < items.size(); i++) if (items.get(i).localUri.equals(uri)) return i;
        return 0;
    }

    // ---------------------------------------------------------------- playback

    private void playFromList(int index) {
        List<Track> items = adapter.items();
        if (index < 0 || index >= items.size()) return;
        List<Track> ready = new ArrayList<>();
        int start = -1;
        for (Track t : items) {
            if (t.localUri != null && !t.localUri.isEmpty()) {
                if (t == items.get(index)) start = ready.size();
                ready.add(t);
            }
        }
        if (start < 0) return;
        playback.playQueue(ready, start, collection == null ? "Cartify" : collection.name);
        openPlayer();
    }

    /**
     * Plays a search result immediately, streaming it from the network.
     *
     * The row list is the queue, so a song plays and the next one follows without the
     * user having to save anything. Rows that are already saved keep playing from disk:
     * the queue is built from {@link Track#playbackUri()}, which prefers the local file,
     * so a mixed list of saved and unsaved songs works in one press.
     */
    private void streamFromList(int index) {
        List<Track> items = adapter.items();
        if (index < 0 || index >= items.size()) return;
        List<Track> queue = new ArrayList<>();
        int start = -1;
        for (Track t : items) {
            // Every row is playable here: a saved one from disk, an unsaved one by
            // resolving its stream when it becomes current. Filtering on playbackUri()
            // skipped exactly the unsaved rows this method exists for, so nothing was
            // ever queued and the tap looked dead.
            if (t.title == null || t.title.trim().isEmpty()) continue;
            if (t == items.get(index)) start = queue.size();
            queue.add(t);
        }
        if (start < 0) return;
        playback.playQueue(queue, start, collection == null ? "Cartify" : collection.name);
        openPlayer();
    }

    private void playLibraryFrom(int index) {
        List<Track> songs = songAdapter.items();
        if (index < 0 || index >= songs.size()) return;
        playback.playQueue(new ArrayList<>(songs), index, "Library");
        openPlayer();
    }

    private void openPlayer() {
        if (playback.current() == null) return;
        startActivity(new Intent(this, PlayerActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onPlaybackChanged() {
        renderMini();
        syncNowPlayingHighlight();
        if (playlistAdapter != null) refreshPlaylists();
    }

    @Override
    public void onQueueChanged() {
        renderMini();
    }

    private void renderMini() {
        Track t = playback.current();
        if (t == null) {
            // Slide it away rather than removing it in one frame, so the list underneath
            // does not jump by the bar's height the instant a queue empties.
            if (miniPlayer.getVisibility() == View.VISIBLE) {
                miniPlayer.animate().cancel();
                miniPlayer.animate().alpha(0f).translationY(18f * getResources().getDisplayMetrics().density)
                        .setDuration(180)
                        .withEndAction(() -> {
                            miniPlayer.setVisibility(View.GONE);
                            miniPlayer.setAlpha(1f);
                            miniPlayer.setTranslationY(0f);
                        }).start();
            }
            return;
        }
        if (miniPlayer.getVisibility() != View.VISIBLE) {
            // First track of a session: rise into place instead of appearing.
            miniPlayer.animate().cancel();
            miniPlayer.setVisibility(View.VISIBLE);
            miniPlayer.setAlpha(0f);
            miniPlayer.setTranslationY(24f * getResources().getDisplayMetrics().density);
            miniPlayer.animate().alpha(1f).translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        miniTitle.setText(t.title);
        String who = t.artist.isEmpty() ? "Unknown artist" : t.artist;
        // A stream takes a second or two to resolve, and a bar that shows only the artist
        // during that time looks stuck. Saying what it is waiting for is the difference
        // between "connecting" and "broken".
        if (playback.isResolvingStream()) who = who + "  \u00b7  connecting\u2026";
        else if (playback.isStream()) who = who + "  \u00b7  streaming";
        miniArtist.setText(who);
        // While a file is still preparing the player reports "not playing"; the icon
        // follows the user's intent instead so it never flips back under the finger.
        boolean showPause = playback.isPlaying() || (playback.isLoading() && playback.wantsPlay());
        miniPlayPause.setImageResource(showPause ? R.drawable.ic_pause : R.drawable.ic_play);
        // The save button states what it can do: a song already on the device gets a
        // check instead of a download arrow, so the bar never offers a no-op.
        View miniDownload = findViewById(R.id.miniDownload);
        if (miniDownload != null) {
            boolean saved = t.localUri != null && !t.localUri.isEmpty();
            ((ImageView) miniDownload).setImageResource(
                    saved ? R.drawable.ic_check : R.drawable.ic_download);
            miniDownload.setAlpha(saved ? 0.55f : 1f);
            miniDownload.setContentDescription(saved ? "Saved to device" : "Download this song");
        }
        Art.load(this, t, miniArt);
        updateMiniProgress();
    }

    /**
     * The collapsed player's overflow: the actions a media bar cannot fit inline.
     *
     * Shuffle and repeat show their state in the label because the sheet has no toggle
     * switch; tapping flips the mode and the next open reflects it.
     */
    private void showMiniMenu(View anchor) {
        final Track t = playback.current();
        if (t == null) return;
        String subtitle = t.artist == null || t.artist.isEmpty() ? "Unknown artist" : t.artist;
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_shuffle,
                playback.isShuffle() ? "Shuffle: on" : "Shuffle: off", () -> {
            playback.setShuffle(!playback.isShuffle());
            toast(playback.isShuffle() ? "Shuffle on" : "Shuffle off");
        }));
        String repeatLabel;
        int repeatIcon;
        switch (playback.repeatMode()) {
            case Playback.REPEAT_ONE:
                repeatLabel = "Repeat: one song";
                repeatIcon = R.drawable.ic_repeat_one;
                break;
            case Playback.REPEAT_ALL:
                repeatLabel = "Repeat: all";
                repeatIcon = R.drawable.ic_repeat;
                break;
            default:
                repeatLabel = "Repeat: off";
                repeatIcon = R.drawable.ic_repeat;
                break;
        }
        items.add(new MenuSheet.Item(repeatIcon, repeatLabel, () -> {
            playback.cycleRepeat();
            String now;
            switch (playback.repeatMode()) {
                case Playback.REPEAT_ONE:
                    now = "Repeating one song";
                    break;
                case Playback.REPEAT_ALL:
                    now = "Repeating the queue";
                    break;
                default:
                    now = "Repeat off";
                    break;
            }
            toast(now);
        }));
        items.add(new MenuSheet.Item(R.drawable.ic_add, "Add to queue", () -> {
            playback.append(t);
            toast("Added to queue");
        }));
        items.add(new MenuSheet.Item(R.drawable.ic_queue, "Add to playlist",
                () -> addToPlaylist(t)));
        items.add(new MenuSheet.Item(R.drawable.ic_info, "Open full player",
                () -> openPlayer()));
        // Offered here as well as on the bar's own button, because the menu is what the
        // user opens when looking for "what else can I do with this song".
        if (t.localUri == null || t.localUri.isEmpty()) {
            items.add(new MenuSheet.Item(R.drawable.ic_download, "Download this song",
                    this::downloadCurrentTrack));
        }
        items.add(new MenuSheet.Item(R.drawable.ic_download, "Check for updates",
                this::checkForUpdatesManually));
        MenuSheet.show(this, t.title, subtitle, items.toArray(new MenuSheet.Item[0]));
    }

    private void updateMiniProgress() {
        if (miniPlayer.getVisibility() != View.VISIBLE) return;
        long dur = playback.duration();
        long pos = playback.position();
        miniProgress.setProgress(dur > 0 ? (int) (1000L * pos / dur) : 0);
    }

    private void syncNowPlayingHighlight() {
        Track t = playback.current();
        String uri = t == null ? "" : t.localUri;
        songAdapter.setPlaying(uri);
    }

    // ---------------------------------------------------------------- misc

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && SpotifyResolver.looksLikeSpotify(text)) {
                linkInput.setText(extractLink(text));
                showTab(TAB_HOME);
                resolve();
            }
        }
    }

    private static String extractLink(String text) {
        int start = text.indexOf("http");
        if (start < 0) return text.trim();
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        return text.substring(start, end);
    }

    private static List<Track> single(Track t) {
        List<Track> one = new ArrayList<>();
        one.add(t);
        return one;
    }

    private void setBusy(boolean busy, String message) {
        resolveButton.setEnabled(!busy);
        resolveButton.setText(busy ? "Loading\u2026" : "Load track list");
        statusText.setText(message);
        pulseBusy(busy);
    }

    /**
     * Breathes the status line while work is in flight.
     *
     * A search takes a network round trip, and a line of static text for several seconds
     * reads as a frozen app. Fading between two alphas is cheap, needs no extra view, and
     * stops the moment the work finishes.
     */
    private void pulseBusy(boolean busy) {
        if (statusText == null) return;
        statusText.animate().cancel();
        if (!busy) {
            statusText.setAlpha(1f);
            return;
        }
        statusText.setAlpha(0.45f);
        statusText.animate().alpha(1f).setDuration(700)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> pulseBusy(true)).start();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(linkInput.getWindowToken(), 0);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Re-ask for audio access. If the user permanently denied it, Android no longer
     * shows the dialog, so send them to the app's settings page instead of doing nothing.
     */
    private void requestAudioAccessOrSettings() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            refreshLibrary();
            return;
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
            ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_NOTIFICATIONS);
        } else {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(settings);
            toast("Enable music access in Settings");
        }
    }

    private void requestNotificationPermission() {
        List<String> wanted = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // Without read access MediaPlayer and MediaMetadataRetriever cannot open the
        // MP3s we saved, which shows up as silent playback and blank artwork.
        String audioPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, audioPerm)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(audioPerm);
        }
        if (!wanted.isEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toArray(new String[0]), REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // The first library scan runs before the prompt is answered, so it comes back
        // empty. Rescan once the user has decided, otherwise the app looks like it has
        // no music at all until the next cold start.
        if (requestCode == REQ_NOTIFICATIONS) refreshLibrary();
    }

    /** True when this build can read the MP3s it saved. */
    private boolean hasAudioAccess() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Fires only on the transition from "nothing started" to "something started", so an
        // in-app jump to the player and back does not replay the intro, while returning from
        // the launcher or Recents does.
        if (AppLifecycle.onActivityStarted()) {
            splashShown = false;
            playIntro();
        }
    }

    @Override
    protected void onStop() {
        AppLifecycle.onActivityStopped();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the "install unknown apps" page: if the user allowed it, the
        // update they already consented to starts without asking again.
        if (awaitingInstallPermission && !updateDownloading
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls())) {
            awaitingInstallPermission = false;
            // If the APK already landed, install it. Re-downloading a file that is
            // sitting in the cache was pure waste, and on a slow link it looked like
            // the permission grant had done nothing.
            if (downloadedUpdate != null && downloadedUpdate.exists()) {
                Updater.install(this, downloadedUpdate);
            } else if (pendingUpdate != null) {
                downloadUpdate(pendingUpdate);
            }
        }
        renderMini();
        syncNowPlayingHighlight();
        if (playlistAdapter != null) refreshPlaylists();
    }

    @Override
    protected void onDestroy() {
        // This pool belongs to the activity, not the process. Leaving it running meant
        // every rotation or return to the app leaked one thread for good.
        io.shutdownNow();
        ProgressBus.unregister(this);
        playback.removeListener(this);
        main.removeCallbacks(miniTicker);
        stopUpdatePulse();
        if (splash != null) splash.stop();
        super.onDestroy();
    }

    /** Minimal TextWatcher so no extra dependency is needed. */
    private static class SimpleWatcher implements android.text.TextWatcher {
        interface OnText {
            void onChanged(boolean hasText);
        }

        private final OnText callback;

        SimpleWatcher(OnText callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            callback.onChanged(s.length() > 0);
        }
    }
}
