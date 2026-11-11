# Cartify — missed features and outstanding tasks

Compiled 4 November 2026 against the working tree at `D:\MY PROJECTS\cartify`
(HEAD `b133706`, versionName 1.1.0, versionCode 3) and against the installed build on
the connected Pixel 7 Pro (`29021FDH300434`).

Every item is marked with how it was established:

- **SOURCE** — read in the code, with the file and the evidence.
- **DEVICE** — observed on the phone through adb during this session.
- **DOC** — promised in a project document or the external review, not yet done.

Nothing here was inferred from a summary; each line cites what proves it.

---

## 1. The one finding that matters most

### 1.1 The variant guard let translated-lyrics uploads through — **FIXED, verified on device**

*Status: fixed in `DownloadEngine.java` on 4 November 2026. See the end of this section.*

A download started from the phone matched:

```
matched "Fix You Canyon City" -> "Canyon City - Fix You (TRADUÇÃO: PT - BR)" score=95 identity=50
```

`TRADUÇÃO: PT - BR` is a Portuguese-translated lyrics video, not the studio recording.
It was accepted because `DownloadEngine.VARIANT_MARKERS` (line ~840) puts
`tradução`/`traducao`/`legendado`/`letra`/`paroles`/`subtitulado` in the
**`packagingOnly`** branch, which subtracts only 22 points and leaves `identity`
intact. With title 35 + artist 20 = 55 identity, the candidate clears
`IDENTITY_ACCEPT_SCORE` (42) and the length check is bypassed entirely.

**Fix applied.** `DownloadEngine.scoreCandidate()` now treats explicitly *translated*
markers (`tradução`, `traducao`, `traducido`, `traduzione`, `traduit`, `tradotto`,
`übersetzt`, `übersetzung`) as a different recording: `identity = 0` and a 40-point
penalty, so the identity shortcut can no longer accept them. Plain lyric packaging
(`lyrics`, `lyric video`, `legendado`, `letra`, `paroles`, `subtitulado`) keeps the old
22-point penalty and keeps its identity, so ordinary lyric uploads are not rejected.
The marker list and the new `isTranslatedMarker()` helper were added for this.

**Verified on the phone (4 Nov 2026, build installed 03:15).** Re-running the same
query now logs:

```
matched "Fix You Canyon City" -> "NEW AMSTERDAM SE1EP1-CANYON CITY - FIX YOU" score=95 identity=50
```

The `TRADUÇÃO: PT - BR` upload is no longer the chosen candidate. Build:
`BUILD SUCCESSFUL`, APK 112,505,159 bytes, `adb install -r` → `Success`.

### 1.2 Single-track download took 16.4 s, not the documented ~4 s — **DEVICE**

```
track 1/1 slot=0 took 16421 ms
queue done: 1 saved, 0 failed, 1 tracks, workers=1, 16425 ms total
```

`docs/HANDOVER-2026-11-03.md` claims per-track time fell from ~16 s to ~4 s via the
`android` player client. On this run the `android,ios` chain did not deliver that gain.
Either the client chain silently fell back, or the network was slow — the log does not
distinguish which, because the client actually used is never logged. Add a log line
naming the chosen client and the resolved format id, then re-measure.

---

## 2. Proposed UI improvements that were never implemented

All ten were listed under "Micro-level UI improvements proposed — Not implemented" in
`Cartify-Features-2026-11-03.docx`. Status verified against source:

| # | Proposal | Status | Evidence |
|---|---|---|---|
| 1 | Track count on the header button ("Download all 25") | Missing | `activity_main.xml` has one literal `android:text="Download all"`; the count lives only in `collectionMeta` |
| 2 | Progress spinner inside the row button | Missing | `item_track.xml` `action` is an `ImageButton` swapping `ic_download`/`ic_close`; `WaveProgressView` is a separate sibling |
| 3 | Disable the header button while a queue runs | Missing | zero hits for `downloadAllButton.setEnabled` in `MainActivity.java`. Only `resolve()` is guarded by `queueRunning` |
| 4 | Clear button in the search field, shown only when text is present | Missing | `clearButton` exists for `linkInput` only; `searchInput` has no clear affordance |
| 5 | Label + divider between search results and the shelf | Partial | `recHeader` exists ("More like this") but there is no divider |
| 6 | Cap recent-search chips at one line with a fade | Missing | `recentRow` is a plain `HorizontalScrollView`, no edge fade |
| 7 | Consistent 8dp spacing scale across rows | Not done | row paddings are 10dp/8dp/6dp across `item_track`, `item_song`, `item_playlist` |
| 8 | One-line empty state on a Downloads page | Done | `downloadsEmpty` renders "Nothing downloading" — DEVICE confirmed |
| 9 | Tab transitions ≤ 200 ms | Violated | `showTab`/`showPlaylistsTab`/`showDownloadsTab` use `setDuration(220)` |
| 10 | Announce tab changes to accessibility services | Missing | zero hits for `announceForAccessibility` in all 43 Java files |

---

## 3. Backlog items still open

From `docs/BACKLOG.md` → "Ideas worth evaluating" and `docs/PROJECT_NOTES.md` → "Backlog".

| Item | Status | Evidence |
|---|---|---|
| Incremental MediaStore insert instead of a whole-library rescan | Open | `refreshLibrary()` has 9 call sites and each does a full `LibraryStore.query()` (2 sites in `MainActivity`) |
| Cache lyrics to disk | Partly | `Lyrics.java` stores LRC in `SharedPreferences` (`cartify_lyrics_v3`), not as files; no eviction and no size bound |
| Detect an already-playing track and offer jump-to | Open | no such check anywhere; `playQueue` always restarts |
| Per-playlist download when a Spotify playlist is opened | Open | only `Download all` exists |
| Stable task keys instead of list indices for cancel | Open | zero hits for `stableKey`/`taskKey`/`EXTRA_TRACK_KEY`; cancel is still index-based |
| Retry-all failed rows | Done | `retryFailed()` + "Retry N failed" button |
| Audio quality selector | Done | `DownloadQuality`, 5 levels, in the app menu |
| Unit-test `SpotifyResolver` parsing and `DownloadEngine.pickCandidate` | Open | see section 5 |
| Consistent seek/prev in the playback notification | Partly | `setShowActionsInCompactView(0, 1, 2)` = prev/pause/next; `ACTION_SEEK` is handled but has no notification action |

---

## 4. Concrete defects found by reading the code

Each is reproducible from the cited line.

### 4.1 `playDownloadsFrom()` can never play anything — SOURCE

`refreshDownloads()` (MainActivity ~920) builds the list from tracks whose status is
`QUEUED`, `ACTIVE` or `ERROR`. `playDownloadsFrom()` (MainActivity ~981) then looks for
rows with a non-empty `localUri`. Finished tracks are `DONE` and therefore never in that
list, so the toast **"Not downloaded yet"** is the normal outcome.

Fix: either keep `DONE` rows on the page, or hide/disable the play action there.

### 4.2 "Clear finished" clears failed rows — SOURCE

`activity_main.xml` sets `downloadsClear` text to `"Clear finished"`, while
`clearFailedDownloads()` removes `Track.ERROR` entries only. The label and the behaviour
disagree.

Fix: rename to "Clear failed", or also drop `DONE` rows.

### 4.3 The Downloads list is rebuilt on every progress tick — SOURCE

`applyQueueState()` (MainActivity ~1666) calls `refreshDownloads()` unconditionally, and
`applyQueueState` runs from `onTrackProgress`. Every yt-dlp progress callback therefore
does a full `submit()` → `notifyDataSetChanged()` on the Downloads adapter. The external
review flagged this too.

Fix: diff the visible set and only notify on membership change.

### 4.4 `submitted` is cleared before late callbacks can arrive — SOURCE

`onQueueFinished()` clears `submitted` (~1714) and then calls `refreshDownloads()`.
`trackForQueueIndex()` returns `null` once that list is empty, so any progress or
finish callback that lands after the queue callback is silently dropped. Cancel-all
follows the same path.

