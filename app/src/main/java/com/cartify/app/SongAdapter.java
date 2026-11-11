package com.cartify.app;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Saved MP3s: tap to play, overflow for playlist/queue actions, long-press to multi-select.
 *
 * Selection is held as a set of local uris rather than positions: the library re-sorts and
 * re-filters underneath the user, and a position-keyed selection would silently jump to a
 * different song after a sort toggle or a search keystroke. The uri is the one thing that
 * keeps naming the same file.
 */
public class SongAdapter extends RecyclerView.Adapter<SongAdapter.VH> {

    /** Artwork width for this list's rows, in pixels. Rows are small; full covers are not. */
    private static final int ROW_ART_PX = 200;

    public interface Actions {
        void onPlay(int index);

        void onMore(View anchor, Track track);

        /**
         * Selection is opt-in per screen: the library wires these, the playlist viewer
         * leaves them alone and keeps its long-press-free single-row menu.
         */
        default void onLongPress(Track track) {
        }

        /** A tap while selecting toggles the row instead of playing it. */
        default void onToggle(Track track) {
        }

        /** Selection count changed; the host swaps its header for the action bar. */
        default void onSelectionChanged(int count, boolean selecting) {
        }
    }

    /** Payload for a bind that only needs to repaint the playing highlight. */
    private static final Object PAYLOAD_PLAYING = new Object();
    /** Payload for a bind that only needs to repaint the selection badge and tint. */
    private static final Object PAYLOAD_SELECTION = new Object();

    /**
     * Artist section headers, when the host asks for them.
     *
     * A library of three hundred songs is browsable by artist and unusable as a flat
     * wall. Headers are rows in the same list rather than a second adapter, so the
     * existing selection, drag and playing-highlight logic keep working unchanged:
     * they simply skip rows whose uri starts with {@link #HEADER}.
     */
    private boolean grouped;
    private final List<String> headers = new ArrayList<>();

    private final List<Track> items = new ArrayList<>();
    private final Actions actions;
    private final Set<String> selected = new LinkedHashSet<>();
    private String playingUri = "";
    private boolean selecting;
    /** Two threads: one decoding artwork, one blurring it, so scrolling stays smooth. */
    private static final java.util.concurrent.ExecutorService BLUR =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    public SongAdapter(Actions actions) {
        this.actions = actions;
    }

    /** Turns artist section headers on or off and rebuilds the visible list. */
    public void setGrouped(boolean on, List<Track> source) {
        grouped = on;
        if (source != null) submit(source);
    }

    public boolean isGrouped() {
        return grouped;
    }

    /** The artist a row belongs under; "Unknown artist" keeps blanks together. */
    private static String groupKey(Track t) {
        String artist = t == null || t.artist == null ? "" : t.artist.trim();
        return artist.isEmpty() ? "Unknown artist" : artist;
    }

    public void submit(List<Track> tracks) {
        items.clear();
        headers.clear();
        if (tracks != null) items.addAll(tracks);
        // Drop selections whose song is no longer in the visible list, so the count in the
        // action bar never claims songs the user cannot see.
        if (!selected.isEmpty()) {
            Set<String> alive = new LinkedHashSet<>();
            for (Track t : items) if (selected.contains(t.localUri)) alive.add(t.localUri);
            if (alive.size() != selected.size()) {
                selected.clear();
                selected.addAll(alive);
                if (selected.isEmpty()) selecting = false;
            }
        }
        rebuildHeaders();
        notifyDataSetChanged();
    }

    /**
     * Marks the row index where each artist first appears.
     *
     * Built only while grouped, and kept in step with every submit, so a delete or a
     * filter can never leave a header pointing at a song that has moved.
     */
    private void rebuildHeaders() {
        headers.clear();
        if (!grouped) return;
        String last = null;
        for (Track t : items) {
            String key = groupKey(t);
            if (key.equals(last)) {
                headers.add(null);
            } else {
                headers.add(key);
                last = key;
            }
        }
    }

    public List<Track> items() {
        return items;
    }

    /**
     * Moves the "now playing" highlight without rebinding the whole list.
     *
     * This runs on every playback callback, which is several times per track start. A full
     * notifyDataSetChanged here re-inflated and re-bound every visible row each time, and
     * with the cover cleared during the rebind that read as the library blinking.
     */
    public void setPlaying(String uri) {
        String next = uri == null ? "" : uri;
        if (next.equals(playingUri)) return;
        String previous = playingUri;
        playingUri = next;
        int was = indexOfUri(previous);
        int now = indexOfUri(next);
        if (was >= 0) notifyItemChanged(was, PAYLOAD_PLAYING);
        if (now >= 0 && now != was) notifyItemChanged(now, PAYLOAD_PLAYING);
        if (was < 0 && now < 0) notifyDataSetChanged();
    }

