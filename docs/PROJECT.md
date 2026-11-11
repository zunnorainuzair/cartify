# Cartify — single source of truth

**Read this file first.** It exists so that any future session — including one with no
memory of previous work — can recover the full state of this project without guessing.

Keep it current. When something changes, update the relevant section here rather than
starting a new dated file. Dated documents in `docs/` are historical; this one is live.

Last updated: 11 November 2026.

---

## 1. What this project is

Cartify is a **Java-only Android app** that turns a song into an offline MP3, keeps a local
library, and plays it back with a full media player.

- Application ID: `com.cartify.app`
- Package root: `app/src/main/java/com/cartify/app/`
- Repository: `github.com/zunnorain700-beep/cartify` (public)
- Status: **beta**, installed on the developer's own phone

### The pipeline

```
Spotify link OR in-app search
        ↓
SpotifyResolver (embed page)  /  MusicSearch (Deezer → iTunes fallback)
        ↓
SpotifyCollection { name, coverUrl, Track[] }
        ↓
DownloadService (foreground, parallel workers, cancellable per row)
        ↓  per track:
DownloadEngine — yt-dlp search (ytsearch10) → score candidates → pick one URL
        ↓
     yt-dlp download + ffmpeg → MP3 at chosen quality
        ↓
Id3Writer (title/artist/album/cover) → LibraryStore.importFile → MediaStore
        ↓
ProgressBus → TrackAdapter rows + notification
```

Playback is independent: `Playback` owns a `MediaPlayer` and a queue; `PlaybackService`
keeps it alive in the foreground with a media session and notification.

### Design decisions that must not be undone casually

1. **No embedded API keys.** Spotify search needs a paid key, so search uses Deezer and
iTunes (both keyless); lyrics use LRCLIB. A key inside an APK can be extracted and would
break for every user at once. This is why Last.fm was rejected and ListenBrainz
(per-user token, no app key) was preferred.
2. **Audio comes from a public video source**, because Spotify audio is DRM-protected.
Metadata still comes from open catalogues.
3. **Matching is scored, not first-hit.** A wrong recording that *looks* saved is the worst
failure this app can have. The variant guard rejects live/remix/karaoke/instrumental and
translated-lyrics uploads unless the user asked for them.
4. **Nothing is uploaded automatically.** Scrobbling, if added, must be opt-in and off by
default. Every existing store (recent plays, search history, playlists) is on-device only.

---

## 2. Build and run — exact commands

These paths are specific to this machine and were used successfully.

| Tool | Path |
|---|---|
| JDK 17 | `C:\Users\HP\AppData\Local\AndroidBuildToolchain\tools\jdk\jdk-17.0.20.1+1` |
| Gradle 8.5 | `C:\Users\HP\AppData\Local\AndroidBuildToolchain\tools\gradle\gradle-8.5\bin\gradle.bat` |
| Android SDK | `C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk` |
| adb | `C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk\platform-tools\adb.exe` |
| Project | `D:\MY PROJECTS\cartify` |

Build + install:

