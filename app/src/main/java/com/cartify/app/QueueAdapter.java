package com.cartify.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The play queue: tap to jump, X to remove, current track highlighted. */
public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.VH> {

    /** Artwork width for this list's rows, in pixels. Rows are small; full covers are not. */
    private static final int ROW_ART_PX = 200;

    public interface Actions {
        void onPick(int index);

        void onRemove(int index);
    }

    private final List<Track> items = new ArrayList<>();
    private final Actions actions;
    private int current = -1;

    public QueueAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<Track> tracks, int currentIndex) {
        items.clear();
        if (tracks != null) items.addAll(tracks);
        current = currentIndex;
        notifyDataSetChanged();
    }

    public void setCurrent(int index) {
        int old = current;
        current = index;
        if (old >= 0 && old < items.size()) notifyItemChanged(old);
        if (current >= 0 && current < items.size()) notifyItemChanged(current);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_queue, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Track t = items.get(position);
        boolean isCurrent = position == current;
        h.title.setText(t.title);
        h.title.setTextColor(h.itemView.getContext()
                .getColor(isCurrent ? R.color.accent : R.color.text_primary));
        h.artist.setText(t.artist);
        h.now.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        Art.load(h.itemView.getContext(), t, h.art, null, ROW_ART_PX);
        h.itemView.setOnClickListener(v -> actions.onPick(h.getAdapterPosition()));
        h.remove.setOnClickListener(v -> actions.onRemove(h.getAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView art;
        final ImageView now;
        final TextView title;
        final TextView artist;
        final ImageButton remove;

        VH(@NonNull View v) {
            super(v);
            art = v.findViewById(R.id.queueArt);
            now = v.findViewById(R.id.queueNow);
            title = v.findViewById(R.id.queueTitle);
            artist = v.findViewById(R.id.queueArtist);
            remove = v.findViewById(R.id.queueRemove);
        }
    }
}
