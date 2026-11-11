package com.cartify.app;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen player: large swipeable artwork, seekable progress and the live queue.
 * Swiping the artwork moves through the queue, exactly like the streaming apps.
 */
public class PlayerActivity extends AppCompatActivity implements Playback.Listener {

    private Playback playback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ViewPager2 pager;
    private ArtPagerAdapter pagerAdapter;
    private ImageView backdrop;
    /** Key of the track whose blurred wash is currently on screen. */
    private String backdropFor = "";
    private TextView title;
    private TextView artist;
    private TextView source;
    private TextView timeNow;
    private TextView timeTotal;
    private SeekBar seek;
    /** Animated neon waveform used as the seek head. */
    private WaveThumb seekThumb;
    private ImageButton playPause;
    private ImageButton shuffle;
    private ImageButton repeat;
    private LinearLayout queueSheet;
    private TextView queueSource;
    private RecyclerView queueList;
    private QueueAdapter queueAdapter;
    private TextView lyricsState;
    private RecyclerView lyricsList;
    private LinearLayoutManager lyricsLayout;
    private LyricAdapter lyricAdapter;
    private final List<Lyrics.Line> lyricLines = new ArrayList<>();
    private int activeLine = -1;
    private boolean lyricsExpanded;
    private View lyricsPanel;
    /** Zero-height slot directly under the artwork that the lyric sheet grows into. */
    private View lyricsHost;
    private View lyricsListHost;
    private View lyricsFade;
    private View artCard;
    private View topSpace;
    private View bottomSpace;
    private android.animation.ValueAnimator lyricsAnim;
    /** Last track shown, so the artwork can slide in the direction the queue moved. */
    private int lastIndex = -1;
    /** True while the pager is already gliding to the new page, so bindAll does not slide it twice. */
    private boolean fromSwipe;
    private TextView pillText;
    private ImageButton playerMore;
    private ImageButton playerDownload;
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private String lyricsFor = "";