```powershell
$out = 'D:\MY PROJECTS\cartify\_build.txt'
cmd /c "`"C:\Users\HP\AppData\Local\AndroidBuildToolchain\tools\gradle\gradle-8.5\bin\gradle.bat`" --no-daemon --console=plain -p `"D:\MY PROJECTS\cartify`" -Dorg.gradle.java.home=`"C:\Users\HP\AppData\Local\AndroidBuildToolchain\tools\jdk\jdk-17.0.20.1+1`" :app:assembleDebug > `"$out`" 2>&1"
& 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk\platform-tools\adb.exe' install -r 'D:\MY PROJECTS\cartify\app\build\outputs\apk\debug\app-debug.apk'
```

Notes:
- `local.properties` must contain `sdk.dir=C\:\\Users\\HP\\AppData\\Local\\AndroidBuildToolchain\\android-sdk`.
- Gradle prints deprecation notes to stderr, so PowerShell may report `exit code 1` even on
  `BUILD SUCCESSFUL`. **Check for the literal string `BUILD SUCCESSFUL`, not the exit code.**
- Builds take roughly 20–40 s.

### Test device

- Model: Pixel 7 Pro, serial `29021FDH300434`
- UI dumps: `adb shell uiautomator dump /sdcard/x.xml` then `adb shell cat /sdcard/x.xml`.
- **uiautomator times out on the player screen** ("could not get idle state") because of its
  continuous animations. Disable animations first:
  `adb shell settings put global window_animation_scale 0` (and `transition_animation_scale`,
  `animator_duration_scale`), then **restore them to 1.0 afterwards**.
- WhatsApp's lock screen can steal the foreground; press BACK and relaunch.

---

## 3. Generating reports

`python-docx` is available in the bundled runtime; LibreOffice Kit converts to PDF.

| Tool | Path |
|---|---|
| Python | `C:\Users\HP\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe` |
| Node | `C:\Users\HP\AppData\Local\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe` |
| LibreOffice CLI | `C:\Users\HP\AppData\Local\Programs\DeepSeek Harness\resources\app.asar.unpacked\dsh\node_modules\@deepseek-ai\libreoffice-kit\lib\cli.js` |

```powershell
& "<node>" "<cli>" convert --input <file.docx> --output <file.pdf>
& "<node>" "<cli>" render --input <file.pdf> --output-dir <dir>   # note: --output-dir, NOT --output; dir must not already exist
```

Deliverables go to `C:\Users\HP\OneDrive\Desktop` (the real Desktop; `C:\Users\HP\Desktop`
is a separate folder that also exists).

---

## 4. Hard-won operational lessons

**These cost real time. Read them before editing.**

### 4.1 The `edit` tool can report success and change nothing

Multiple times in one session `edit` returned "updated successfully" while the file on disk
was unchanged. The build then failed on a symbol that "existed". **Always verify an edit**
with `grep`/`Select-String` for the new text before building.

Reliable fallback: PowerShell line-index editing.

```powershell
$p = (Resolve-Path 'app\src\main\java\com\cartify\app\MainActivity.java').Path
$enc = New-Object System.Text.UTF8Encoding($false)
$lines = New-Object System.Collections.Generic.List[string]
$lines.AddRange([string[]][System.IO.File]::ReadAllLines($p, [System.Text.Encoding]::UTF8))
# ... splice $lines ...
[System.IO.File]::WriteAllLines($p, $lines.ToArray(), $enc)
```

### 4.2 String `.Replace()` on this repo is unreliable

Java sources here use CRLF. Multi-line `$txt.Replace(old, new)` silently no-ops when
line endings differ. Prefer line-index splicing, or normalise line endings first.

### 4.3 Beware mojibake

An earlier PowerShell rewrite left `" - filtered"` in `MainActivity.java`. Always write
with `UTF8Encoding($false)` (no BOM) and check afterwards:

```powershell
$raw = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
# count of U+00C2 and U+00E2 characters must both be 0
```

Also `-Encoding utf8` in old PowerShell adds a BOM, which breaks the Java compiler
(`illegal character: '\ufeff'`).

### 4.4 Verify with the build, then the device

A claim of "fixed" is only true after `BUILD SUCCESSFUL` **and** an on-device check. Several
"fixes" in this project's history were announced and never landed.

---

## 5. Code map

43 Java files, ~13,000 lines. The big ones:

| File | Lines | Role |
|---|---|---|
| `MainActivity.java` | ~2,776 | **All four tabs**, both adapters, download queue, playlists, nav styling, scroll memory |
| `DownloadEngine.java` | ~1,100 | yt-dlp invocation, candidate scoring, variant guard, retries |
| `PlayerActivity.java` | ~870 | Full player: art pager, seek, lyrics sheet, queue sheet |
| `Playback.java` | ~715 | MediaPlayer, queue, shuffle/repeat, audio focus, volume fade |
| `SpotifyResolver.java` | ~475 | Embed-page parsing, request gating, backoff, cache |
| `DownloadService.java` | ~430 | Foreground queue, workers, cancellation |
| `PlaylistActivity.java` | ~385 | One playlist: songs, search, menu |
| `PlaybackService.java` | ~350 | Media session, notification, ±10 s seek actions |
| `SplashView.java` | ~380 | Particle-word launch animation |
| `Lyrics.java` | ~325 | LRCLIB lookup, strict artist/length matching, LRC parse |
| `Updater.java` | ~315 | GitHub releases, APK download, installer handoff |
| `TrackAdapter.java` | ~320 | Search/download rows, progress, artwork |

Supporting: `Art`, `Net`, `Blur`, `MenuSheet`, `InputSheet`, `MusicSearch`, `LibraryStore`,
`Id3Writer`, `ProgressBus`, `DownloadQuality`, `Track`, `SpotifyCollection`, `Playlist*`,
`SongAdapter`, `CardAdapter`, `QueueAdapter`, `DragRecyclerView`, `WaveProgressView`,
`WaveThumb`, `SquareFrameLayout`, `Recent`.

**Dead files, 0 references** — safe to delete, kept only pending a decision:
`LibraryAdapter.java`, `BrandSound.java`, `SearchHistory.java` (SearchHistory became unused
when the recent-search chip row was removed).

---

## 6. Current state

**v1.2.0** (`versionCode 4`). The previous release was `b133706` / tag `v1.1.0`.

v1.2.0 collects everything that had been sitting uncommitted since v1.1.0: the new flat
logo and black splash, the streaming-latency work (§7-stream), the reworked in-app updater
(§7-update), plus the translated-lyrics rejection, volume fades, parallax art pager,
±10 s seek, cover-cache fix and shelf rework that were already on disk.

### Uncommitted work on disk

| File | Change |
|---|---|
| `DownloadEngine.java` | Rejects explicitly translated-lyrics uploads (`tradução`, `traducido`, `übersetzt`, …) via `isTranslatedMarker()` |
| `Playback.java` | 180 ms volume fade in/out (`FADE_MS`), used by toggle, pause, sleep timer |
| `PlayerActivity.java` | ViewPager2 parallax page transform; press-spring on transport buttons |
| `PlaybackService.java` | `ACTION_SEEK_BACK` / `ACTION_SEEK_FORWARD`, ±10 s, `SEEK_STEP_MS` |
| `TrackAdapter.java` | Checks `Net.bitmap` cache before blanking a cover (removes a guaranteed flash) |
| `MainActivity.java` | Shelf = overlay fading with scroll; collection bar + recent-search row removed; `announceTab()` |
| `activity_main.xml` | `shelfBlock` moved into `resultsArea` overlay; collection bar and `recentRow` deleted |
| `ic_rewind_10.xml`, `ic_forward_10.xml` | New — distinguish a 10-second seek from a track skip |
| `DownloadEngine.java` | Stream pre-warm (`prewarmStream`, `cancelPrewarms`, `prewarmDepth`), `STREAM_CHAINS`, `warmConnection`, adaptive `streamSocketTimeout` |
| `Playback.java` | `open()` no longer hands an `https://` url to the `Context` overload of `setDataSource` |
| `MainActivity.java` | `showCollection()` cancels stale pre-warms and warms `prewarmDepth()` rows |
| `local.properties` | `sdk.dir` points at `D:\tools\android-sdk` (toolchain moved off `C:`) |
| `colors.xml`, `activity_main.xml`, mipmaps, `drawable-nodpi/cartify_logo.png` | New flat logo everywhere; splash background pure black |
| `tools/gen_icons_new.py` | New — regenerates every icon asset from `_brand/logo.png` |