    private int indexOfUri(String uri) {
        if (uri == null || uri.isEmpty()) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (uri.equals(items.get(i).localUri)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ selection

    public boolean isSelecting() {
        return selecting;
    }

    public int selectedCount() {
        return selected.size();
    }

    /** The selected songs in list order, which is the order they get queued or added. */
    public List<Track> selectedTracks() {
        List<Track> out = new ArrayList<>();
        for (Track t : items) if (selected.contains(t.localUri)) out.add(t);
        return out;
    }

    public void beginSelection(Track track) {
        boolean wasSelecting = selecting;
        selecting = true;
        if (track != null && track.localUri != null) selected.add(track.localUri);
        // Entering selection mode changes the overflow button on every row, so this one
        // legitimately touches the whole list. The artwork no longer blanks (Art.load
        // skips an unchanged key) and that was what made it read as a blink.
        if (!wasSelecting) notifyDataSetChanged();
        else notifyItemChanged(indexOfTrack(track), PAYLOAD_SELECTION);
        actions.onSelectionChanged(selected.size(), true);
    }

    public void toggle(Track track) {
        if (track == null || track.localUri == null) return;
        boolean wasSelecting = selecting;
        boolean nowChecked;
        if (!selected.remove(track.localUri)) {
            selected.add(track.localUri);
            nowChecked = true;
        } else {
            nowChecked = false;
        }
        selecting = !selected.isEmpty();

        // Repaint only the row that was tapped. Deselecting the last song also leaves
        // selection mode, which has to restore the overflow button on every row.
        int index = indexOfTrack(track);
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION);
        if (wasSelecting && !selecting) notifyDataSetChanged();
        actions.onSelectionChanged(selected.size(), selecting);
    }

    public void selectAll() {
        boolean wasSelecting = selecting;
        selecting = true;
        for (Track t : items) {
            if (t.localUri != null && !t.localUri.isEmpty()) selected.add(t.localUri);
        }
        if (!wasSelecting) notifyDataSetChanged();
        else notifyItemRangeChanged(0, items.size(), PAYLOAD_SELECTION);
        actions.onSelectionChanged(selected.size(), true);
    }

    public void clearSelection() {
        boolean was = selecting;
        selected.clear();
        selecting = false;
        if (was) notifyDataSetChanged();
        if (was) actions.onSelectionChanged(0, false);
    }

    private int indexOfTrack(Track track) {
        if (track == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == track) return i;
        }
        return indexOfUri(track.localUri);
    }

    // ------------------------------------------------------------- drag selection

    /**
     * Anchor for a long-press drag: the row the finger went down on, and the set of uris the
     * drag had painted before this gesture started.
     *
     * Select-all-by-scroll is the gesture every gallery and file manager has, and without it
     * picking 30 songs means 30 separate taps. Each drag step re-derives the range from the
     * anchor so dragging back over a row un-highlights it instead of leaving a trail.
     */
    private int dragAnchor = -1;
    private Set<String> dragBaseline;

    /** Begins a drag range at {@code position}, keeping whatever was already selected. */
    public void beginDragRange(int position) {
        if (position < 0 || position >= items.size()) return;
        dragAnchor = position;
        dragBaseline = new LinkedHashSet<>(selected);
        selecting = true;
        applyDragRange(position);
    }

    /** Extends the range from the anchor to {@code position}. */
    public void extendDragRange(int position) {
        if (dragAnchor < 0 || position < 0 || position >= items.size()) return;
        applyDragRange(position);
    }

    public void endDragRange() {
        dragAnchor = -1;
        dragBaseline = null;
    }

    public boolean isDragging() {
        return dragAnchor >= 0;
    }

    private void applyDragRange(int position) {
        if (dragBaseline == null) return;
        int from = Math.min(dragAnchor, position);
        int to = Math.max(dragAnchor, position);
        Set<String> next = new LinkedHashSet<>(dragBaseline);
        for (int i = from; i <= to && i < items.size(); i++) {
            String uri = items.get(i).localUri;
            if (uri != null && !uri.isEmpty()) next.add(uri);
        }
        if (next.equals(selected)) return;
        // Repaint against what is on screen right now, not against the baseline. Comparing
        // with the baseline meant a row that the drag had painted and then swept back over
        // was "not in baseline, not in selection" on both sides of the test, so it was never
        // repainted and kept its tick after the count had already dropped.
        Set<String> before = new LinkedHashSet<>(selected);
        selected.clear();
        selected.addAll(next);
        for (int i = 0; i < items.size(); i++) {
            String uri = items.get(i).localUri;
            boolean was = before.contains(uri);
            boolean now = selected.contains(uri);
            if (was != now) notifyItemChanged(i, PAYLOAD_SELECTION);
        }
        actions.onSelectionChanged(selected.size(), true);
    }

