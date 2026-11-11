package com.cartify.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One playlist: its cover, its songs, and every action that changes it.
 *
 * The playlist is re-read from the store on every resume, so a song added from another
 * screen shows up without any cross-activity callback plumbing.
 */
public class PlaylistActivity extends AppCompatActivity implements Playback.Listener {

    public static final String EXTRA_ID = "playlist_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Playlist playlist;
    private SongAdapter adapter;
    private Playback playback;

    private ImageView hero;
    private ImageView heroFallback;
    private TextView title;
    private TextView meta;
    private RecyclerView songs;
    private LinearLayout empty;
    private TextView playButton;
    /** Filters the songs already in this playlist. */
    private android.widget.EditText search;
    /** The filter text, applied by {@link #visibleTracks()}. */
    private String filter = "";

    /** Every saved song, loaded once so the picker opens instantly. */
    private final List<Track> library = new ArrayList<>();

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
        setContentView(R.layout.activity_playlist);
        playback = Playback.get(this);
        playback.addListener(this);

        String id = getIntent().getStringExtra(EXTRA_ID);
        playlist = id == null ? null : PlaylistStore.byId(this, id);
        if (playlist == null) {
            Toast.makeText(this, "Playlist not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        setupList();
        render();
        loadLibrary();
    }

    private void bindViews() {
        hero = findViewById(R.id.plHero);
        heroFallback = findViewById(R.id.plHeroFallback);
        title = findViewById(R.id.plTitle);
        meta = findViewById(R.id.plMeta);
        songs = findViewById(R.id.plSongs);
        empty = findViewById(R.id.plEmpty);
        search = findViewById(R.id.plSearch);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                filter = e == null ? "" : e.toString();
                render();
            }
        });
        playButton = findViewById(R.id.plPlay);

        ImageButton back = findViewById(R.id.plBack);
        back.setOnClickListener(v -> finish());
        findViewById(R.id.plOverflow).setOnClickListener(this::showMenu);
        playButton.setOnClickListener(v -> play(false));
        findViewById(R.id.plShuffle).setOnClickListener(v -> play(true));
        findViewById(R.id.plAdd).setOnClickListener(v -> addSongs());

        // Tapping the artwork is the shortcut for changing the cover.
        hero.setOnClickListener(v -> PlaylistActions.pickCover(this, playlist));
    }

    private void setupList() {
        adapter = new SongAdapter(new SongAdapter.Actions() {
            @Override
            public void onPlay(int index) {
                playAt(index);
            }

            @Override
            public void onMore(View anchor, Track track) {
                showSongMenu(track);
            }
        });
        songs.setLayoutManager(new LinearLayoutManager(this));
        songs.setAdapter(adapter);
    }

    private void render() {
        title.setText(playlist.name);
        meta.setText(playlist.summary());
        PlaylistArt.load(this, playlist, hero, heroFallback);
        List<Track> tracks = visibleTracks();
        adapter.submit(tracks);
        adapter.setPlaying(playback.current() == null ? "" : playback.current().localUri);
        boolean has = !tracks.isEmpty();
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        songs.setVisibility(has ? View.VISIBLE : View.GONE);
        playButton.setAlpha(has ? 1f : 0.5f);
        playButton.setEnabled(has);
        // The box is pointless over an empty playlist, and showing "no matches"
        // for a playlist that has no songs would be misleading.
        search.setVisibility(playlist.songs.isEmpty() ? View.GONE : View.VISIBLE);
        if (tracks.isEmpty() && !playlist.songs.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            songs.setVisibility(View.GONE);
        }
    }

    /**
     * The songs to show: all of them, or only those matching the search box.
     *
     * Matching is a case-insensitive substring over title, artist and album, which is
     * what someone typing three letters of a song name expects. An empty filter
     * returns the list unchanged so the normal path costs one string compare.
     */
    private List<Track> visibleTracks() {
        List<Track> out = new ArrayList<>();
        String needle = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.US);
        for (Playlist.Entry e : playlist.songs) {
            Track t = e.toTrack();
            if (needle.isEmpty() || matches(t, needle)) out.add(t);
        }
        return out;
    }

    private static boolean matches(Track t, String needle) {
        return contains(t.title, needle)
                || contains(t.artist, needle)
                || contains(t.album, needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(java.util.Locale.US).contains(needle);
    }

    // ---------------------------------------------------------------- playing

    private List<Track> tracks() {
        List<Track> out = new ArrayList<>();
        for (Playlist.Entry e : playlist.songs) out.add(e.toTrack());
        return out;
    }

    private void play(boolean shuffle) {
        List<Track> list = tracks();
        if (list.isEmpty()) {
            Toast.makeText(this, "Add songs first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (shuffle) Collections.shuffle(list);
        playback.playQueue(list, 0, playlist.name);
        playback.setShuffle(shuffle);
        openPlayer();
    }

    private void playAt(int index) {
        List<Track> list = tracks();
        if (index < 0 || index >= list.size()) return;
        playback.playQueue(list, index, playlist.name);
        openPlayer();
    }

    private void openPlayer() {
        startActivity(new Intent(this, PlayerActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void showSongMenu(Track track) {
        MenuSheet.show(this, track.title,
                track.artist == null || track.artist.isEmpty() ? "Unknown artist" : track.artist,
                new MenuSheet.Item(R.drawable.ic_play, "Play now", () -> playAt(indexOf(track))),
                new MenuSheet.Item(R.drawable.ic_next, "Play next", () -> {
                    playback.playNext(track);
                    Toast.makeText(this, "Playing next", Toast.LENGTH_SHORT).show();
                }),
                new MenuSheet.Item(R.drawable.ic_add, "Add to queue", () -> {
                    playback.append(track);
                    Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show();
                }),
                new MenuSheet.Item(R.drawable.ic_delete, "Remove from playlist", true,
                        () -> removeFromPlaylist(track)));
    }

    private int indexOf(Track track) {
        List<Track> list = tracks();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).localUri.equals(track.localUri)) return i;
        }
        return 0;
    }

    private void removeFromPlaylist(Track track) {
        playlist.removeUri(track.localUri);
        PlaylistStore.save(this, playlist);
        // Re-read so the in-memory object matches what was written.
        playlist = PlaylistStore.byId(this, playlist.id);
        render();
        Toast.makeText(this, "Removed from " + playlist.name, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- editing

    private void showMenu(View anchor) {
        MenuSheet.show(this, playlist.name, playlist.summary(),
                new MenuSheet.Item(R.drawable.ic_add, "Add songs", this::addSongs),
                new MenuSheet.Item(R.drawable.ic_edit, "Rename playlist", () ->
                        PlaylistActions.rename(this, playlist, () -> {
                            playlist = PlaylistStore.byId(this, playlist.id);
                            render();
                        })),
                new MenuSheet.Item(R.drawable.ic_image, "Change cover", () ->
                        PlaylistActions.pickCover(this, playlist)),
                new MenuSheet.Item(R.drawable.ic_image, "Remove custom cover", () -> {
                    if (playlist.coverPath != null && !playlist.coverPath.isEmpty()) {
                        new java.io.File(playlist.coverPath).delete();
                    }
                    playlist.coverPath = "";
                    playlist.updatedAt = System.currentTimeMillis();
                    PlaylistStore.save(this, playlist);
                    playlist = PlaylistStore.byId(this, playlist.id);
                    render();
                }),
                new MenuSheet.Item(R.drawable.ic_download, "Export as .m3u", this::exportPlaylist),
                new MenuSheet.Item(R.drawable.ic_delete, "Delete playlist", true, () ->
                        PlaylistActions.confirmDelete(this, playlist, this::finish)));
    }

    /**
     * Writes this playlist to Music/Cartify as an .m3u file.
     *
     * Playlists used to exist only inside the app. With this, the list can be opened by any
     * other player or copied off the phone, and the songs it points at already sit in the
     * same folder, so the export keeps working after the phone is wiped.
     */
    private void exportPlaylist() {
        if (playlist == null) return;
        if (playlist.songs.isEmpty()) {
            Toast.makeText(this, "This playlist is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show();
        PlaylistExport.export(this, playlist, true, new PlaylistExport.Callback() {
            @Override
            public void onDone(String displayPath) {
                Toast.makeText(PlaylistActivity.this, "Saved to " + displayPath,
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailed(String message) {
                Toast.makeText(PlaylistActivity.this, "Export failed: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void addSongs() {
        if (!hasAudioAccess()) {
            Toast.makeText(this, "Allow music access first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (library.isEmpty()) {
            Toast.makeText(this, "Your library is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        PickSongs.show(this, library, playlist, picked -> {
            int added = 0;
            for (Track t : picked) if (playlist.add(t)) added++;
            if (added == 0) {
                Toast.makeText(this, "Already in this playlist", Toast.LENGTH_SHORT).show();
                return;
            }
            PlaylistStore.save(this, playlist);
            playlist = PlaylistStore.byId(this, playlist.id);
            render();
            Toast.makeText(this, added + (added == 1 ? " song added" : " songs added"),
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void loadLibrary() {
        io.execute(() -> {
            List<LibraryStore.Item> raw = LibraryStore.query(this);
            List<Track> out = new ArrayList<>();
            for (LibraryStore.Item it : raw) out.add(it.toTrack());
            main.post(() -> {
                library.clear();
                library.addAll(out);
            });
        });
    }

    private boolean hasAudioAccess() {
        String perm = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? android.Manifest.permission.READ_MEDIA_AUDIO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        return androidx.core.content.ContextCompat.checkSelfPermission(this, perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        PlaylistActions.onActivityResult(this, requestCode, resultCode, data, () -> {
            playlist = PlaylistStore.byId(this, playlist.id);
            if (playlist == null) {
                finish();
                return;
            }
            render();
            Toast.makeText(this, "Cover updated", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playlist != null) {
            Playlist reloaded = PlaylistStore.byId(this, playlist.id);
            if (reloaded != null) playlist = reloaded;
            render();
            loadLibrary();
        }
    }

    @Override
    public void onPlaybackChanged() {
        if (adapter != null) {
            adapter.setPlaying(playback.current() == null ? "" : playback.current().localUri);
        }
    }

    @Override
    public void onQueueChanged() {
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        playback.removeListener(this);
        super.onDestroy();
    }
}