Untracked debris that should probably be deleted: `_backups/`, `MainActivity.java.bak`,
`MainActivity.java.pre_fix152`, `AndroidManifest.xml.pre_singletask`,
`AndroidManifest.xml.pre_task_fix`.

### Layout facts worth knowing

The home pane is a vertical stack: greeting + search box + paste-link card, then status
line, then `resultsArea` (a `FrameLayout`, `layout_weight="1"`) containing the results
`list`, the `shelfBlock` overlay, and `emptyState`.

The shelf overlay pattern matters: a sibling **above** the weighted list steals height
from it, so making it appear/disappear moves every row in one frame. Keeping it as an
overlay with a matching list padding means only transforms change.

---

## 7. Known defects

FIXED on 4 November 2026 (verified on device, not yet committed):

1. ~~Downloads play button cannot work~~ — **fixed.** `playDownloadsFrom()` now falls back
   to `LibraryStore.findSaved(title, artist)` when the row has no local file.
2. ~~Downloads list rebuilt on every progress tick~~ — **fixed.** `applyQueueState()` only
   calls `refreshDownloads()` when the row's membership changes; otherwise it patches the
   single row with `downloadsAdapter.update(dpos, ...)`.
3. ~~"More like this" shows the same songs as the rows~~ — **fixed.** The shelf is now
   filled by `MusicSearch.relatedTo()`, which resolves the seed artist, asks Deezer for
   related artists and pulls their top tracks, excluding everything already in the rows.
   Verified: an Adele search produced a shelf of Sam Smith and Calvin Harris.
4. ~~Download timing unlogged~~ — **fixed.** Each attempt logs
   `attempt N client=... fragments=... quality=...` and each success logs
   `downloaded N bytes, client=...`. A measured run showed
   `attempt 1 client=android,ios fragments=8 quality=0` then 7,205,036 bytes in 47.1 s.