    /** Drops one uri without disturbing the rest, used after a bulk delete. */
    public void removeSelectedUri(String uri) {
        if (uri != null && selected.remove(uri)) {
            selecting = !selected.isEmpty();
            actions.onSelectionChanged(selected.size(), selecting);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_PLAYING)) {
            // Highlight-only rebind: repaint the now-playing marker and title tint, and
            // touch nothing else. Artwork and the blurred backdrop stay exactly as they are.
            Track t = items.get(position);
            boolean isCurrent = playingUri.equals(t.localUri);
            h.now.setVisibility(isCurrent && !selecting ? View.VISIBLE : View.GONE);
            h.title.setTextColor(h.itemView.getContext()
                    .getColor(isCurrent ? R.color.accent : R.color.text_primary));
            return;
        }
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            // Selection-only rebind: the badge, the row wash and the overflow button. The
            // cover, the title and the blurred backdrop are left alone, so tapping a row
            // no longer flashes its artwork.
            Track t = items.get(position);
            boolean checked = selecting && selected.contains(t.localUri);
            h.check.setVisibility(checked ? View.VISIBLE : View.GONE);
            h.tint.setVisibility(checked ? View.VISIBLE : View.GONE);
            h.itemView.setSelected(checked);
            h.more.setVisibility(selecting ? View.GONE : View.VISIBLE);
            return;
        }
        onBindViewHolder(h, position);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Track t = items.get(position);
        // The section label sits inside the row, not as a separate list item: a second
        // view type would shift every index and break selection, drag and the play
        // callback, which all address songs by position.
        String group = position < headers.size() ? headers.get(position) : null;
        if (h.header != null) {
            h.header.setVisibility(group == null ? View.GONE : View.VISIBLE);
            if (group != null) h.header.setText(group);
        }
        h.title.setText(t.title);
        long secs = t.durationMs / 1000;
        String sub = (t.artist == null || t.artist.isEmpty() ? "Unknown artist" : t.artist)
                + (t.durationMs > 0
                ? String.format(Locale.US, "  \u00b7  %d:%02d", secs / 60, secs % 60)
                : "");
        h.subtitle.setText(sub);

        boolean checked = selecting && selected.contains(t.localUri);
        h.check.setVisibility(checked ? View.VISIBLE : View.GONE);
        h.tint.setVisibility(checked ? View.VISIBLE : View.GONE);
        h.itemView.setSelected(checked);
        // The overflow button is pointless while selecting: the bar owns the actions then.
        h.more.setVisibility(selecting ? View.GONE : View.VISIBLE);

        boolean isCurrent = playingUri.equals(t.localUri);
        h.now.setVisibility(isCurrent && !selecting ? View.VISIBLE : View.GONE);
        h.title.setTextColor(h.itemView.getContext()
                .getColor(isCurrent ? R.color.accent : R.color.text_primary));

        Art.load(h.itemView.getContext(), t, h.art, null, ROW_ART_PX);
        loadRowBackdrop(h, t);

        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (selecting) actions.onToggle(items.get(pos));
            else actions.onPlay(pos);
        });
        h.itemView.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return true;
            Track row = items.get(pos);
            if (selecting) actions.onToggle(row);
            else actions.onLongPress(row);
            return true;
        });
        h.more.setOnClickListener(v -> actions.onMore(v, t));
    }

    private DragRecyclerView recycler;

    /**
     * Drag-to-select is observed on the RecyclerView itself.
     *
     * A long-press starts selection and then the finger keeps moving; the list reports
     * every one of those moves here. The first row of the drag seeds the range and each
     * further row extends it, so sweeping down the list selects a run of songs at once.
     */
    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        super.onAttachedToRecyclerView(rv);
        if (rv instanceof DragRecyclerView) {
            this.recycler = (DragRecyclerView) rv;
            this.recycler.setDragObserver(new DragRecyclerView.DragObserver() {
                @Override
                public boolean onDragTouch(android.view.MotionEvent event) {
                    return handleDragTouch(event);
                }

                @Override
                public void onDragAutoScrolled(float rawY) {
                    // The list moved under a stationary finger, so the range has to be
                    // recomputed from the row that is now under it.
                    if (!dragActive || recycler == null) return;
                    int target = recycler.positionUnder(rawY);
                    if (target >= 0) extendDragRange(target);
                }
            });
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        super.onDetachedFromRecyclerView(rv);
        if (rv instanceof DragRecyclerView) {
            ((DragRecyclerView) rv).setDragObserver(null);
        }
        this.recycler = null;
    }

    /** @return true when this event was consumed by a drag. */
    private boolean handleDragTouch(android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                dragDownY = e.getRawY();
                dragDownPosition = recycler == null ? -1 : recycler.positionUnder(e.getRawY());
                dragActive = false;
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!selecting || dragDownPosition < 0 || recycler == null) return false;
                if (!dragActive) {
                    if (Math.abs(e.getRawY() - dragDownY) < dragSlop) return false;
                    dragActive = true;
                    beginDragRange(dragDownPosition);
                }
                int target = recycler.positionUnder(e.getRawY());
                if (target >= 0) extendDragRange(target);
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                boolean dragged = dragActive;
                dragActive = false;
                dragDownPosition = -1;
                endDragRange();
                return dragged;
            }
            default:
                return false;
        }
    }

    private float dragDownY;
    private int dragDownPosition = -1;
    private boolean dragActive;
    private final float dragSlop = 24f;

    /**
     * Blurs the row's artwork into the translucent ground behind it.
     *
     * Decoding and blurring run on a shared executor; the holder's tag holds the key the
     * bitmap belongs to so a recycled row never shows the previous song's wash.
     */
    /**
     * Bounded cache of row washes, keyed by artwork key.
     *
     * Every row used to blur its own artwork into a fresh bitmap and hand it straight to an
     * ImageView, with nothing caching or releasing it. Scrolling a large library therefore
     * created a new GPU texture per row visited: GPU memory reached ~174 MB and total PSS
     * ~325 MB, which on a low-end phone is a one-way trip to the low-memory killer.
     *
     * A wash is small and purely decorative, so a modest LRU is enough: revisiting a row is
     * then free, and the cap keeps a long scroll bounded.
     */
    private static final android.util.LruCache<String, Bitmap> WASH =
            new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** Height the wash is rendered at: enough to fill a 68dp row on a dense screen. */
    private static final int WASH_H = 160;

    private void loadRowBackdrop(VH h, Track t) {
        final String key = Art.key(t);
        if (key.isEmpty()) {
            h.backdrop.setImageDrawable(null);
            h.backdrop.setTag("");
            return;
        }
        if (key.equals(h.backdrop.getTag())) return;
        h.backdrop.setTag(key);

        Bitmap hit = WASH.get(key);
        if (hit != null) {
            h.backdrop.setImageBitmap(hit);
            return;
        }

        final android.content.Context app = h.itemView.getContext().getApplicationContext();
        final ImageView target = h.backdrop;
        BLUR.execute(() -> {
            Bitmap cached = Art.peek(t);
            Bitmap src = cached != null ? cached : Art.full(app, t);
            if (src == null) return;
            // Shrink before blurring: Blur only needs 48px of width internally, so handing
            // it a full cover just allocates a large temporary for no visible gain.
            int w = Math.max(1, Math.round(WASH_H * (float) src.getWidth() / src.getHeight()));
            Bitmap small = src.getWidth() > w
                    ? Bitmap.createScaledBitmap(src, w, WASH_H, true)
                    : src;
            final Bitmap wash = Blur.of(small, 2);
            if (small != src) small.recycle();
            if (wash == null) return;
            WASH.put(key, wash);
            target.post(() -> {
                if (!key.equals(target.getTag())) return;
                target.setImageBitmap(wash);
                target.setAlpha(1f);
                target.animate().alpha(1f).setDuration(180).start();
            });
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView art;
        final ImageView backdrop;
        final ImageView now;
        final ImageView check;
        final View tint;
        final TextView title;
        final TextView subtitle;
        final ImageButton more;
        /** Artist section label, shown on the first row of each group. */
        final TextView header;

        VH(@NonNull View v) {
            super(v);
            art = v.findViewById(R.id.songArt);
            backdrop = v.findViewById(R.id.songRowBackdrop);
            now = v.findViewById(R.id.songNow);
            check = v.findViewById(R.id.songCheck);
            tint = v.findViewById(R.id.songSelectedTint);
            title = v.findViewById(R.id.songTitle);
            subtitle = v.findViewById(R.id.songSubtitle);
            more = v.findViewById(R.id.songMore);
            header = v.findViewById(R.id.songArtistHeader);
        }
    }
}