    private boolean draggingSeek;
    private boolean syncingPager;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            main.postDelayed(this, 250);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        AppLifecycle.onActivityStarted();
    }

    @Override
    protected void onStop() {
        AppLifecycle.onActivityStopped();
        super.onStop();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        playback = Playback.get(this);

        pager = findViewById(R.id.artPager);
        backdrop = findViewById(R.id.playerBackdrop);
        title = findViewById(R.id.playerTitle);
        artist = findViewById(R.id.playerArtist);
        source = findViewById(R.id.playerSource);
        timeNow = findViewById(R.id.timeNow);
        timeTotal = findViewById(R.id.timeTotal);
        seek = findViewById(R.id.seek);
        seekThumb = new WaveThumb(this);
        // A real drawable object (not the XML shape) so the wave can breathe with the song.
        seek.setThumb(seekThumb);
        seek.setSplitTrack(false);
        // Half the waveform on each side: the head sits fully inside the bar even at
        // 0:00 and at the very end, instead of hanging off the track edge.
        int halfThumb = seekThumb.getIntrinsicWidth() / 2;
        seek.setPadding(halfThumb, seek.getPaddingTop(), halfThumb, seek.getPaddingBottom());
        playPause = findViewById(R.id.playPauseButton);
        shuffle = findViewById(R.id.shuffleButton);
        repeat = findViewById(R.id.repeatButton);
        queueSheet = findViewById(R.id.queueSheet);
        queueSource = findViewById(R.id.queueSource);
        queueList = findViewById(R.id.queueList);
        lyricsState = findViewById(R.id.lyricsState);
        lyricsList = findViewById(R.id.lyricsList);
        lyricsPanel = findViewById(R.id.lyricsPanel);
        lyricsHost = findViewById(R.id.lyricsHost);
        lyricsListHost = findViewById(R.id.lyricsListHost);
        lyricsFade = findViewById(R.id.lyricsFade);
        artCard = findViewById(R.id.artCard);
        topSpace = findViewById(R.id.topSpace);
        bottomSpace = findViewById(R.id.bottomSpace);
        pillText = findViewById(R.id.pillText);
        playerMore = findViewById(R.id.playerMore);
        playerDownload = findViewById(R.id.playerDownload);
        findViewById(R.id.pillQueue).setOnClickListener(v -> toggleQueue());
        playerMore.setOnClickListener(v -> showTrackMenu());
        // Saving the song while it plays. The player is where the user is most likely to
        // decide to keep a stream, so it must not require the overflow menu.
        playerDownload.setOnClickListener(v -> downloadCurrentTrack());
        // The reference player opens on the artwork card; a small rise-in sells the entrance.
        pager.setAlpha(0f);
        pager.setScaleX(0.94f);
        pager.setScaleY(0.94f);
        pager.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).start();
        // Retry a failed lookup: the lyrics service is occasionally busy, and a song that
        // came back wrong can be asked for again.
        findViewById(R.id.lyricsRetry).setOnClickListener(v -> {
            Track current = playback.current();
            if (current == null) return;
            Lyrics.forget(this, current);
            lyricLines.clear();
            activeLine = -1;
            lyricAdapter.notifyDataSetChanged();
            showLyrics(false);
            lyricsState.setVisibility(View.VISIBLE);
            lyricsState.setText("Looking up lyrics\u2026");
            lyricsFor = "";
            loadLyrics();
            Toast.makeText(this, "Asking for a better match", Toast.LENGTH_SHORT).show();
        });
        lyricsState.setOnClickListener(v -> {
            Track current = playback.current();
            if (current == null) return;
            Lyrics.forget(this, current);
            lyricsFor = "";
            loadLyrics();
        });

        lyricAdapter = new LyricAdapter(lyricLines, timeMs -> {
            // Jump the song to the tapped line; the highlight follows on the next tick.
            playback.seekTo(timeMs);
            previewLyricsAt(timeMs);
        });
        lyricsLayout = new LinearLayoutManager(this);
        lyricsList.setLayoutManager(lyricsLayout);
        lyricsList.setAdapter(lyricAdapter);
        lyricsList.setClipToPadding(false);
        // Item animations cross-fade during the constant re-binding that karaoke needs,
        // which left the highlighted line stuck half-faded.
        lyricsList.setItemAnimator(null);
        // Half-viewport padding lets the first and last lines reach the centre, but no
        // more than the sheet is tall, otherwise the first line opens off-screen.
        lyricsList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = lyricsList.getHeight();
            if (h <= 0 || h == ob - ot) return;
            int pad = Math.min(h / 2 - 24, Math.max(24, h / 3));
            lyricsList.setPadding(0, pad, 0, pad);
            scrollToActive();
        });

        pagerAdapter = new ArtPagerAdapter();
        pager.setAdapter(pagerAdapter);
        pager.setOffscreenPageLimit(1);
        // A gentle parallax while the artwork is swiped: the outgoing cover drifts slightly
        // slower than the finger and shrinks a touch, the incoming one settles up to size.
        // Without it the cards simply slide past each other like two photographs, which
        // reads as flat on a screen this large. The values are deliberately small â€” a big
        // transform here fights the card's own rounded edges and starts to look cheap.
        // Alpha only. This used to scale every page to 0.94 and shift it by up to 18dp,
        // which opened a gap at the card's edge that the NEXT page - already attached
        // because offscreenPageLimit is 1 - showed through as a strip of unrelated
        // artwork. ViewPager2 does not clip a child that shrinks or moves, so the only
        // transform that cannot leak a neighbour is one that keeps the page in its slot.
        pager.setPageTransformer((page, position) -> {
            final float distance = Math.min(1f, Math.abs(position));
            page.setAlpha(1f - 0.25f * distance);
        });
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (syncingPager) return;
                if (position != playback.index() && position < playback.queue().size()) {
                    // The pager is already gliding to this page; do not slide it twice.
                    fromSwipe = true;
                    playback.playAt(position, true);
                }
            }
        });

        queueAdapter = new QueueAdapter(new QueueAdapter.Actions() {
            @Override
            public void onPick(int index) {
                if (index >= 0 && index < playback.queue().size()) {
                    playback.playAt(index, true);
                    queueSheet.setVisibility(View.GONE);
                }
            }

            @Override
            public void onRemove(int index) {
                playback.removeAt(index);
            }
        });
        queueList.setLayoutManager(new LinearLayoutManager(this));
        queueList.setAdapter(queueAdapter);

        findViewById(R.id.collapseButton).setOnClickListener(v -> finish());
        findViewById(R.id.queueButton).setOnClickListener(v -> toggleQueue());
        findViewById(R.id.closeQueue).setOnClickListener(v -> queueSheet.setVisibility(View.GONE));
        findViewById(R.id.prevButton).setOnClickListener(v -> playback.previous());
        findViewById(R.id.nextButton).setOnClickListener(v -> playback.next(false));
        playPause.setOnClickListener(v -> {
            playback.toggle();
            // A small press-then-settle on the main transport. On a control this size the
            // tap is the whole interaction, and a button that does not acknowledge it feels
            // unresponsive even when the music starts immediately.
            playPause.animate().cancel();
            playPause.setScaleX(0.9f);
            playPause.setScaleY(0.9f);
            playPause.animate().scaleX(1f).scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                    .start();
        });
        // The side controls get the same acknowledgement, scaled down so they do not
        // compete with the main button.
        pressBounce(findViewById(R.id.prevButton));
        pressBounce(findViewById(R.id.nextButton));
        shuffle.setOnClickListener(v -> playback.setShuffle(!playback.isShuffle()));
        repeat.setOnClickListener(v -> playback.cycleRepeat());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    long dur = playback.duration();
                    timeNow.setText(fmt(progress * dur / 1000));
                    previewLyricsAt(dur * progress / 1000);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                draggingSeek = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                draggingSeek = false;
                long dur = playback.duration();
                if (dur > 0) playback.seekTo(dur * bar.getProgress() / 1000);
            }
        });

        playback.addListener(this);
        bindAll();
        main.post(ticker);
    }

    /**
     * A quick dip and return for a transport button, layered on top of its own action.
     *
     * This must not install the click listener itself: prev/next already have one, and
     * replacing it would have silently turned both buttons into decoration.
     */
    private static void pressBounce(final android.view.View view) {
        if (view == null) return;
        final Runnable bounce = () -> {
            view.animate().cancel();
            view.setScaleX(0.86f);
            view.setScaleY(0.86f);
            view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                    .start();
        };
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) bounce.run();
            return false;   // never consume: the real click must still arrive
        });
    }

    private void toggleQueue() {
        if (queueSheet.getVisibility() == View.VISIBLE) {
            queueSheet.setVisibility(View.GONE);
        } else {
            refreshQueue();
            queueSheet.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Loads the lyric sheet that sits under the artwork, Spotify style.
     *
     * Cached lyrics are shown immediately; otherwise a lookup runs in the background
     * and the sheet fills in as soon as the result arrives.
     */
    /**
     * Identity of the song whose lyrics are on screen.
     *
     * Prefers the catalogue id: artist+title+length cannot tell two uploads apart, and
     * that is exactly how one song's sheet ended up under another. The string is only
     * used to drop a late answer for a track the user has already moved past.
     */
    private static String lyricsKey(Track t) {
        if (t == null) return "";
        String id = t.id == null ? "" : t.id.trim();
        if (!id.isEmpty()) return "id:" + id;
        return t.title + "|" + t.artist + "|" + t.durationMs;
    }

    private void loadLyrics() {
        Track t = playback.current();
        if (t == null) return;
        final String key = lyricsKey(t);
        lyricsFor = key;
        String cached = Lyrics.cached(this, t);
        if (cached != null) {
            showLyrics(cached);
            return;
        }
        lyricLines.clear();
        activeLine = -1;
        lyricAdapter.notifyDataSetChanged();
        lyricsState.setVisibility(View.VISIBLE);
        lyricsState.setText("Looking up lyrics\u2026");
        final Track target = t;
        io.execute(() -> {
            final String found = Lyrics.fetch(PlayerActivity.this, target);
            main.post(() -> {
                if (!key.equals(lyricsFor)) return;
                showLyrics(found);
            });
        });
    }

    private void showLyrics(String lrc) {
        lyricLines.clear();
        activeLine = -1;
        boolean has = lrc != null && !lrc.trim().isEmpty();
        if (has) lyricLines.addAll(Lyrics.parse(lrc));
        lyricAdapter.notifyDataSetChanged();
        // Lyrics stay folded until the artwork is tapped; the player opens artwork-first.
        if (!lyricsExpanded) {
            showLyrics(false);
            lyricsState.setVisibility(View.GONE);
            return;
        }
        lyricsPanel.setVisibility(View.VISIBLE);
        if (!has) {
            showLyrics(false);
            lyricsState.setVisibility(View.VISIBLE);
            lyricsState.setText("No synced lyrics for this track");
            return;
        }
        lyricsState.setVisibility(View.GONE);
        showLyrics(true);
        syncLyrics();
        scrollToActive();
    }

    /**
     * Shows or hides the lyric rows together with the box that hosts them.
     *
     * The rows live inside a FrameLayout so the fade scrim can be drawn over their lower
     * edge. That host carries layout_weight="1", and a GONE child's weight is skipped - so
     * if the host stayed visible while the list was empty, the panel would keep a large blank
     * area in the loading and error states. Every visibility change goes through here so the
     * host, the rows and the scrim can never disagree.
     */
    private void showLyrics(boolean visible) {
        int state = visible ? View.VISIBLE : View.GONE;
        if (lyricsList != null) lyricsList.setVisibility(state);
        if (lyricsFade != null) lyricsFade.setVisibility(state);
        if (lyricsListHost != null) lyricsListHost.setVisibility(state);
    }

    /**
     * Tapping the artwork (or "Show lyrics" in the menu) unfolds the lyric sheet.
     *
     * The sheet lives in its own slot directly below the square artwork and shares its
     * width, so it always lines up edge-to-edge with the cover. Opening animates the
     * slot's height while the two spacers collapse, which keeps the artwork square and
     * growing out of the bottom edge of the sheet instead of covering the cover.
     */
    private void toggleLyrics() {
        setLyricsExpanded(!lyricsExpanded);
    }

    private void setLyricsExpanded(boolean expand) {
        boolean was = lyricsExpanded;
        lyricsExpanded = expand;

        if (expand) {
            Track t = playback.current();
            String cached = t == null ? null : Lyrics.cached(this, t);
            if (cached != null) showLyrics(cached);
            else if (!lyricLines.isEmpty()) {
                showLyrics(true);
                lyricsState.setVisibility(View.GONE);
                syncLyrics();
            } else {
                lyricsState.setVisibility(View.VISIBLE);
                lyricsState.setText("Looking up lyrics\u2026");
                loadLyrics();
            }
            lyricsPanel.setVisibility(View.VISIBLE);
            lyricsPanel.setAlpha(1f);
            animateLyricsHeight(true);
            shrinkArtwork(true);
        } else {
            if (!was) return;
            animateLyricsHeight(false);
            shrinkArtwork(false);
        }
    }

    /**
     * Folds the lyric sheet away without animating.
     *
     * Called on a track change: the new song starts on its artwork with lyrics closed,
     * and a collapse animation racing the artwork slide would read as a glitch.
     */
    private void hideLyricsImmediately() {
        if (lyricsAnim != null) lyricsAnim.cancel();
        lyricsExpanded = false;
        lyricsHost.getLayoutParams().height = 0;
        lyricsHost.requestLayout();
        lyricsPanel.setVisibility(View.GONE);
        lyricsPanel.setAlpha(0f);
        showLyrics(false);
        lyricsState.setVisibility(View.GONE);
        lyricLines.clear();
        activeLine = -1;
        lyricAdapter.notifyDataSetChanged();
    }

    /**
     * Grows or shrinks the lyric slot.
     *
     * The two weighted spacers above and below the artwork share whatever room is left,
     * so giving the sheet a real height automatically squeezes them by the same amount
     * and the square artwork keeps its size and its place. Only the slot is animated;
     * touching the spacers directly would fight their layout weights.
     */
    private void animateLyricsHeight(boolean expand) {
        if (lyricsAnim != null) lyricsAnim.cancel();
        final int startH = lyricsHost.getLayoutParams().height;
        final int full = Math.max(1, availableLyricsHeight());
        final int endH = expand ? full : 0;
        if (startH == endH) {
            lyricsHost.getLayoutParams().height = endH;
            lyricsHost.requestLayout();
            if (!expand) lyricsPanel.setVisibility(View.GONE);
            return;
        }
        lyricsAnim = android.animation.ValueAnimator.ofInt(startH, endH);
        lyricsAnim.setDuration(expand ? 320 : 240);
        lyricsAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        lyricsAnim.addUpdateListener(a -> {
            lyricsHost.getLayoutParams().height = (int) a.getAnimatedValue();
            lyricsHost.requestLayout();
        });
        lyricsAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (lyricsExpanded) {
                    lyricsList.post(PlayerActivity.this::scrollToActive);
                    return;
                }
                lyricsPanel.setVisibility(View.GONE);
                showLyrics(false);
                lyricsState.setVisibility(View.GONE);
            }
        });
        lyricsAnim.start();
    }

    /** Room the sheet may take: most of what the spacers hold, leaving a margin around. */
    private int availableLyricsHeight() {
        // The sheet may use everything the spacers hold, plus what the artwork gives
        // back when it shrinks. A single line still needs a fixed minimum, otherwise a
        // short song would unfold into a sliver and clip its own text.
        int free = topSpace.getHeight() + bottomSpace.getHeight();
        int shrink = (int) (72 * getResources().getDisplayMetrics().density);
        int min = (int) (150 * getResources().getDisplayMetrics().density);
        if (free <= 0) {
            // Not measured yet (first open before layout); fall back to a share of the screen.
            int screen = getResources().getDisplayMetrics().heightPixels;
            return Math.max(min, (int) (screen * 0.42f));
        }
        return Math.max(min, free + shrink);
    }

    /**
     * Gives the lyric sheet more room by shrinking the artwork a little.
     *
     * The artwork is the one element with slack, so trading 72dp of it for the sheet
     * keeps every other control exactly where the finger expects it.
     */
    private void shrinkArtwork(boolean shrink) {
        if (artCard == null) return;
        float density = getResources().getDisplayMetrics().density;
        float target = shrink ? 72f * density : 0f;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(
                artCard.getTranslationY(), 0f);
        final float from = artCard.getTranslationY();
        float[] unused = null;
        // Vertical scale is derived from the same curve so the card stays square.
        float startScale = 1f - Math.abs(from) / Math.max(1f, artCard.getHeight());
        float endScale = shrink
                ? (artCard.getHeight() - target) / Math.max(1f, artCard.getHeight())
                : 1f;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(
                Math.abs(from), shrink ? target : 0f);
        va.setDuration(shrink ? 320 : 240);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            float lost = (float) a.getAnimatedValue();
            float scale = (artCard.getHeight() - lost) / Math.max(1f, artCard.getHeight());
            artCard.setScaleY(Math.max(0.5f, scale));
            artCard.setPivotY(artCard.getHeight());
        });
        va.start();
        if (anim != null) anim.cancel();
    }

    /**
     * Saves the song that is playing, without disturbing playback.
     *
     * The download runs in the foreground service and reports through ProgressBus, so the
     * stream keeps sounding while the file is fetched; the next start of this song opens
     * from disk. A song already saved says so instead of queueing a pointless transfer.
     */
    private void downloadCurrentTrack() {
        Track t = playback.current();
        if (t == null) return;
        if (t.localUri != null && !t.localUri.isEmpty()) {
            Toast.makeText(this, "Already saved to your device", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadState.download(this, t,
                playback.source().isEmpty() ? "Cartify" : playback.source());
    }

    /** Overflow menu next to the title: song actions without leaving the player. */
    private void showTrackMenu() {
        Track t = playback.current();
        if (t == null) return;
        String subtitle = (t.artist == null || t.artist.isEmpty() ? "Unknown artist" : t.artist);
        // Saving the song that is playing is the one action a stream needs most: it is
        // the difference between "heard it once" and "kept it". Offered only when there
        // is no local file, because saving an already-saved song does nothing.
        MenuSheet.Item save = t.localUri.isEmpty()
                ? new MenuSheet.Item(R.drawable.ic_download, "Download this song",
                () -> DownloadState.download(this, t,
                        playback.source().isEmpty() ? "Cartify" : playback.source()))
                : new MenuSheet.Item(R.drawable.ic_download, "Saved to device", true, () -> {
        });
        MenuSheet.show(this, t.title, subtitle,
                save,
                new MenuSheet.Item(R.drawable.ic_next, "Play next", () -> {
                    playback.playNext(t);
                    Toast.makeText(this, "Playing next", Toast.LENGTH_SHORT).show();
                }),
                new MenuSheet.Item(R.drawable.ic_add, "Add to queue", () -> {
                    playback.append(t);
                    Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show();
                }),
                new MenuSheet.Item(R.drawable.ic_lyrics,
                        lyricsExpanded ? "Hide lyrics" : "Show lyrics", () -> {
                    setLyricsExpanded(!lyricsExpanded);
                    Toast.makeText(this, lyricsExpanded ? "Lyrics shown" : "Lyrics hidden",
                            Toast.LENGTH_SHORT).show();
                }),
                new MenuSheet.Item(R.drawable.ic_info, "Song info", () -> showSongInfo(t)),
                new MenuSheet.Item(R.drawable.ic_music_note,
                        playback.sleepMinutesLeft() > 0
                                ? "Sleep timer \u00b7 " + playback.sleepMinutesLeft() + " min left"
                                : "Sleep timer",
                        this::showSleepTimer),
                new MenuSheet.Item(R.drawable.ic_queue, "Open queue", this::toggleQueue));
    }

    /**
     * Sleep timer sheet.
     *
     * Every option arms the timer from now, so re-opening this sheet and picking a
     * different duration replaces the old countdown rather than stacking a second one.
     */
    private void showSleepTimer() {
        int left = playback.sleepMinutesLeft();
        String subtitle = left > 0
                ? "Stops in " + left + " min"
                : "Playback pauses when the timer runs out";
        java.util.List<MenuSheet.Item> items = new java.util.ArrayList<>();
        int[] options = {15, 30, 45, 60};
        for (int minutes : options) {
            items.add(new MenuSheet.Item(R.drawable.ic_music_note, minutes + " minutes", () -> {
                playback.setSleepTimer(minutes);
                Toast.makeText(this, "Sleep timer: " + minutes + " min", Toast.LENGTH_SHORT).show();
            }));
        }
        if (left > 0) {
            items.add(new MenuSheet.Item(R.drawable.ic_close, "Turn timer off", () -> {
                playback.setSleepTimer(0);
                Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show();
            }));
        }
        MenuSheet.show(this, "Sleep timer", subtitle,
                items.toArray(new MenuSheet.Item[0]));
    }

    /** Song details in the same glass sheet, so nothing falls back to a system dialog. */
    private void showSongInfo(Track t) {
        MenuSheet.show(this, t.title, "Artist \u00b7 "
                        + (t.artist.isEmpty() ? "Unknown" : t.artist)
                        + "\nAlbum \u00b7 " + (t.album == null || t.album.isEmpty() ? "\u2014" : t.album)
                        + "\nPlaying from \u00b7 "
                        + (playback.source().isEmpty() ? "Cartify" : playback.source()),
                new MenuSheet.Item(R.drawable.ic_music_note, "Close", () -> {
                }));
    }

    /** Highlights the line that matches the playback position, karaoke style. */
    private void syncLyrics() {
        if (lyricLines.isEmpty() || lyricsList.getVisibility() != View.VISIBLE) return;
        int now = Lyrics.activeLine(lyricLines, playback.position());
        if (now == activeLine) return;
        activeLine = now;
        lyricAdapter.setActive(now);
        scrollToActive();
    }

    /**
     * Centres the line being sung and glides to it, so a lyric change reads as motion
     * rather than a jump cut.
     */
    private void scrollToActive() {
        if (activeLine < 0 || activeLine >= lyricLines.size()) return;
        final int target = activeLine;
        lyricsList.post(() -> {
            int half = lyricsList.getHeight() / 2;
            lyricsLayout.scrollToPositionWithOffset(target, half);
            // The offset lands the row at the top; nudge it back to the middle.
            lyricsList.post(() -> {
                View row = lyricsLayout.findViewByPosition(target);
                if (row == null) return;
                int shift = (row.getTop() + row.getHeight() / 2) - half;
                if (Math.abs(shift) < 1) return;
                lyricsList.stopScroll();
                lyricsList.smoothScrollBy(0, shift);
            });
        });
    }

    /**
     * Moves the highlight to whatever line sits at {@code positionMs}, without touching
     * the audio. Used while the seek bar is dragged so the sheet tracks the thumb.
     */
    private void previewLyricsAt(long positionMs) {
        if (lyricLines.isEmpty() || lyricsList.getVisibility() != View.VISIBLE) return;
        int now = Lyrics.activeLine(lyricLines, positionMs);
        if (now == activeLine) return;
        activeLine = now;
        lyricAdapter.setActive(now);
        scrollToActive();
    }

    private void refreshQueue() {
        List<Track> q = playback.queue();
        queueAdapter.submit(q, playback.index());
        queueSource.setText(q.size() + " track" + (q.size() == 1 ? "" : "s")
                + (playback.source().isEmpty() ? "" : " \u00b7 " + playback.source()));
        scrollQueueToCurrent();
    }

    private void scrollQueueToCurrent() {
        int i = playback.index();
        if (i >= 0) queueList.scrollToPosition(i);
    }

    @Override
    public void onPlaybackChanged() {
        bindAll();
    }

    @Override
    public void onQueueChanged() {
        pagerAdapter.notifyDataSetChanged();
        bindAll();
        if (queueSheet.getVisibility() == View.VISIBLE) refreshQueue();
    }

    private void bindAll() {
        Track t = playback.current();
        if (t == null) {
            finish();
            return;
        }
        title.setText(t.title);
        artist.setText(t.artist.isEmpty() ? "Unknown artist" : t.artist);
        String from = playback.source().isEmpty() ? "Cartify" : playback.source();
        // A streamed song is not on the device, and the player has to say so: otherwise
        // it reads as a normal saved track that will simply stop working offline.
        source.setText(playback.isStream() ? from + "  \u00b7  streaming" : from);
        // The save button reflects reality: a song already on the device cannot be saved
        // again, and one that is still streaming is exactly what this button is for.
        if (playerDownload != null) {
            boolean saved = t.localUri != null && !t.localUri.isEmpty();
            playerDownload.setImageResource(
                    saved ? R.drawable.ic_check : R.drawable.ic_download);
            playerDownload.setAlpha(saved ? 0.55f : 1f);
            playerDownload.setContentDescription(saved ? "Saved to device" : "Download this song");
        }
        pillText.setText(playback.queue().size() + " song"
                + (playback.queue().size() == 1 ? "" : "s") + " \u00b7 "
                + (playback.source().isEmpty() ? "Cartify" : playback.source()));
        playPause.setImageResource(playback.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        shuffle.setImageTintList(getColorStateList(playback.isShuffle()
                ? R.color.accent : R.color.text_secondary));
        int rep = playback.repeatMode();
        repeat.setImageResource(rep == Playback.REPEAT_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setImageTintList(getColorStateList(rep == Playback.REPEAT_OFF
                ? R.color.text_secondary : R.color.accent));

        if (pagerAdapter.getItemCount() != playback.queue().size()) {
            pagerAdapter.notifyDataSetChanged();
        }
        int index = playback.index();
        if (pager.getCurrentItem() != index && index >= 0) {
            if (fromSwipe) {
                // The pager is mid-gesture already; let it land on its own.
                syncingPager = true;
                main.postDelayed(() -> syncingPager = false, 350);
            } else {
                animateTrackChange(index, lastIndex);
            }
        }
        lastIndex = index;

        // Lyrics belong to the song, so reload them whenever the track changes.
        String key = t.title + "|" + t.artist + "|" + t.durationMs;
        if (!key.equals(lyricsFor)) {
            // Every new song opens on its artwork: the lyric sheet is opt-in per track,
            // so switching songs (next, previous or a swipe) folds it back down.
            hideLyricsImmediately();
            loadLyrics();
        }
        updateBackdrop(t);
        // A new song is a new set of lines, even if the song is the same one repeated.
        fromSwipe = false;

        updateProgress();
    }

    /**
     * Animates the artwork to the new track, always in the direction the song moved:
     * next slides in from the right, previous from the left.
     *
     * A hop to a neighbouring track uses the pager's own page slide, which shows the
     * outgoing and incoming covers together â€” the closest thing to physically pushing
     * the card aside. Anything further (a queue tap, a shuffled jump) would mean
     * scrolling through every page in between, so those are cut and the entering cover
     * is glided in from the correct side instead.
     */
    private void animateTrackChange(int index, int previous) {
        syncingPager = true;
        boolean forward = previous < 0 || index > previous;
        boolean neighbour = previous >= 0 && Math.abs(index - previous) == 1;

        // A leftover offset from a previous glide must not tilt the native page slide.
        pager.animate().cancel();
        pager.setTranslationX(0f);
        pager.setAlpha(1f);

        if (neighbour) {
            pager.setCurrentItem(index, true);
            main.postDelayed(() -> syncingPager = false, 420);
            slideText(forward);
            return;
        }

        // Long jump: cut to the new page, then glide it in from the side it came from.
        int width = pager.getWidth() > 0
                ? pager.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        final float offset = (forward ? 1f : -1f) * width * 0.55f;

        pager.setCurrentItem(index, false);
        pager.setTranslationX(offset);
        pager.setAlpha(0.35f);
        pager.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(340)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    pager.setTranslationX(0f);
                    pager.setAlpha(1f);
                })
                .start();

        slideText(forward);
        main.postDelayed(() -> syncingPager = false, 400);
    }

    /** Title and artist trail the artwork a little, in the same direction. */
    private void slideText(boolean forward) {
        final float dir = forward ? 1f : -1f;
        View[] views = {title, artist};
        final float[] offsets = {dir * 44f, dir * 30f};
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            v.animate().cancel();
            v.setTranslationX(offsets[i]);
            v.setAlpha(0f);
            v.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(320)
                    .setStartDelay(i * 25L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    /**
     * Rebuilds the frosted artwork wash behind the player.
     *
     * Blurring happens on the IO executor; the finished bitmap cross-fades in so the
     * backdrop changes with the track instead of snapping.
     */
    private void updateBackdrop(Track t) {
        final String key = Art.key(t);
        if (key.isEmpty()) return;
        if (key.equals(backdropFor) && backdrop.getDrawable() != null) return;
        backdropFor = key;
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            // Brightness 1: the wash is a direct, glowing copy of the cover, not a dimmed one.
            final Bitmap wash = Blur.of(Art.full(app, t), 3, 1f);
            if (wash == null) return;
            main.post(() -> {
                if (!key.equals(backdropFor) || isFinishing()) return;
                backdrop.setImageBitmap(wash);
                backdrop.setAlpha(0f);
                backdrop.animate().alpha(1f).setDuration(420).start();
            });
        });
    }

    private void updateProgress() {
        long dur = playback.duration();
        long pos = playback.position();
        timeTotal.setText(fmt(dur));
        if (!draggingSeek) {
            timeNow.setText(fmt(pos));
            seek.setProgress(dur > 0 ? (int) (1000L * pos / dur) : 0);
        }
        boolean wantPause = playback.isPlaying()
                || (playback.isLoading() && playback.wantsPlay());
        if (wantPause != (playPause.getTag() != null && (Boolean) playPause.getTag())) {
            playPause.setTag(wantPause);
            playPause.setImageResource(wantPause ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        // The waveform only breathes while sound is actually coming out of the speaker.
        if (seekThumb != null) seekThumb.setPlaying(playback.isPlaying());
        syncLyrics();
    }

    private static String fmt(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) main.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        if (io != null) io.shutdownNow();
        main.removeCallbacks(ticker);
        playback.removeListener(this);
        if (seekThumb != null) seekThumb.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (queueSheet.getVisibility() == View.VISIBLE) {
            queueSheet.setVisibility(View.GONE);
        } else {
            super.onBackPressed();
        }
    }

    /** One full-bleed artwork page per queued track. */
    private class ArtPagerAdapter extends RecyclerView.Adapter<ArtPagerAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_page_art, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            List<Track> q = playback.queue();
            if (position < 0 || position >= q.size()) return;
            Track t = q.get(position);
            h.placeholder.setVisibility(View.VISIBLE);
            Art.load(PlayerActivity.this, t, h.art,
                    found -> h.placeholder.setVisibility(found ? View.GONE : View.VISIBLE));
            h.itemView.setOnClickListener(v -> {
                if (position != playback.index()) playback.playAt(position, true);
                else toggleLyrics();   // tapping the current cover folds/unfolds lyrics
            });
            // Each page settles in with a gentle scale so swiping feels physical.
            h.itemView.setScaleX(0.97f);
            h.itemView.setScaleY(0.97f);
            h.itemView.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
        }

        @Override
        public int getItemCount() {
            return playback.queue().size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView art;
            final ImageView placeholder;

            VH(@NonNull View v) {
                super(v);
                art = v.findViewById(R.id.pageArt);
                placeholder = v.findViewById(R.id.pagePlaceholder);
            }
        }
    }
}

