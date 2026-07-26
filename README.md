# iGpod

A music player for Android, **forked and redesigned from [Gramophone](https://github.com/FoedusProgramme/Gramophone)** (`org.akanework.gramophone`).

iGpod keeps Gramophone's clean Media3-based player core but repurposes it as a
focused, server-synced player for a fixed local music collection rather than a
general-purpose on-device browser.

- **Package:** `com.igeeta.igpod` (debug build: `com.igeeta.igpod.debug`)
- **Upstream lineage:** `FoedusProgramme/Gramophone` (we track its `beta`)
- **This repo:** `gakubhat/iGpod` (default branch `main`)

---

## What iGpod is (vs. Gramophone)

| Area | Gramophone | iGpod |
|------|-----------|-------|
| Music source | MediaStore browsing of all on-device audio | **Always-DB mode** from a fixed folder `/Music/iGeeta` |
| Library scan | MediaStore | DB populated by sync with the iGeeta server |
| First run | Manual setup | **First launch triggers a Sync** |
| Rating | 1–5 stars | **Single heart ⇔ favorite** (`rating >= 3` ⇒ filled) |
| Sleep timer | Custom duration entry | **Dropdown**: End-of-song / 15 / 30 / 45 / 60 / 90 min |
| Playback speed | Built-in control | **Removed** |
| Lyrics / LRC | Full synced-lyrics support | **Removed** |
| Audio / ReplayGain / Equalizer | Supported | **Removed** |
| Experimental / Blacklist / Whitelist | Present | **Removed** |
| Theme | Material You dynamic (Monet) | **Fixed green/avocado theme** (non-dynamic) |
| Launcher icon | Gramophone gramophone | **Jango** artwork |
| App-bar | Default | Brand on the **left** (icon + "iGpod"), search on the **right** |

### Rating model
- A single heart represents *favorite*.
- `track.rating >= 3` ⇒ heart filled.
- Tapping the heart sets `local_rating = 3` (on) or `0` (off) and marks the row
  dirty.
- Dirty rows are pushed to the server on the next sync via
  `POST /api/track/rating?path=<filePath>`.
- On pull, the server's `rating` is applied only when the local row is **not**
  dirty.

### Artwork
Album/track artwork paths are stored in the DB as **relative** paths. At load
time they are resolved against the music root and copied into the app-private
`files/artwork/` directory (scoped-storage safe).

---

## Repository layout & submodules

iGpod builds a **custom Media3 (ExoPlayer) fork in-tree**. The build uses Gradle
`includeBuild` + dependency substitution, so the `androidx.media3:*` artifacts are
replaced by projects compiled from the submodule.

```
.gitmodules
  [submodule "media3"]
      path   = media3
      url    = https://github.com/nift4/media      # nift4's Media3 fork
      branch = gramophone                          # pinned @ 962f72c
  [submodule "hificore/src/main/cpp/libusb-cmake"]
      path   = hificore/src/main/cpp/libusb-cmake
      url    = https://github.com/libusb/libusb-cmake
```

`media3 @ 962f72c` in the commit graph refers to this pinned submodule commit
(the custom ExoPlayer used by Gramophone/iGpod). **The submodule contents are
NOT stored in this repo** — they live in the external repos above.

---

## Building

### Prerequisites
- **JDK 21** (e.g. Android Studio's bundled JBR)
- **Android SDK** with `ANDROID_HOME` set
- Git with submodule support

### 1. Clone with submodules
```bash
git clone git@github.com:gakubhat/iGpod.git
cd iGpod
git submodule update --init --recursive   # REQUIRED — build fails without media3
```

### 2. Configure the toolchain
```bash
export ANDROID_HOME=/Users/gsbhat/Library/Android/sdk
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### 3. Build the debug APK
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/iGpod-*.apk
```

### 4. Install / verify on a device
```bash
adb install -r app/build/outputs/apk/debug/iGpod-*.apk
```
On first launch the app performs an initial **Sync** with the iGeeta server and
populates the library from `/Music/iGeeta`.

---

## Sync server

iGpod syncs against an iGeeta backend. Relevant endpoints used by the app:

- `POST /api/track/rating?path=<filePath>` — upload local rating (heart on/off).
- Library / artwork metadata is pulled during Sync and stored in the local DB.

---

## Notes / known quirks

- **Launcher icon background on Samsung One UI:** the launcher auto-tints an
  adaptive icon's background from the icon's dominant accent (Jango's green
  ring). The Jango foreground art is correct; the green frame is a Samsung
  One UI cosmetic, not controlled by the APK. Toggle "Icon backgrounds" in
  launcher settings to change it.
- This is a **redesign fork**, not a drop-in Gramophone build. Features listed
  as "removed" above are intentionally absent.

---

## License

Inherited from Gramophone / upstream dependencies. See `LICENSE` in the respective
submodules and the original Gramophone project for details.
