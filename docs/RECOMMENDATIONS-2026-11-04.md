# Cartify — recommendations and suggestions

Compiled 4 November 2026 against the working tree at `D:\MY PROJECTS\cartify`
(HEAD `b133706`, versionName 1.1.0, installed build 112,683,970 bytes from 04:48).

Each item is marked with how it was established:

- **SOURCE** — read in the code, with the file and the evidence.
- **DEVICE** — observed on the connected Pixel 7 Pro through adb.
- **API** — tested live against the provider's endpoint.

Ordered by value, not by effort.

---

## 1. Bugs — fix these first

### 1.1 The Downloads tab play button still cannot play — SOURCE

`refreshDownloads()` builds its list from rows whose status is `QUEUED`, `ACTIVE` or
`ERROR`. `playDownloadsFrom()` then looks for a row with a non-empty `localUri`.
Finished tracks are `DONE` and never in that list, so "Not downloaded yet" is the normal
outcome. **A previous session claimed this was fixed; reading the code shows the fix was
never applied** — line 1047 is still the original implementation.

*Fix:* keep `DONE` rows on the page, or hide the play action there.

### 1.2 The Downloads list rebuilds on every progress tick — SOURCE

`applyQueueState()` (line ~1841) calls `refreshDownloads()` unconditionally, and it runs
from `onTrackProgress`. Every yt-dlp callback therefore does a full `submit()` →
`notifyDataSetChanged()`.

*Fix:* diff the visible set; only notify on membership change.

### 1.3 `submitted` is cleared before late callbacks arrive — SOURCE

`onQueueFinished()` clears `submitted` (line 1889). `trackForQueueIndex()` returns null
afterwards, so any progress or finish callback landing late is dropped.

*Fix:* keep the list until the next `startDownload`, or key callbacks by identity.

### 1.4 "More like this" does not show different music — SOURCE

The shelf is populated with `cardAdapter.submit(resolved.tracks)` (line 1526) — **the
same songs as the rows**. It is a duplicate view, not a recommendation. The label promises
something the code does not do.

*Fix:* feed it a genuinely different set (see 3.2).

---

## 2. Performance and correctness

### 2.1 Whole-library rescan after every queue — SOURCE

`refreshLibrary()` has **9 call sites**, and 2 of them run `LibraryStore.query()` over the
entire MediaStore. On 222 songs that is fast; at a few thousand it will not be.

*Fix:* insert each finished track incrementally instead of rescanning.

### 2.2 Search is hard-capped at 25 results — SOURCE

`MusicSearch.LIMIT = 25`, and there is **no pagination anywhere** (0 hits for
`loadMore`/`nextPage`/`pageToken`). A song that misses the first page is unreachable.

*Fix:* add "show more" with an offset.

### 2.3 Download player client is never logged — SOURCE

A measured download took **16,421 ms** against the handover's documented ~4 s. The log
records the match but never which player client or format id actually ran, so the
regression cannot be diagnosed.

*Fix:* one log line naming the client and format used.

---

## 3. ListenBrainz — tested live, recommended

Unlike Last.fm, ListenBrainz needs **no app-level API key**: each user supplies their own
token, so nothing secret ships in the APK and nothing leaks through the public repo.

| Endpoint | Result |
|---|---|
| `POST /1/submit-listens` no auth | **401** |
| `GET /1/validate-token` no/bogus token | **400** |
| `stats/sitewide/artists` | **200** (2,550 B) |
| `stats/sitewide/recordings` | **200** (8,585 B) |
| `stats/sitewide/release-groups` | **200** (6,808 B) |
| `explore/fresh-releases` | **200** (991,094 B — needs caching) |
| `metadata/artist` | **200** |
| `explore/similar-artists` | **404** (unverified, not necessarily broken) |
| `lb-radio/artist` | **400** |
| `popularity/artist` | **404** |

MusicBrainz resolves keylessly (`artist?query=Coldplay` returned the correct MBID;
`X-RateLimit-Limit=400`).

### 3.1 Scrobbling — small and high value

