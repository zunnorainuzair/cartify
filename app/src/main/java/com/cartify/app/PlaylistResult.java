package com.cartify.app;

/**
 * One playlist row returned by search.
 *
 * Separate from {@link Playlist} on purpose: that class is the user's local, persisted
 * playlist and carries a UUID and file cover. This is a remote catalogue result that only
 * knows a provider id, so it can be fetched and turned into a local playlist on demand.
 */
public class PlaylistResult {

    /** Provider playlist id, used to fetch the track list. */
    public String id = "";
    public String title = "";
    /** Creator name from the catalogue, shown as the card subtitle. */
    public String owner = "";
    /** Track count as reported by the catalogue; 0 when unknown. */
    public int trackCount = 0;
    public String coverUrl = "";

    public PlaylistResult() {
    }

    public PlaylistResult(String id, String title, String owner, int trackCount, String coverUrl) {
        this.id = id;
        this.title = title;
        this.owner = owner;
        this.trackCount = trackCount;
        this.coverUrl = coverUrl;
    }

    /** "12 tracks" / "1 track" / "Playlist" when the count is unknown. */
    public String subtitle() {
        String by = (owner == null || owner.isEmpty()) ? "" : owner;
        String count;
        if (trackCount <= 0) {
            count = "Playlist";
        } else {
            count = trackCount + (trackCount == 1 ? " track" : " tracks");
        }
        if (by.isEmpty()) return count;
        return by + " \u00b7 " + count;
    }
}