Fix: keep the list until the next `startDownload`, or key callbacks by identity.

### 4.5 Duplicate imports in `MainActivity` — SOURCE

Lines 5–10 import `android.net.Uri`, `android.provider.Settings`,
`android.provider.MediaStore` twice each. Harmless, but it is the residue of a bad
patch and should go.

### 4.6 Orphaned and dead resources — SOURCE

- `res/layout/item_artist_header.xml` — 0 references; headers moved inside `item_song.xml`.
- `LibraryAdapter.java` — referenced only by its own file; the library uses `SongAdapter`.
- `BrandSound.java` — referenced only by its own file; nothing calls `prepare()` or `play()`, so the brand sound never plays. `MainActivity.playIntro()` is explicitly silent.

### 4.7 Build config the review asked to clean up — SOURCE

- `android:extractNativeLibs="true"` is still in the source manifest; AGP sets this itself.
- `android:largeHeap="true"` remains, which the review ties to bitmap memory pressure.
- `minifyEnabled false` in both build types; no `shrinkResources`, no ProGuard rules.

---

## 5. Architecture and quality gaps

From `Cartify_Final_Review_Report.pdf` (OneDrive Desktop, decoded from its
ASCII85+Flate streams), cross-checked against the tree:

| Gap | Verified how |
|---|---|
| No tests at all | `app/src/test` and `app/src/androidTest` do not exist |
| No CI | `.github/` does not exist |
| No modularisation | `MainActivity.java` is 2,580 lines / 109 KB and owns all four tabs, both adapters, the download queue, playlists, nav styling and scroll memory |
| No Room / SQLite | 0 references to `androidx.room`; playlists are one JSON file, library is re-queried from MediaStore |
| No DI | 0 references to Hilt/Dagger; adapters use static executors and static `IN_FLIGHT`/`WAITING` maps shared across instances |
| No ViewModel / Fragments | 0 `ViewModel` hits; the 15 `Fragment` hits are all the word in download-engine comments |
| No pagination | `MusicSearch.LIMIT = 25` fixed, no `loadMore`/`nextPage`/`pageToken` anywhere |
| No localisation | `strings.xml` contains exactly one string (`app_name`); every other label is hard-coded in XML or Java |
| No accessibility work | 0 `announceForAccessibility`; many `contentDescription`s are the literal `@string/app_name` |

The review scored the project overall ~7/10 with scalability/maintainability at
5/10 and singled out three priorities: split `MainActivity`, move to Room with
incremental inserts, add a test suite plus CI.

---

## 6. Verified working (so it does not get re-listed as missing)

Checked on the phone this session, not assumed:

- Build 1.1.0 (versionCode 3, last updated 2026-11-03 18:39) launches: `topResumedActivity=com.cartify.app/.MainActivity`.
- Four tabs present and correctly labelled: Offline · Playlists · Search · Saved.
- Library renders 221 songs at launch; after one download the folder held 222 files.
- Search works end to end: query "Fix You" → `25 results for Fix You`, ranked results.
- Downloads tab opens to its empty state: `No active downloads` / `Nothing downloading`.
- A single-track download completed with `queue done: 1 saved, 0 failed` and the file landed in `Music/Cartify`.
- Home opens on Recently played (11 tracks, all marked already saved) rather than a public chart.
- No `FATAL EXCEPTION` in logcat across this session.

---

## 7. Suggested order of work

1. Section 1.1 variant guard — it is the only item here that silently saves the wrong audio, which is the failure the whole matching system exists to prevent.
2. Sections 4.1 / 4.2 — two small, self-contained fixes that make the Downloads tab honest.
3. Section 1.2 client logging — cannot tune what is not measured.
4. Sections 4.3 / 4.4 — the tick-driven rebuild and the callback race; both are cheap and both cause visible bugs under load.
5. Section 2 items 1–4 — small UX wins that were already specified.
6. Section 5 — tests and CI, then the `MainActivity` split. Largest effort, largest payoff.

---

*No files were pushed. Nothing was uploaded to GitHub — this is a local list only.*