`Playback.loadCurrent()`'s `onPrepared` listener is already the single choke point every
play path goes through (that is where `Recent.remember()` lives). A submit would hang off
the same place. No architectural change needed.

**Must be opt-in and off by default.** Every other store in this app is on-device only.

### 3.2 Fresh-releases shelf — replaces the fake shelf

`explore/fresh-releases` is keyless and returns real artwork, so it would fix 1.4 at the
same time. Caveat: that response was **991 KB**, so it needs caching and paging.

### 3.3 Similar-artists radio — do not start yet

Every ListenBrainz recommendation endpoint is **MBID-keyed**, and Cartify's `Track`
objects come from Deezer/iTunes with no MusicBrainz IDs. Each suggestion would need an
extra MusicBrainz lookup, and MB allows ~1 request/second — a 25-item shelf would take
about 25 seconds. Worse than what exists.

---

## 4. Architecture

### 4.1 `MainActivity` is 2,776 lines — SOURCE

It owns all four tabs, both adapters, the download queue, playlists, nav styling and
scroll memory. The external review scored scalability **5/10** and named this the top
liability.

*Fix:* split into four screens or Fragments.

### 4.2 No tests, no CI — SOURCE

`app/src/test`, `app/src/androidTest` and `.github` **do not exist**. Several bugs this
session would have been caught by one test: the shelf measurement that silently returned
0, and the translated-lyrics match.

*Fix:* a handful of unit tests over matching rules; a CI job running assemble + tests.

### 4.3 Dead code — SOURCE

- `LibraryAdapter.java` — **0 references** outside itself.
- `BrandSound.java` — **0 references**; the brand sound never plays.
- `SearchHistory.java` — **0 references** after the recent-searches row was removed.

---

## 5. Media-player features worth adding

Verified absent (0 references each):

| Feature | Why it matters |
|---|---|
| **Gapless playback** | `setNextMediaPlayer` unused. Albums with continuous tracks have a gap. |
| **Crossfade** | Natural for a player that already fades on pause. |
| **ReplayGain / normalisation** | Downloaded MP3s vary wildly in loudness. |
| **Playback speed** | `setPlaybackParams` unused; useful for long mixes. |
| **Equalizer** | Android's `AudioEffect` is free to wire up. |

Already present and working: sleep timer (`setSleepTimer`, 3 references), audio focus,
queue, shuffle, repeat persisted across restarts, scrollable lyrics.

---

## 6. Smaller polish

- **Library has only 3 sort modes** (`librarySort = (librarySort + 1) % 3`) — Newest, Title, Artist. No album or duration sort.
- **`strings.xml` holds one string** (`app_name`); everything else is hard-coded in layouts and Java, which blocks any second language.
- **Accessibility** — only 1 `announceForAccessibility` call in 43 classes; many `contentDescription`s are the literal `@string/app_name`.
- **Manifest** — `android:extractNativeLibs="true"` is still declared in source (AGP sets it itself and warns); `android:largeHeap="true"` remains.
- **No R8** — `minifyEnabled false` in both build types, no `shrinkResources`, no ProGuard rules. The APK is 112 MB, most of it native libs.

---

## 7. What already works (do not re-list as missing)

Verified on the device this session: four tabs (Offline · Playlists · Search · Saved),
222-song library, search with ranked results, single-track download completing with
`queue done: 1 saved, 0 failed`, Downloads empty state, player opening with `audio focus
granted`, the shelf fading smoothly over ~350px (samples 1260 → 1074 → 883 → 692 → gone),
0 fatal exceptions and 0 ANRs.

---

## 8. If you only do five things

1. Fix the Downloads play button (1.1) — it is visibly broken to any user.
2. Stop the per-tick list rebuild (1.2).
3. Make the shelf show real recommendations (1.4 + 3.2).
4. Add scrobbling, opt-in (3.1).
5. Add tests and CI (4.2) — the cheapest insurance against the class of bug that has
   repeatedly shipped here: changes that report success while doing nothing.