5. ~~`Playback.ensureService()` unguarded `startForegroundService`~~ — **fixed.** It was
   API 26+ while minSdk is 24, and the `catch (Exception)` did not cover `NoSuchMethodError`,
   so the first play tap crashed on Android 7. Now uses `ContextCompat.startForegroundService`
   with a `catch (Throwable)` behind it.
6. ~~Full-resolution artwork in every row~~ — **fixed.** `Net.bitmap(url, targetPx)` decodes
   with `inSampleSize`, `Art.load(..., targetPx)` caches per size bucket, and the row
   adapters request ~200px. Launch PSS fell **199,739 → 152,465 KB** and Graphics
   **103,100 → 76,740 KB**.
7. ~~Unbounded per-row blur bitmaps~~ — **fixed.** `SongAdapter.loadRowBackdrop()` built a
   fresh wash per row with nothing caching or releasing it; GPU memory reached 174 MB.
   Now a bounded 8 MB `LruCache` plus a pre-shrink to 160px. Peak GL fell to ~152 MB and,
   importantly, **stabilises and then falls** on continued scrolling (152,920 → 134,124 →
   133,096 KB), so it is bounded rather than growing without limit.

Still open:

7. **`submitted` cleared before late callbacks** — `onQueueFinished()` clears it, then
   `trackForQueueIndex()` returns null for any straggler.
8. **Full library rescan** after every queue (9 `refreshLibrary()` call sites).
9. **Search hard-capped at 25** — `MusicSearch.LIMIT = 25`, no pagination anywhere.
10. **Shelf fade depends on list scroll offset.** `prepareShelfOverlay()` resets the list
   to position 0 before computing the fade, because the list is reused between searches;
   without that reset the shelf was born at alpha 0. Keep that reset if the shelf is
   touched again.

## 7-update. In-app updates - reworked 11 November 2026

The updater reads `releases/latest` from GitHub and offers a newer tag. The rework fixed
one real bug and made the download survive a bad connection.

### Bugs fixed

1. **A failed check silenced the updater for six hours.** `KEY_LAST_CHECK` was written
   *before* the request, so one launch without a connection meant no check for the rest of
   the day. The clock now starts only when GitHub actually answered (200 or 404).
2. **The install permission grant re-downloaded the APK.** `onResume` called
   `downloadUpdate()` even when the file was already in the cache. It now installs the
   file that is on disk.
3. **A downloaded APK was forgotten on the next launch.** The banner now checks
   `Updater.downloadedApk()` and shows "Update ready - tap to install" instead of
   offering the download again.
4. **The repository had been renamed.** `zunnorain700-beep` is now `zunnorainuzair`;
   GitHub answered the old name with a 301, so every check paid an extra round trip.
   `REPO` now holds the canonical name.

### Added

| Feature | Detail |
|---|---|
| Resumable download | bytes go to `cartify-<tag>.apk.part`; a retry sends `Range: bytes=<n>-` and appends. A server that ignores the range restarts cleanly |
| Cancel | `Updater.cancelDownload()` disconnects the transfer; the partial file is kept so the next tap resumes |
| Integrity | the file must start with the ZIP local-file header, and when GitHub publishes `assets[].digest` the SHA-256 must match |
| Size check | a transfer that ends short of `assets[].size` is rejected instead of handed to the installer |
| Cache hygiene | `cleanCache()` deletes every other `.apk`/`.part` in `cache/updates` |
| Readable notes | `plainNotes()` strips markdown, so the sheet no longer shows literal `##` and `**` |
| Honest manual check | `checkWithStatus()` distinguishes up to date / offline / GitHub unavailable; the automatic check stays silent |
| Size in the UI | the banner and sheet show the download size (`formatSize`) |

### Verified

- `releases/latest` for `zunnorainuzair/cartify` returns `v1.1.0`, asset
  `cartify-1.1.0.apk` (111,305,367 bytes) with a `sha256:` digest.
- The release build is signed with the Android debug key, the same key the installed
  v1.1.0 uses, so an in-app upgrade is accepted by the installer.

## 7-stream. Streaming start latency - measured 11 November 2026

Goal: a tap on a search result should start audio quickly, and the behaviour should
follow the connection rather than assume one.

### What was slow

Resolution (yt-dlp `-g`) was the whole wait, and it was ~27 s per track on the test
network (HostelF2B, 2.4 GHz, ~1.4 Mbps real throughput). Three separate causes:

1. **`tv_embedded` was first in the chain.** It is the right first choice for a
   *download* (it is the only client exposing audio-only formats 139/140/251), but it
   is the slowest to answer. Measured on the same tracks: `android,ios` 10 s vs
   `tv_embedded` 27 s.
