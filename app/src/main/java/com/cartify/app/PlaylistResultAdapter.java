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

/**
 * The "Playlists" strip under search results.
 *
 * Reuses item_card so playlist cards sit at the same size and rhythm as the "More like this"
 * rail - a second card design next to the first would read as two unrelated widgets.
 */
public class PlaylistResultAdapter extends RecyclerView.Adapter<PlaylistResultAdapter.VH> {

    /** Card artwork is 126dp tall; 420px covers a 2x screen without waste. */
    private static final int CARD_ART_PX = 420;

    public interface Actions {
        /** Open the playlist: show its tracks in the results list. */
        void onOpen(PlaylistResult playlist);

        /** Download every track and keep the playlist locally. */
        void onDownload(PlaylistResult playlist);
    }

    private final List<PlaylistResult> items = new ArrayList<>();
    private final Actions actions;

    public PlaylistResultAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<PlaylistResult> playlists) {
        items.clear();
        if (playlists != null) items.addAll(playlists);
        notifyDataSetChanged();
    }

    public List<PlaylistResult> items() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PlaylistResult p = items.get(position);
        h.title.setText(p.title);
        h.subtitle.setText(p.subtitle());
        if (p.coverUrl == null || p.coverUrl.isEmpty()) {
            h.art.setTag("");
            h.art.setImageDrawable(null);
        } else {
            // Tag with the exact key Art.loadUrl checks, or a recycled card can show the
            // previous playlist's cover when the new one fails to load.
            h.art.setTag(p.coverUrl + "#w" + CARD_ART_PX);
            h.art.setImageDrawable(null);
            Art.loadUrl(h.art, p.coverUrl, CARD_ART_PX);
        }
        h.itemView.setOnClickListener(v -> actions.onOpen(p));
        h.itemView.setOnLongClickListener(v -> {
            actions.onDownload(p);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView title;
        final TextView subtitle;

        VH(View v) {
            super(v);
            art = v.findViewById(R.id.cardArt);
            title = v.findViewById(R.id.cardTitle);
            subtitle = v.findViewById(R.id.cardSubtitle);
        }
    }
}
