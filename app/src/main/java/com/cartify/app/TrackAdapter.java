package com.cartify.app;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Track list with artwork loading, animated progress and per-row actions. */
public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.VH> {

    public interface Actions {
        void onDownloadClicked(Track track, int index);

        void onCancelClicked(Track track, int index);

        void onPlayClicked(Track track, int index);

        /**
         * Stream a row that has never been saved.
         *
         * Defaulted so the downloads list, which only ever holds saved or in-progress
         * rows, does not have to implement a stream path it cannot reach.
         */
        default void onStreamClicked(Track track, int index) {
        }
    }

    private static final ExecutorService IMAGES = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final java.util.Map<String, List<ImageView>> WAITING = new java.util.HashMap<>();

    private final List<Track> items = new ArrayList<>();
    private final Actions actions;

    public TrackAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<Track> tracks) {
        items.clear();
        if (tracks != null) items.addAll(tracks);
        notifyDataSetChanged();
    }

    public void update(int index, int status, int percent, String detail, String uri, String error) {
        if (index < 0 || index >= items.size()) return;
        Track t = items.get(index);
        t.status = status;
        t.progress = percent;
        if (uri != null) t.localUri = uri;
        if (error != null) t.error = error;
        // A full rebind re-ran the artwork fade on every progress tick, which read as a
        // shimmer over the cover mid-download. Progress carries a payload now, and that
        // path only touches the bar and the label.
        notifyItemChanged(index, PAYLOAD_PROGRESS);
    }

    /** Puts a cancelled row back to idle without flashing an error. */
    public void reset(int index) {
        if (index < 0 || index >= items.size()) return;
        Track t = items.get(index);
        t.status = Track.IDLE;
        t.progress = 0;
        t.error = "";
        notifyItemChanged(index);
    }

    public List<Track> items() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_track, parent, false);
        return new VH(v);
    }

    /** Marks a partial update that only repaints progress, never the cover. */
    private static final Object PAYLOAD_PROGRESS = new Object();

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_PROGRESS)) {
            bindProgress(h, items.get(position));
            return;
        }
        onBindViewHolder(h, position);
    }

    /** Repaints only the progress widgets, leaving artwork and text untouched. */
    private void bindProgress(VH h, Track t) {
        boolean busy = t.status == Track.QUEUED || t.status == Track.ACTIVE;
        // Saved rows show no bar. A track already on the device has no progress to
        // report, and drawing one at 100% put a full-width neon strip on every
        // already-downloaded row - the brightest thing in the list, saying nothing.
        // GONE, not INVISIBLE: the wave is weighted, so an invisible one still
        // claimed the row and pushed "saved" out to the right edge.
        h.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        h.ring.setVisibility(busy ? View.VISIBLE : View.GONE);
        h.progress.setProgress(t.progress);
        if (busy) h.progress.start(); else h.progress.stop();
        animate(h.ring, t.progress);
        if (t.status == Track.ACTIVE) h.percent.setText(t.progress + "%");
        else if (t.status == Track.QUEUED) h.percent.setText("queued");
        else if (t.status == Track.DONE) h.percent.setText("saved");
        else if (t.status == Track.ERROR) h.percent.setText(t.error == null || t.error.isEmpty() ? "failed" : t.error);
        else h.percent.setText("");
        h.action.setImageResource(t.status == Track.QUEUED || t.status == Track.ACTIVE
                ? R.drawable.ic_close : (t.status == Track.DONE ? R.drawable.ic_play : R.drawable.ic_download));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Track t = items.get(position);
        h.title.setText(t.title);
        String sub = t.artist;
        if (t.durationText().length() > 0) sub = sub + "  ·  " + t.durationText();
        h.subtitle.setText(sub);
        // Only clear the artwork when the URL really changed, and only blank it when the
        // replacement is not already in memory. The old order — blank first, then load —
        // produced a guaranteed blink on every rebind even when Net.bitmap answered from
        // its cache in the same frame: the cover was set to null and alpha 0, and the
        // bitmap was only put back a moment later. On a list that rebinds often (progress
        // ticks, artwork arriving, the already-saved scan) that is what reads as flashing.
        // Art.load, like every other adapter in the app, instead of a private copy of the
        // loader. This row used Net.bitmap directly, which is network-only: no disk cache,
        // no embedded-artwork fallback, no retry. A cover that failed to arrive left the
        // row blank forever, while the shelf above - which uses Art.load - showed the same
        // artwork perfectly. That divergence is what the empty squares in the rows were.
        h.cover.setAlpha(1f);
        Art.load(h.itemView.getContext(), t, h.cover, null,
                coverTargetPx(h.itemView.getContext()));

        boolean busy = t.status == Track.QUEUED || t.status == Track.ACTIVE;
        // Saved rows show no bar. A track already on the device has no progress to
        // report, and drawing one at 100% put a full-width neon strip on every
        // already-downloaded row - the brightest thing in the list, saying nothing.
        // GONE, not INVISIBLE: the wave is weighted, so an invisible one still
        // claimed the row and pushed "saved" out to the right edge.
        h.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        h.ring.setVisibility(busy ? View.VISIBLE : View.GONE);
        h.progress.setProgress(t.progress);
        if (t.status == Track.ACTIVE) h.progress.start(); else h.progress.stop();
        animate(h.ring, t.progress);

        // The play button is always about hearing the song, in every state; the action
        // button is always about keeping it. They used to be one button, so a row could
        // only be heard by downloading it first - and once saved, the download button
        // turned into a play button, which is why "save this while it plays" had nowhere
        // to live.
        h.play.setVisibility(View.VISIBLE);
        h.play.setContentDescription(t.localUri.isEmpty() ? "Stream" : "Play");
        h.play.setAlpha(busy ? 0.4f : 1f);
        h.play.setEnabled(!busy);

        switch (t.status) {
            case Track.QUEUED:
                h.percent.setText("queued");
                h.action.setImageResource(R.drawable.ic_close);
                h.action.setContentDescription("Cancel download");
                h.action.setEnabled(true);
                break;
            case Track.ACTIVE:
                h.percent.setText(t.progress + "%");
                h.action.setImageResource(R.drawable.ic_close);
                h.action.setContentDescription("Cancel download");
                h.action.setEnabled(true);
                break;
            case Track.DONE:
                h.percent.setText("saved");
                // Saved: the action becomes a state, not an action. Leaving a play icon
                // here made the row offer two identical ways to start the song, while
                // the actual "save while playing" case had no button at all.
                h.action.setImageResource(R.drawable.ic_download);
                h.action.setContentDescription("Saved");
                h.action.setEnabled(false);
                h.action.setAlpha(0.45f);
                break;
            case Track.ERROR:
                h.percent.setText("failed");
                h.subtitle.setText(t.error == null || t.error.isEmpty()
                        ? "Download failed — tap to retry"
                        : t.error + " · tap to retry");
                h.action.setImageResource(R.drawable.ic_download);
                h.action.setContentDescription("Retry");
                h.action.setEnabled(true);
                break;
            default:
                h.percent.setText("");
                h.action.setImageResource(R.drawable.ic_download);
                h.action.setContentDescription("Download");
                h.action.setEnabled(true);
                break;
        }
        // Only the saved state dims the button; every other state must look tappable
        // after a row is recycled from a saved one.
        if (t.status != Track.DONE) h.action.setAlpha(1f);

        // A saved row that is currently being heard from the network says so: the same
        // title otherwise appears once as "saved" while the mini player streams it, which
        // reads as the app ignoring the tap.
        if (t.status == Track.IDLE && t.isStreaming()) {
            h.percent.setText("streaming");
        }

        h.action.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            press(h.action);
            if (t.status == Track.QUEUED || t.status == Track.ACTIVE) actions.onCancelClicked(t, pos);
            else if (t.status == Track.DONE) {
                // Already saved: nothing to fetch. Deletion lives in the row menu.
            } else actions.onDownloadClicked(t, pos);
        });

        h.play.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            press(h.play);
            // A saved row plays its file; anything else streams. Both land in the same
            // queue, so the next row follows on its own - and the song can be saved
            // from the player while it is still playing.
            if (t.status == Track.DONE) actions.onPlayClicked(t, pos);
            else actions.onStreamClicked(t, pos);
        });
        // Tapping the row plays it right away - a saved file from the library, a stream
        // from the network - so hearing a search result no longer costs a download.
        // The button keeps doing exactly what it did: save the song, or cancel a save.
        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (t.status == Track.DONE) actions.onPlayClicked(t, pos);
            else if (t.status == Track.QUEUED || t.status == Track.ACTIVE) actions.onCancelClicked(t, pos);
            else actions.onStreamClicked(t, pos);
        });
        h.itemView.setContentDescription(t.status == Track.DONE
                ? t.title + ", saved. Tap to play."
                : t.title + ". Tap to stream, or use the button to save.");
    }

    /**
     * A short dip on a row's action button.
     *
     * The row can sit in four different states and the icon changes with them, so without
     * a physical acknowledgement a tap can look as if nothing happened until the download
     * bar finally moves. A 0.9 scale for 200 ms is enough to confirm the tap.
     */
    private static void press(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.86f);
        view.setScaleY(0.86f);
        view.animate().scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                .start();
    }

    /**
     * Slowly inches a bar that is waiting on a silent stage.
     *
     * The conversion step reports no progress at all, so a bar parked at 92% looks like a
     * hang. Creeping toward 99% keeps it visibly alive; a real update retargets the same
     * animator, so the creep never fights the true value.
     */
    private static void creep(ProgressBar bar, int value) {
        ObjectAnimator old = (ObjectAnimator) bar.getTag(R.id.progress);
        if (old != null && old.isRunning()) return;
        ObjectAnimator anim = ObjectAnimator.ofInt(bar, "progress", value, 99);
        anim.setDuration(9000);
        anim.setInterpolator(new LinearInterpolator());
        bar.setTag(R.id.progress, anim);
        anim.start();
    }

    /**
     * Progress glides to its new value instead of snapping between yt-dlp updates.
     *
     * The previous version bailed out whenever an animation was still running, which
     * silently dropped every update that arrived mid-flight: the bar sat at one number
     * and then leapt when the next animation finally got through. Now the running
     * animation is retargeted from wherever it currently is, so no update is ever lost.
     */
    private static void animate(ProgressBar bar, int value) {
        ObjectAnimator old = (ObjectAnimator) bar.getTag(R.id.progress);
        int current = bar.getProgress();
        if (old != null) {
            if (old.isRunning()) {
                current = (int) Math.round(((Number) old.getAnimatedValue()).floatValue());
            }
            old.cancel();
        }
        if (current == value) {
            bar.setProgress(value);
            return;
        }
        ObjectAnimator anim = ObjectAnimator.ofInt(bar, "progress", current, value);
        // A linear, slightly longer glide reads as steady motion rather than a stall.
        anim.setDuration(420);
        anim.setInterpolator(new LinearInterpolator());
        bar.setTag(R.id.progress, anim);
        anim.start();
    }

    /** Width a list row's cover is actually drawn at, in pixels. */
    private static int coverTargetPx(android.content.Context context) {
        // The row cover is 54dp; 2x covers high-density screens without wasting memory.
        float d = context.getResources().getDisplayMetrics().density;
        return Math.max(64, Math.round(54 * d * 2f));
    }

    private void loadCover(ImageView view, String url) {
        view.setTag(url);
        if (url == null || url.isEmpty()) return;
        final int targetPx = coverTargetPx(view.getContext());
        Bitmap cached = Net.bitmap(url, targetPx);
        if (cached != null) {
            reveal(view, cached);
            return;
        }
        synchronized (WAITING) {
            List<ImageView> list = WAITING.get(url);
            if (list == null) {
                list = new ArrayList<>();
                WAITING.put(url, list);
            }
            if (!list.contains(view)) list.add(view);
        }
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(url)) return;   // already fetching this artwork
        }
        IMAGES.execute(() -> {
            final Bitmap b = Net.bitmap(url, targetPx);
            List<ImageView> targets;
            synchronized (WAITING) {
                targets = WAITING.remove(url);
                IN_FLIGHT.remove(url);
            }
            if (b != null && targets != null) {
                for (final ImageView target : targets) {
                    MAIN.post(() -> {
                        if (url.equals(target.getTag())) reveal(target, b);
                    });
                }
            }
        });
    }

    /** Fades artwork in so the list does not flicker while scrolling. */
    private static void reveal(ImageView view, Bitmap bitmap) {
        view.setImageBitmap(bitmap);
        view.animate().alpha(1f).setDuration(180).start();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView subtitle;
        final TextView percent;
        final WaveProgressView progress;
        final ProgressBar ring;
        final ImageButton action;
        /** Plays the row immediately; the action button saves or cancels a save. */
        final ImageButton play;

        VH(@NonNull View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            percent = v.findViewById(R.id.percent);
            progress = v.findViewById(R.id.progress);
            ring = v.findViewById(R.id.ring);
            action = v.findViewById(R.id.action);
            play = v.findViewById(R.id.playButton);
        }
    }
}