2. **The watch page was fetched and parsed** on every resolution, purely to find the
   player config that the innertube clients return from the API anyway.
3. **The pre-warm pool had two threads**, so the previous screen's 30 s pre-warms held
   both and a fresh search sat in the queue — the first tap still paid full price.

### What changed

| Change | Effect |
|---|---|
| `STREAM_CHAINS` — `android,ios` first, `tv_embedded` as fallback | 27 s → 10 s resolution |
| `player_skip=webpage` on attempt 0 only | removes two round trips and a JS parse; a client that needs the page falls through to the next chain, which runs without the skip |
| `prewarmStream()` on the first rows of every collection | tap lands on a ready url |
| `warmConnection()` — 16 KB range GET after resolving | primes DNS/TLS so MediaPlayer's own stack starts faster |
| `STREAM_POOL` 2 → 4 threads, `cancelPrewarms()` on a new collection | a new list no longer queues behind the old one |
| `prewarmDepth()` from the **measured** last resolution, not the link rate | WiFi reports its PHY rate (tens of Mbps) while real throughput is a tenth of that; the last resolution is the honest predictor |
| `streamSocketTimeout()` from `getLinkDownstreamBandwidthKbps()` | 10 s on a fast link, 30 s on 2G |
| `Playback.open()` uses the string `setDataSource` for http(s) | an `https` url on the `Context` overload made ContentResolver throw `FileNotFoundException: No content provider` on the main thread before MediaPlayer silently retried |

### Measured result (Pixel 7 Pro, same network)

