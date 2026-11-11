package com.cartify.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Horizontal rail of album-style cards, the shape Spotify uses for its home shelves. */
public class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {

    /** Artwork width for this list's rows, in pixels. Rows are small; full covers are not. */
    private static final int CARD_ART_PX = 420;

    public interface Actions {
        void onOpen(Track track);
    }

    private final List<Track> items = new ArrayList<>();
    private final Actions actions;

    public CardAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<Track> tracks) {
        items.clear();
        if (tracks != null) items.addAll(tracks);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Track t = items.get(position);
        h.title.setText(t.title);
        String sub = (t.artist == null || t.artist.isEmpty()) ? "Unknown artist" : t.artist;
        h.subtitle.setText(sub);
        Art.load(h.itemView.getContext(), t, h.art, null, CARD_ART_PX);
        h.itemView.setOnClickListener(v -> actions.onOpen(t));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView title;
        final TextView subtitle;

        VH(@NonNull View v) {
            super(v);
            art = v.findViewById(R.id.cardArt);
            title = v.findViewById(R.id.cardTitle);
            subtitle = v.findViewById(R.id.cardSubtitle);
        }
    }
}