| Case | Before | After |
|---|---|---|
| Tap a pre-warmed row | ~30 s | **1.05 s** to audio focus |
| Tap a row that was not pre-warmed | ~30 s | ~15 s (4.3 s resolve + MediaPlayer's own ~10 s connect/buffer) |
| Resolution, cold | 27 s | 10-14 s |

`MediaHTTPService` opens its own connection and does not share the app's pool, so
`warmConnection` helps it only through the warm DNS cache — which is why the pre-warmed
tap is ~1 s and the on-demand one is not.

### Trade-off to keep in mind

`android,ios` exposes no audio-only formats, so a stream falls back to muxed format 18
(~5 MB for a 4-minute song instead of ~3.8 MB). That is acceptable for a stream, which
is discarded when the song ends, and it is why `CLIENT_CHAINS` (downloads, bytes kept
forever) was left untouched with `tv_embedded` first.

## 7a. Download timing - measured 4 November 2026

Phase timing is now logged per track by `DownloadEngine`. Three consecutive real downloads
on the Pixel 7 Pro produced:

| Phase | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| pick (resolve upload) | 0 ms | 0 ms | 0 ms |
| extraction (to first byte) | 6,388 ms | 4,807 ms | 6,885 ms |
| transfer | 11,188 ms | 10,381 ms | 15,152 ms |
| art fetch | ? | 488 ms | 399 ms |
| ID3 tag | ? | 62 ms | 81 ms |
| MediaStore import | ? | 70 ms | 75 ms |
| **total** | **27,398 ms** | **11,028 ms** | **15,724 ms** |

**Conclusion 1 ? it is not the post-processing.** Tagging and saving together are under
600 ms. They were never the problem, and the earlier "47 s" was not conversion.

**Conclusion 2 ? the transfer is slow because it is downloading VIDEO.** The yt-dlp log
names the chosen stream:

```
[info] T1tl66trXTQ: Downloading 1 format(s): 18
[download] Destination: .../track.mp4
[download]   0.0% of    4.98MiB at  393.76KiB/s
```

Format 18 is a muxed 360p MP4 ? video **and** audio together. `-f "bestaudio*/best"` was
supposed to prefer an audio-only stream, but with `player_client=android,ios` those clients
do not expose audio-only formats for most videos, so the `best` fallback matched format 18.
The result is ~5 MB downloaded where ~1 MB of audio exists, and ffmpeg then throws the video
away. Roughly **3-4 MB of every track is wasted**, and at 265-394 KiB/s that waste is most
of the wall time.

**Conclusion 3 ? throughput is genuinely poor.** 393 KiB/s is a slow connection by any
standard, so even a correct audio-only stream would take ~3-4 s. Both halves matter.

### What to try next (not done)

1. Change the format selector to reject muxed video: `-f "bestaudio[acodec!=none]"` then
   fall back to `-f "18/best"` only when nothing else matches, and log which branch was
   taken.
2. Test whether a different client chain (e.g. `web_safari`, `tv_embedded`) exposes
   audio-only formats on this device. That re-introduces extraction cost, so it needs
   measuring ? the old note says 16.5 s became 4.0 s when the JS challenge was avoided.
3. Re-measure `fragmentsForSpeed()`: 8 fragments at 393 KiB/s is more parallelism than the
   link can feed.

### Cover decode ? measured, then the logging removed

Temporary instrumentation recorded per-cover decode time on a cold artwork cache:

```
disk-cached decodes: 25, 30, 41, 43, 49, 51, 87 ms   (median ~41 ms)
output: 200x200 px = 160,000 bytes each
```

**~41 ms per cover is 2.5x the 16 ms frame budget**, so the decode is genuinely above
budget on this device. The cost is the full 640x640 JPEG decode followed by
`createScaledBitmap`; the disk read itself is 0-1 ms. Decoding at target size via
`inSampleSize` on the file would remove the 1.6 MB intermediate.

The logging that produced these numbers has been removed (it was one line per image, i.e.
exactly the log spam flagged as a defect). Re-add temporarily if this is revisited.

## 7b. Playlists in search - 4 November 2026

### What it does

A search now returns playlists as well as tracks. They appear in a rail under the
results; tapping one opens its tracks, and the collection menu downloads it.

Downloading a playlist does two things at once:

1. creates a local Playlist immediately and appends each track as it finishes, so the
   Playlists tab shows it growing rather than appearing only at the end;
2. downloads every track into Music/Cartify through the normal MediaStore path.

Tracks already on the device are added to the playlist and are NOT re-downloaded.

### Verified on device

| Check | Result |
|---|---|
| Playlist rail for "Adele" | 3 playlist cards, header "Playlists" |
| Open "100% Adele" | "30 tracks from 100% Adele - 2 are already saved" |
| Download all and keep playlist | 28 downloaded, 2 already saved, 0 failed |
| files/playlists.json | one playlist, 30 songs, growing during the queue |
| Playlists tab | "100% Adele - 30 songs - 2 hr 10 min" |
| Queue summary | 28 saved, 0 failed, 28 tracks, workers=6, avg 7405 ms/track |
| Fatal exceptions / ANRs | 0 / 0 |

### Bugs found and fixed during testing

1. Shelf covered the results. Opening a playlist showed the shelf above it, leaving
   list [47,677][1033,2277] with shelfBlock [47,677][1033,1864] on top - only 2 of 30
   rows visible. Playlist collections now hide the shelf entirely (7 rows visible).
2. Two rails doubled the overlay. Adding the playlist rail inside the shelf made it
   1187px of a 1600px pane, leaving 2 result rows. Now one rail shows at a time; the
   overlay is 604px and 5 rows are visible.
3. Related rail painted under playlists. The guard ran after cardAdapter.submit(), so
   recRail still rendered 488px of cards. The early return now happens first.
4. Adapter name collision. playlistAdapter already existed as the Playlists-tab adapter;
   the new rail adapter was renamed playlistRailAdapter. Left as-is, the tab's
   refreshPlaylists() would have written into the search rail.
5. LibraryStore.findSaved returns Item, not String. The first version compared an Item
   to an empty string.

### The tradeoff this creates - please read

Deezer returns playlists for almost every query. Measured: Coldplay 8, Adele 8,
Keane 8, Bohemian Rhapsody 8, "lofi beats" 8. Since playlists now take the rail, the
"More like this" recommendation shelf is suppressed for essentially every search.

That shelf was built earlier in this same session. It is not deleted - it still appears
when a search returns no playlists - but in practice that is rare.

If "More like this" should stay visible, the options are, in rough order of cost:

- show only 2-3 playlist cards and put "More like this" below them (still tall);
- give playlists their own smaller card variant so both rails fit;
- move playlists into the results list as a section instead of an overlay rail.

None of these were done: the current behaviour is the one that leaves the most room for
the search results, which are the reason the screen exists.

## 7c. UI glitch sweep - 4 November 2026

Six visual defects reported as "many glitches", each reproduced from a screenshot,
measured in pixels, fixed, and re-measured on device.

### 1. Blank artwork on every result row

Shelf cards showed covers; the result rows below them were empty squares. The cause was
duplication: every other adapter calls `Art.load` (memory + disk cache + embedded-artwork
fallback), but `TrackAdapter` had its own private loader built on `Net.bitmap`, which is
network-only. A cover that failed to arrive left the row blank permanently, with no retry
and no fallback. The row now calls `Art.load` like everything else.

Measured as a file-size signal: the same screen went from 685 KB to 864 KB, which is what
real bitmap content adds to a PNG.

### 2. Full-width neon bar on every saved row

The progress wave was shown when `busy || status == DONE`, so every already-downloaded
track rendered a completely filled, fully saturated bar - the brightest element on screen,
conveying nothing. Now shown only while `busy`.

### 3. Hard-edged green blocks behind the header

`bg_app.xml` drew two decorative `<shape android:shape="oval"/>` items positioned with
negative insets. A layer-list clips each item to its own bounds, so the tint ended in a
hard vertical line. Measured: `rgb(7,29,13)` left of x=452 and `rgb(0,10,2)` right of it -
a 26-point step across a perfectly straight edge. Replaced with radial gradients that fade
to zero alpha, which cannot produce a boundary at any size or density. Re-measured at the
same rows: uniform `rgb(4,24,10)`.

### 4. Black block behind the shelf

After fix 3, `shelfBlock`'s solid `@color/bg_deep` backing was the only flat rectangle left.
It must stay opaque (it hides rows scrolling under it) but it read as a black box against
the new glow. Now a vertical gradient from `@color/surface` to transparent. Shelf band
measured `rgb(7,26,13)` against a page band of `rgb(9,37,17)` - was a 26-point gap, now 11.

### 5. "saved" stranded under the play button

The status row holds a weighted `WaveProgressView` plus the `percent` label. The wave was
only `INVISIBLE`, so it kept its weight and pushed "saved" to the far right edge. Changed
to `GONE`. The row also gained `minHeight="15dp"` so hiding the wave does not shrink the
row and jump the list.

### 6. Black band above the header (status bar)

The window background is a radial glow but `statusBarColor`/`navigationBarColor` were flat
`@color/bg_deep`, the darkest colour in the palette. Measured: `rgb(0,10,2)` above y=108,
`rgb(8,35,16)` below it. Set to `@color/surface`, which tracks the gradient at that height.
Step reduced from 26 points to ~9.

Edge-to-edge was rejected deliberately: the activity has no `WindowInsets` handling at all,
so enabling it would slide the layout under the status bar and clip the header.

### Not a bug (investigated and dismissed)

`TrackAdapter` appeared to contain mojibake (`Ã‚Â·`, `Ã¢â‚¬â€`). A byte-level scan of all Java
sources found none - the console code page was mangling UTF-8 on the way out. Verified with
an explicit `\u00c2\u00b7` search over the raw bytes.

### 7. Player: neighbouring artwork bled past the card edge

Measured a red/yellow strip at x=994..1032 while the artwork itself ended at x=992 - the
next track's cover showing through. The page transformer scaled each page to 0.94 and
shifted it by up to 18dp; ViewPager2 does not clip a child that shrinks or moves, and with
`offscreenPageLimit = 1` the neighbour was already attached, so it filled the gap the
transform opened. The transform is now alpha-only (it cannot move a page out of its slot)
and `artPager` sets `clipChildren`/`clipToPadding` as a second line of defence. Re-measured:
uniform from x=960 to x=1024.

### 8. Player: "PLAYING FROM" was invisible

The label is 9sp in `text_tertiary` over a blurred wash of the cover, which is often light.
Measured `rgb(120,120,112)` on `rgb(121,121,112)` - one point of contrast. The scrim's top
stop was `#7A` (122/255) which is far too weak to darken a bright cover. Now `#C4` and the
label uses `text_secondary` at 10sp. Re-measured: `rgb(55,59,53)` background, a readable gap.

### 9. Lyrics: last line sliced through its glyphs

The lyric list is a fixed-height viewport, so a row is always partly visible at its lower
edge. With no affordance it read as broken text.

First attempt was `android:requiresFadingEdge="vertical"`. It built and changed nothing:
View paints a fading edge only when `getTopFadingEdgeStrength()` is non-zero, and
RecyclerView does not override it, so the strength is always 0. Verified by re-screenshotting
- the line was still cut.

Second attempt was a 26dp gradient view over the list's bottom. That worked, but measured
on device it only reached full opacity in the last few pixels, so the third line was still
half-legible and still looked cut. Raised to 42dp with the midpoint at `#C0`, so most of the
opacity lands within a line's height.

The scrim's end colour is the panel's **rendered** colour `rgb(16,20,15)`, not its declared
`#E6040A06` - the panel is translucent over a blurred backdrop, so the declared value is not
what reaches the screen and fading to it would leave a visible band.

Visibility is funnelled through `showLyrics(boolean)` because the list moved inside a
`FrameLayout` host for the scrim. That host carries `layout_weight="1"`, and a GONE child's
weight is skipped - so a permanently-present host would reserve a large blank area in the
loading and error states.

### 10. Player: neighbouring artwork bled past the card edge

Measured a red/yellow strip at x=994..1032 while the artwork ended at x=992. The page
transformer scaled each page to 0.94 and shifted it up to 18dp; ViewPager2 does not clip a
child that shrinks or moves, and with `offscreenPageLimit = 1` the neighbour was already
attached. The transform is now alpha-only, and `artPager` sets `clipChildren` as a second
line of defence. Re-measured: uniform from x=960 to x=1024.

### 11. Player: "PLAYING FROM" was invisible

9sp in `text_tertiary` over a blurred cover measured `rgb(120,120,112)` on
`rgb(121,121,112)` - one point of contrast. The scrim's top stop was `#7A`, far too weak.
Now `#C4`, the label is `text_secondary` at 10sp. Re-measured background: `rgb(55,59,53)`.

### Verified after the sweep

0 fatal exceptions and 0 ANRs across: home, search results, Playlists tab, Saved tab,
player, expanded lyrics, and the queue sheet.

### 12. Launch animation did not replay

Reported as: the intro plays once and never again, including when the app is reopened from
Recents or returned to after being in the background.

Three independent causes. Each one alone was enough to break the replay, which is why the
first two fixes appeared to do nothing.

1. **`SPLASH_SHOWN` was `static` and never cleared.** A process-wide latch set on first
   launch meant the gate stayed closed for the life of the process. Now an instance field,
   reset whenever the app re-enters the foreground.
2. **`SplashView.start()` refused to run twice.** It opens with `if (anim != null) return;`,
   and `anim` was only cleared by `stop()` - never when the animation completed. A replay
   found the finished animator still assigned and returned immediately. Cleared in
   `onAnimationEnd`.
3. **`reset()` hid the splash it was supposed to show.** `reset()` calls `stop()`, which
   called `anim.cancel()` - and **`Animator.cancel()` delivers `onAnimationEnd`**. That fired
   the *previous* run's `onFinished()`, which animates alpha to 0 and sets the view GONE. The
   replay made it visible and had it hidden again within 320 ms. `stop()` now calls
   `removeAllListeners()` before `cancel()`.

### Foreground detection

`AppLifecycle` counts started activities rather than relying on one activity's lifecycle,
which cannot distinguish "left the app" from "opened a screen on top". Android starts the
incoming activity before stopping the outgoing one, so an in-app jump to the player keeps the
count above zero; leaving the app drops it to zero.

Verified on device: cold launch shows the intro; HOME then reopen replays it (view VISIBLE at
300 ms and 2000 ms); opening the player and pressing back does **not** (`fresh=false`).

### Device caveat found while testing - not an app defect

This handset had `animator_duration_scale` set to `null` (system animations disabled). With
that value every `ValueAnimator` completes in a single frame, so the splash vanished
instantly. That is what made the first three attempts look like failures even after the code
was correct - the logs proved `playIntro()` ran while the screen showed the home view. Set to
`1.0` to test.

The app now respects the setting rather than fighting it: with animations disabled the intro
is skipped, which is the expected behaviour for that accessibility preference.

## 8. Roadmap

Small, high value: fix (1) and (2) above; make the shelf show real recommendations; log the
player client.

Medium: incremental library insert; search pagination; opt-in ListenBrainz scrobbling
(per-user token, no app key — endpoints tested: `submit-listens` needs auth, `stats/sitewide/*`
and `explore/fresh-releases` are keyless, `explore/similar-artists` and `lb-radio` did not
respond to any parameter form tried and remain unverified).

Structural: split `MainActivity`; add tests (none exist — `app/src/test`, `app/src/androidTest`,
`.github` are all absent); move strings into `strings.xml` (currently one entry); delete dead files.

---

## 9. Document history

| File | What it is |
|---|---|
| `docs/PROJECT.md` | **This file — the live source of truth** |
| `docs/HANDOVER-2026-11-03.md` | The v1.1.0 session handover, in plain language |
| `docs/BACKLOG.md` | Feature backlog for that session |
| `docs/VERIFICATION-2026-11-03.md` | Evidence log for the v1.1.0 claims |
| `docs/MISSED-FEATURES-2026-11-04.md` | Defect and gap list |
| `docs/RECOMMENDATIONS-2026-11-04.md` | Prioritised recommendations incl. ListenBrainz findings |
| Desktop `Cartify-Project-Documentation-2026-11-04.pdf` | Five-page PDF covering all versions |
