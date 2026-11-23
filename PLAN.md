# Camera — build plan

A from-scratch replacement for the stock OnePlus 15 camera app (OxygenOS 16), in the ClaudeBorne look, shipped as a
self-updating APK. This file is the source of truth for scope and order. Each coding session does one phase (or one
part of a phase), ticks the checklist, and stops.

- Repo: `C:\Users\Amir Rashid\my-camera` → GitHub `zeronuker/my-camera` (public; created in Phase 0 with the owner's OK)
- App name: **Camera** · package: `com.claudeborne.camera` (never change it later: Android would treat it as a new app)
- Target phone: OnePlus 15 (CPH2747), **Android 17 (SDK 37)**, OxygenOS build CPH2747_17.0.0.102. Rear: 50MP main 24mm f/1.8 OIS, 50MP ultrawide 16mm, 50MP 3.5x periscope
  (73mm, in-sensor crop to 7x). Front: 32MP AF.
- **What apps actually get (probe 2026-11-23, `docs/camera-probe.json`):** logical rear camera `0` (zoom 0.67–20) with physical `2` main (5.59 mm f/1.88, min focus 10 cm), `3` ultrawide (2.31 mm f/2.0, min focus 3.5 cm), `4` tele (12.19 mm f/2.85, min focus 40 cm); front `1` (3.23 mm f/2.4, no flash). Every lens outputs at most 4096x3072 (12.6 MP, binned; front 3280x2464). All LEVEL_3 with MANUAL_SENSOR, RAW, 10-bit, JPEG_R. Camera Extensions: BOKEH + NIGHT on rear `0`, NIGHT on front `1`, nothing else. OIS not controllable. Re-run Diagnostics after OxygenOS updates; if anything new appears, update this file.
- Stock UI reference: `docs/stock-reference/` (45 screenshots of the stock app named by screen, plus two Soft light sample photos 46/47). Local only — git-ignored, never pushed.

## Decisions already made (do not re-ask)

| Topic | Decision |
|---|---|
| Quality | Highest available. Use OnePlus's own processing wherever Android exposes it (Camera Extensions); otherwise our own multi-frame processing. |
| Look | OxygenOS layout and floating glass panels (as in the screenshots and in my-gallerymanager's `Glass.kt`/`Pill.kt`), ClaudeBorne colours and fonts (mint replaces OnePlus amber), chamfer frames on settings cards and dialogs. Viewfinder is always dark; settings screens follow the phone's light/dark theme. |
| Filters | Our own looks with our own names. Never copy OnePlus LUTs or use film brand names (Portra, 800T, Tri-X, etc.). |
| Doc scanner | Our own (ClaudeBorne look), not Google's ready-made scanner. Revisit only if quality is not good enough. |
| Dual view | Every layout (PiP movable/resizable/swappable, top-bottom, side-by-side); recording as one combined video or two separate files, chosen in settings. |
| Quad view (3 rear + front) | Only if the phone allows it. If blocked, offer the largest combination the phone allows, or drop the feature. Never fake it by fast-switching cameras. |
| Thumbnail tap | Unlocked: open in my-gallerymanager and swipe through the camera roll. Locked: our own mini viewer showing only photos taken in this lock-screen session. |
| Watermark | On/off switch in settings (plus the stock sub-options, see Settings). |
| Location, shutter sound | Both in, each with a switch. |
| Signing | Reuse my-gallerymanager's release keystore (same four secrets copied to the new repo). |
| Updates | Same as my-gallerymanager: push to `main` → GitHub Action builds a signed APK → GitHub Release → the app offers it. |
| Beta | **Beta 1 = end of Phase 3**: photo, video, lock-screen launch, gallery handoff, QS tile — usable as the daily camera. Phases 4–10 arrive as in-app updates on top. The stock camera stays installed as a fallback. |
| Stock camera | **Never disable it.** Since Android 11, other apps' "take a photo for me" requests (`IMAGE_CAPTURE`, `VIDEO_CAPTURE`) go only to the pre-installed camera; if it is disabled those apps crash instead of falling back. Banking QR scans and face checks run their own in-app camera and are not affected by this app either way. |
| Desktop preview | Android emulator on the PC (existing `android-35` x86_64 image; webcam as camera) for layout work, and **scrcpy** to mirror and control the real phone on the PC. Coding sessions take screenshots of either (`adb exec-out screencap -p`) and compare them with `docs/stock-reference/`. |
| Dev build | Debug builds install as a separate app, **"Camera Dev"** (`com.claudeborne.camera.dev`, debug key), side by side with the release app, sent straight from the PC with `adb install` (USB or wireless debugging). Layout tweaks never need a push. |

## Feasibility tags

- **OK** — buildable with public Android APIs.
- **PROBE** — depends on what the OnePlus 15 exposes to third-party apps; Phase 0 finds out. If the probe says no, the control is hidden (never shown greyed-out or faked).
- **NO** — Android does not allow it for a non-system app. Listed with the substitute we ship.

---

## Tech stack

Same toolchain as my-gallerymanager, so builds are known to work:

- Kotlin 2.0.21, Jetpack Compose (BOM 2024.10.01), AGP 8.7.3, Gradle 8.9 (CI), JDK 17.
- `compileSdk 35`, `targetSdk 35`, `minSdk 34` (single-device app; 34 gives Ultra HDR JPEG and the newer Camera2 APIs without fallbacks). The phone runs Android 17 (SDK 37); the app runs there unchanged. Raise compileSdk only if a later phase needs an API 36/37 feature, with the owner's OK.
- **Camera2 directly, no CameraX.** CameraX cannot drive physical lenses separately, multi-stream sessions, manual sensor control or high-speed sessions the way this app needs.
- **One OpenGL preview/record pipeline from day one** (`gl/`): camera → `SurfaceTexture` → shader chain → screen + video encoder surface + analysis frames. Filters, master styles, tilt-shift, retouch, video watermark, video depth-of-field and dual-view composition all plug into it. Building it later would mean rewriting the preview.
- Video: `MediaCodec` + `MediaMuxer` (not `MediaRecorder`), so frames can pass through the GL pipeline.
- Settings: `SharedPreferences` + Compose state, same pattern as my-gallerymanager `data/Settings.kt`.
- Saving: MediaStore, `DCIM/Camera/`, names `IMG_yyyyMMdd_HHmmss_SSS.jpg|heic|dng`, `VID_….mp4` (my-gallerymanager already treats `DCIM/Camera` as the camera album).
- Build variants: **release** = arm64 only, signed with the shared keystore; `-Pemulator` also keeps x86_64 (same switch as my-gallerymanager). **debug** = `applicationIdSuffix ".dev"`, label "Camera Dev", arm64 + x86_64, so it runs on the phone and the emulator next to the release app. Every native library added later (OpenCV, LiteRT, MediaPipe) must ship x86_64 too, or be skipped on the emulator with its control hidden.

Libraries, added only in the phase that needs them (each one needs the owner's OK when added):

| Library | For | Phase |
|---|---|---|
| ML Kit Barcode Scanning (bundled) | QR codes | 6 |
| ML Kit Text Recognition v2 (bundled, Latin + Chinese) | Text mode, text suggestions | 6 |
| ML Kit Face Detection / Face Mesh | face metering, retouch, distortion correction | 4, 8 |
| ML Kit Subject / Selfie Segmentation | portrait blur fallback, video depth of field | 7 |
| MediaPipe Hand Landmarker | "show palm" timer | 6 |
| OpenCV (arm64 only) | doc scanner edges/warp, night/HDR frame alignment, panorama stitching | 6, 7 |
| LiteRT + Real-ESRGAN model (copied from my-gallerymanager) | AI Telescope Zoom | 7 |

## Code layout (single `app` module)

```
com.claudeborne.camera
  MainActivity.kt          launcher + camera intents + secure (lock screen) mode
  camera/                  Camera2 engine: open/close, sessions, lens switching, zoom, AF/AE, extensions
  gl/                      preview + record render pipeline and shaders
  capture/                 photo save: JPEG/HEIF/DNG/Ultra HDR, EXIF, location, motion photo writer
  video/                   MediaCodec recorder, audio, slow-mo, time-lapse
  modes/                   one file per shooting mode
  proc/                    night/HDR merge, long exposure, panorama, document warp, AI zoom
  ml/                      ML Kit / MediaPipe wrappers
  data/                    Settings.kt, Updater.kt, Probe.kt
  ui/                      ported ClaudeBorne theme + camera screens + settings
```

Ported from my-gallerymanager (copy, then rename package; do not import across repos): `ui/Theme.kt`, `ui/Motion.kt`,
`ui/Components.kt` (BrandButton, CbDialog, doubleChamferFrame, WaitingLine/LiveDot), `ui/Glass.kt`, `ui/Pill.kt`,
`ui/SvgIcon.kt`/`Icons.kt` as needed, `ui/UpdateDialog.kt`, `data/Updater.kt` (change `REPO` to `zeronuker/my-camera`
and the file regex to `camera-(\d+)\.apk`), fonts in `res/font/`, `.github/workflows/build.yml` (rename APK to
`camera-$VERSION_CODE.apk`), the signing block in `app/build.gradle.kts`, `.gitignore`.

---

## Phases

Each phase ends with: tests green (where the repo has them), APK built, the owner installs it and checks the
"done when" list on the phone. Nothing in a later phase starts until the owner confirms the current one.

| Phase | Content | Model |
|---|---|---|
| 0 | Skeleton, CI, updater, theme, **hardware probe** | Sonnet 5.5 medium to build; **Opus 5.5 high to read the probe report and update this plan** |
| 1 | Photo mode core | Sonnet 5.5 medium |
| 2 | Video, slow-mo, time-lapse | Sonnet 5.5 medium |
| 3 | System integration + gallery handoff → **Beta 1** | Sonnet 5.5 medium |
| 4 | Master (Pro) photo + Pro video | Sonnet 5.5 medium (Opus if DNG/log gets stuck) |
| 5 | Looks and overlays | Sonnet 5.5 medium |
| 6 | Text, QR, Lens, doc scanner, gestures | Sonnet 5.5 medium (Opus for the doc scanner edge finder) |
| 7 | Computational photography | **Opus 5.5 high** |
| 8 | Retouch / beauty | **Opus 5.5 high** |
| 9 | Dual view + quad view | **Opus 5.5 high** |
| 10 | Polish and animation audit | Sonnet 5.5 medium |

### Phase 0 — skeleton, CI, updater, probe

Build:
- Gradle project as above, ported theme/components/updater, splash, empty camera screen with a working Settings → About page (version, build number, CHECK FOR UPDATES).
- `.github/workflows/build.yml`, signing from secrets, `camera-<versionCode>.apk` releases.
- With the owner's OK at that moment: create public repo `zeronuker/my-camera`, push. **The owner sets the four keystore secrets personally** (the session gives the exact `gh secret set …` commands; it never sees or types a password).
- Build variants and `-Pemulator` switch as in Tech stack; "Camera Dev" debug app.
- Desktop preview setup: create/reuse an AVD from the installed `android-35` x86_64 image with the webcam as camera; install scrcpy (download only with the owner's OK); walk the owner through enabling USB/wireless debugging on the phone. Show the loop once: build debug → `adb install` → screenshot → compare.
- Crash log: uncaught exceptions are written to a file in app storage; Diagnostics shows the latest ones with a SHARE button. No third-party crash service.
- **Diagnostics screen** (Settings → About → Diagnostics; it stays in the app afterwards). It collects and shows, and saves as `Download/camera-probe.json` with a SHARE button:
  - Every camera ID and every physical camera ID behind each logical camera: facing, focal length, aperture, sensor size, pixel array, min focus distance, hardware level, capabilities (MANUAL_SENSOR, RAW, LOGICAL_MULTI_CAMERA, ULTRA_HIGH_RESOLUTION_SENSOR, DYNAMIC_RANGE_TEN_BIT, CONSTRAINED_HIGH_SPEED_VIDEO, BURST_CAPTURE, STREAM_USE_CASE).
  - Output sizes per format: JPEG, HEIC, JPEG_R (Ultra HDR), RAW_SENSOR, YUV_420_888, PRIVATE; maximum-resolution sizes.
  - Zoom ratio range, video/OIS/preview stabilisation modes, AE fps ranges, high-speed video sizes and fps, dynamic range profiles (HLG10, HDR10, HDR10+, Dolby Vision), tonemap modes, face detect modes, AF modes, flash.
  - Camera Extensions per camera (AUTOMATIC, BOKEH, FACE_RETOUCH, HDR, NIGHT) with their supported sizes.
  - Concurrent camera ID sets, plus live tests: configure a session with 2 and with 3 physical rear streams; open front + rear together; front + 3 rear. Record success or the exact error.
  - Video encoders: HEVC/AVC (and Main10 HDR) max size and fps, including 8K30 and 4K120.
  - Which app currently answers `STILL_IMAGE_CAMERA`, `STILL_IMAGE_CAMERA_SECURE`, `IMAGE_CAPTURE`, `VIDEO_CAMERA`.
- Manifest already declares those camera intents, so the owner can set this app as default camera and test the power-button double-press (result recorded in the checklist below).

Done when: APK installs; the app updates itself from a second CI build; "Camera Dev" installs next to it from the PC and runs on the emulator; scrcpy shows the phone on the PC; Diagnostics shows all sections and saves the JSON; the owner sends the JSON. **Then an Opus session reads it and rewrites every PROBE tag in this file to OK or NO.** (Done 2026-11-23 from `docs/camera-probe.json`; only the power double-press is still PROBE, pending the owner's test.)

### Phase 1 — photo mode core

Viewfinder layout exactly as `01-photo-mode.jpg`, in the ClaudeBorne look. Buttons whose feature comes in a later phase (Live photo, Google Lens, retouch, filters, Soft light, etc.) stay hidden until that phase builds them.
- First run: ask for camera, then microphone (on first video), then location (only if "Add location tags" is on). Denied → a clear ClaudeBorne screen explaining why, with a button to the app's system settings. Never a black viewfinder.
- Errors handled, never a crash or frozen preview: camera busy in another app (message + retry when it is free), camera disconnected (reopen), overheating (`PowerManager` thermal status: warn, then lower video quality / stop recording safely), storage full (warn before capture), photo still saving when the app closes (MediaStore `IS_PENDING` write finishes in the background).
- Camera released in `onPause` within a moment, so other apps (banking QR/face check) can open it right away. Test: open this app, switch to the bank app's scanner, it must work.
- Top-left glass pill: flash, Live (motion) photo, `EV 0.0`. Top-right: Google Lens button, 6-dot menu.
- Left floating badges: auto-macro, low-light/night hint.
- Row above the shutter: retouch button (left), lens pills `0.6 · 1x · 2 · 3.5 · 7` (center), filters button (right). Pinch zoom with a zoom ruler.
- Bottom: last-shot thumbnail, shutter, front/rear flip. Mode strip with the selected mode in a pill; swipe to change mode.
- Tap to focus + exposure (focus box with sun slider), long-press AE/AF lock, EV dial with reset (`20-exposure-dial.jpg`). Toasts like "Flash: Off".
- Photo 6-dot menu (glass panel, `44-photo-6dot-menu-b.jpg`): Aspect ratio (4:3, 16:9, 1:1, full), Soft light*, Action mode*, Macro shot*, Auto HDR*, Timer (off/3/5/10 s), Interval shooting, Settings. *Items marked * become live in Phase 5/7; until then hidden.
- Flash: off / auto / on / torch. Front camera: screen flash.
- Capture: JPEG and HEIF (per Formats setting), EXIF, location tag, mirror selfie, shutter sound switch, orientation.
- Lens focal lengths: extra 28 mm, 35 mm, 243 mm stops (toggled in settings) and default main 24/28/35 mm.
- Volume keys: shutter / zoom / volume (setting). Touch-and-hold shutter: burst (Clear / Classic). Tap anywhere to capture (setting).
- Thumbnail → opens the photo in my-gallerymanager (`ACTION_VIEW` with `setPackage`, falling back to a chooser). Swiping comes in Phase 3.
- Settings screen skeleton: back button, segmented tabs General / Photo / Video, grouped cards (`10-settings-general-tab.jpg`), with every row from the Settings section below present; rows for later phases are hidden until built.
- Normal photos: best plain capture (max-quality JPEG, HIGH_QUALITY noise reduction and edge modes, 4096x3072). OnePlus exposes no AUTOMATIC/HDR extension (probe). Save as **Ultra HDR JPEG (JPEG_R)** by default when the format is JPEG; HEIF per Formats setting.

Done when: every control above works on the phone; photos land in `DCIM/Camera` with correct rotation, mirror and location; the gallery manager opens them.

### Phase 2 — video

- Video mode layout as `03-video-mode.jpg`; red record button, timer, pause/resume, snapshot while recording (still photo or motion photo, per setting).
- Quality pill picker: 720p / 1080p / 2K / 4K × 30 / 60 on every lens; 4K 120 on the main lens only and 1080p 120 on all rear lenses (both via constrained high-speed session; check the GL pipeline holds 120 fps). **No 8K**: the camera gives apps at most 4096x3072. Hide combinations the current lens cannot do.
- Video 6-dot menu (`45-video-6dot-menu-b.jpg`): 10-bit HDR (HLG10 / HDR10 / HDR10+, HEVC Main10; instead of Dolby Vision), Ultra Steady (= Android `PREVIEW_STABILIZATION`, not OnePlus's own), Focus lock, Settings.
- Dolby Vision check: the camera lists `DOLBY_VISION_10B_HDR_OEM`. Look for a `video/dolby-vision` encoder; if one exists and a test recording plays back as Dolby Vision, add it (owner's OK first); otherwise leave it NO.
- Stabilisation, HEVC/AVC switch, bitrate Standard/High, adaptive frame rate (off / 30 / 30+60), lock lens, lock white balance, smooth lens switching while recording.
- Slo-mo (constrained high-speed session): 1080p / 720p at 120 or 240 fps on all rear lenses, 4K 120 on the main lens; not on the front camera. Time-lapse (speed choices).
- Audio: stereo AAC; wind noise not promised.

Done when: each resolution/fps the probe allowed records, plays in the gallery manager, and has sound.

**Built in Phase 2 (facts learned, details in the hand-over note):**
- Normal video (up to 4K60) goes camera → GL texture → screen and encoder surface (second EGL surface in the same share group, one frame event per camera frame). A photo while recording is one picture read back from the same GL frame (video resolution, plain JPEG); it is hidden in 10-bit HDR.
- 120 and 240 fps cannot go through the GL texture: a constrained high-speed session only delivers every frame to a *recording* surface, a preview surface gets about 30 fps. So while recording at 120/240 the camera is switched to a high-speed session that writes straight into the encoder (the screen keeps a slower GL preview) and the file is turned by an orientation hint. Slo-mo files are stretched to play at 30 fps (120 fps = 4x slower, 240 = 8x), without sound.
- **HDR10+ is NO**: HDR10+ needs per-frame dynamic metadata, which the GL redraw cannot carry. 10-bit offers HLG10 and HDR10 (HEVC Main10). The on-screen preview of 10-bit is not tone-mapped.
- Time-lapse speeds 10x / 30x / 60x / 120x / 300x, one kept frame every speed/30 s, played at 30 fps, no sound. SLO-MO is hidden on a camera without high-speed sizes.
- The mode strip is VIDEO · PHOTO · SLO-MO · TIME-LAPSE until the More grid exists (Phase 10 reorders).
- Dolby Vision: the probe only listed AVC/HEVC encoders, so it could not answer; Diagnostics now also lists `dolbyVisionEncoders` (re-run it on the phone). Not offered.

### Phase 3 — system integration and gallery handoff

- Default-camera intents: `STILL_IMAGE_CAMERA`, `STILL_IMAGE_CAMERA_SECURE`, `VIDEO_CAMERA` (power double-press, if OxygenOS honours a third-party default — PROBE).
- `IMAGE_CAPTURE` / `VIDEO_CAPTURE` (return result to the calling app): declared, but since Android 11 only apps that show their own camera chooser or name this app can reach it; the system sends everyone else to the stock camera. Not a promise to make "the camera for all apps".
- Lock-screen (secure) mode: `showWhenLocked`, no settings access, thumbnail opens our own mini viewer limited to this session's shots.
- Unlocked thumbnail: open in my-gallerymanager **with swiping through `DCIM/Camera`**. This needs a small change in the my-gallerymanager repo (accept `com.android.camera.action.REVIEW` and open the viewer on the camera album at that item) — separate session in that repo, with the owner's OK.
- Quick Settings tile, launcher long-press shortcuts (selfie, video).
- **Quick launch by double-pressing a volume key with the screen off: NO** (Android does not deliver key presses to apps while the screen is off). Substitutes: power-button double-press via default camera (if the probe test passed), lock-screen shortcut, QS tile.

Done when: the power double-press (if supported), QS tile and lock-screen launch open this app; locked users cannot see older photos; unlocked thumbnail swipes through the roll.

### Phase 4 — Master (Pro) and Pro video

- Master mode (`04-master-mode.jpg`): Auto / Pro switch; JPG / RAW / RAW+JPG pill; lens pills; styles button (stacked cards).
- Pro controls: ISO, shutter speed, white balance (presets + Kelvin), manual focus, EV — each with an AUTO state. Only what MANUAL_SENSOR allows.
- Master 6-dot menu (`41-master-6dot-menu.jpg`): Aspect ratio, Timer, Histogram, Face metering, Interval shooting, Settings.
- Assistants: live histogram, focus peaking, zebra stripes, level.
- RAW DNG via `DngCreator`: every lens including the front, 12 MP (4096x3072; front 3280x2464).
- Pro video (More → PRO VIDEO): the same manual controls while recording, shutter shown as shutter angle option, audio level meter, our own log-style flat profile (no OnePlus log exposed; tonemap CONTRAST_CURVE or a GL curve, best on 10-bit HLG), LUT preview.

### Phase 5 — looks and overlays

- Filters panel (`14-filters-a.jpg`…`19`): Original + ~14 of our own looks (film, CCD-flash, B&W, food, vivid…), live preview, info button. 3D LUTs in the GL pipeline, applied identically to the saved photo.
- Master styles page (`21-master-styles-a.jpg`): swipeable presets starting with Standard; reset button; seven controls, left to right (confirmed on the phone): Soft light (plain circle, same 10-position scale as below, default None), Tonality (half circle), Saturation (bars), Cold/Warm (thermometer), Cyan/Magenta (ring of dots), Sharpness (triangle) — these five default 0 — and Vignette (square with circle, On/Off, default On).
- Scenes: None / Stage / Silhouette / Fireworks (exposure + tone presets). Opened from the Video mode label in the mode strip, which is both a swipe target and a dropdown (`13-scenes-panel.jpg`).
- Soft light (Photo and Portrait 6-dot menus, and Master styles): software mist/bloom look; the flash never fires. 10 positions: None, 2 notches, Weak, 2 notches, Medium, 2 notches, Strong. Effect at Strong (`46-soft-light-off.webp` vs `47-soft-light-strong.webp`): bright areas bleed a wide white halo over nearby edges, blacks lift slightly, contrast drops, highlights clip; shadow detail mostly unchanged. Build in the GL pipeline: threshold highlights → wide blur → screen blend + small black lift, all scaled by the position, so preview and saved photo match.
- Watermark (`26-settings-watermark.jpg`): on/off; add to Photos / Videos / Photos & videos; "Shot on <phone model>", location, date and time, custom text; font size; position. Burned into video through the GL pipeline.
- Composition: grid None / Grid / Diagonal lines / Golden spiral; level.
- Tilt-shift mode (blur band, movable/rotatable), Underwater mode (colour correction + volume keys as shutter).

### Phase 6 — text, QR, Lens, doc scanner, gestures

- QR scanning in Photo mode (setting), result chip → open / copy / share.
- "Recommend Text Scanner": when text fills the frame in Photo mode, suggest Text mode.
- Text mode (`07-text-mode.jpg`): live page outline (`42-text-6dot-menu-b.jpg` shows the yellow outline — ours is mint), capture → recognised text in an editor: select, edit, copy, share. 6-dot: Aspect ratio, Settings.
- Scan Docs (More): our own scanner — live edge detection, auto/manual corners, perspective flatten, enhance (colour / greyscale / B&W), multi-page, save as JPEG or PDF.
- Google Lens button: current frame → Google Lens via the Google app; hidden if the Google app is missing.
- Shooting methods: tap to capture, show palm → 3 s timer.
- Smart suggestions: "Move further away" in Portrait, "Try Night" in low light, lens-smudge hint is **not** promised.

### Phase 7 — computational photography (Opus)

Probe result: OnePlus exposes only NIGHT (rear + front) and BOKEH (rear). Extension sessions give a finished JPEG: no manual controls, no RAW, and our looks (filters, Soft light, styles) are applied to the decoded photo afterwards, not live in the session; preview still runs through our GL pipeline.
- Night mode + night badge in Photo (`1s` pill): NIGHT extension, rear and front. Tripod detection for longer exposures only if the extension allows it; else our own multi-frame align + merge as the "long night" option.
- Daytime experiment: shoot the same daylight scenes with plain capture and with the NIGHT extension; the owner compares on the phone. Offer it as the normal photo path only if it is clearly better and not too slow.
- Auto HDR: our own bracket merge (no HDR extension). Ultra HDR JPEG is already the Phase 1 default.
- Hi-Res 50MP: **NO** — no ULTRA_HIGH_RESOLUTION_SENSOR, no max-resolution sizes; apps get 12.6 MP binned. Hi-Res mode and Formats → High resolution are not shown.
- Action mode (fast shutter priority), Clear burst vs Classic burst.
- Long exposure mode (light trails, silky water, star trails: frame stacking).
- Live / Motion photo (Google Motion Photo format: JPEG + embedded MP4 + XMP), photo and in-video capture.
- Portrait mode (`02-portrait-mode.jpg`): lenses 1 / 2 / 3.5, f-number blur slider, distance hints. Rear: BOKEH extension (capture 4096x3072, preview 1280x960; check whether its blur strength can be set, else the f-number slider uses our own blur). Front: own segmentation + depth blur. Video depth of field (`f/2.8` in Video): own.
- Panorama (OpenCV stitching, guided sweep UI).
- Macro shot + auto-macro switch to the ultrawide (physical `3`, focuses to 3.5 cm) when close.
- AI Telescope Zoom: Real-ESRGAN upscale on long-zoom shots (slow; progress bar).

### Phase 8 — retouch / beauty (Opus)

- Retouch panel (`12-retouch-presets.jpg`, `11`, `15`, `16`): presets Original / Natural / Classic; Reset; sliders Skin texture, Skin tone, Cheeks, Head size, Eye size, Face width, Face length, Hairline, Temples, Cheekbones, Nose. Live, via face mesh + GL warp and skin smoothing; same result in the saved photo.
- Portrait distortion correction (setting): straighten faces near the ultrawide edges.
- All our own: the phone exposes no FACE_RETOUCH extension.

### Phase 9 — dual view and quad view (Opus)

- Dual-view video (More): front + rear. Layouts: PiP (move, resize, swap), top-bottom, side-by-side. Record combined (GL composite) or two files (setting). Photo capture of the composite.
- Probe: front `1` + rear `0` are a concurrent set, and front + 3 physical rear streams configured successfully. Dual view is OK.
- Quad view (3 rear + front): session proven. Phase 9 must check it streams smoothly (target 30 fps per tile, reduced stream sizes) and stays cool for a few minutes; if not, offer the largest combination that does (e.g. 2 rear + front).

### Phase 10 — polish

- Animation audit against CLAUDE.md for every screen.
- Preserve settings (all 11 switches) wired end to end; Restore defaults; Privacy page; mode-strip reorder (pencil in More).
- Performance: time-to-first-frame from launch and from lock screen; shutter lag; memory during bursts.

---

## Mode menus (6-dot) — from the stock app

| Mode | Items |
|---|---|
| Photo | Aspect ratio, Soft light, Action mode, Macro shot, Auto HDR, Timer, Interval shooting, Settings |
| Video | 10-bit HDR (stock: Dolby Vision), Ultra Steady, Focus lock, Settings |
| Master | Aspect ratio, Timer, Histogram, Face metering, Interval shooting, Settings |
| Portrait | Aspect ratio, Soft light, Timer, Interval shooting, Settings |
| Text | Aspect ratio, Settings |

Modes on the strip: MASTER · VIDEO · PHOTO · PORTRAIT · TEXT · MORE.
More grid (`06-more-modes-grid.jpg`): Night, Pano, Pro Video, Slo-mo, Time-lapse, Long exposure, Dual-view video, Underwater, Scan Docs, Tilt-shift, Quad view. (Stock Hi-Res: NO, see Phase 7.) Pencil = reorder.

## Settings — from the stock app (plus ours)

**General**
- Watermark › (on/off, add to, Shot on / Location / Date and time / Custom text, Font size, Position)
- Composition assistance › (Grid and guides: None / Grid / Diagonal lines / Golden spiral; Level)
- Shutter sound · Mirror selfie · Add location tags
- Formats › (Photo: Ultra HDR on/off — High resolution is NO, row not shown; High efficiency image (HEIF; motion photos fall back to JPEG); Video: High efficiency video (HEVC))
- Preserve settings › (Shooting mode, Styles & effects, Creative effects, Exposure setting, Auto macro, Motion Photo, AI Telescope Zoom, Portrait mode zooming; Pro: Pro assistance, Metering, Photo formats)
- *Handy features:* Volume button action (Volume / Shutter / Zoom) · Photo orientation (Follow system / Auto-rotate) · Smart suggestions
- Quick launch — **NO** (see Phase 3); row not shown.
- About Camera › (version, build, CHECK FOR UPDATES, Diagnostics) · Privacy › · Restore defaults

**Photo**
- Touch and hold shutter button › (Take burst photos; Burst mode: Clear / Classic)
- Shooting methods › (Tap to capture; Show palm)
- Lens focal length › (More focal lengths: 28 / 35 / 243 mm; Default wide: 1x 24 mm / 1.1x 28 mm / 1.4x 35 mm)
- Text and QR code scanning › (QR code scanning; Recommend Text Scanner)
- Portrait distortion correction

**Video**
- Lock lens · Lock white balance · Adaptive frame rate › (Off / 30 fps / 30 and 60 fps) · Bitrate (Standard / High quality) · Photo capture (Still photo / Motion Photo) · Dual-view recording (Combined / Separate files — ours)

---

## Feature checklist

Tick `[x]` when the owner has confirmed it on the phone. Update the tag after the probe.

| Done | Feature | Tag | Phase |
|---|---|---|---|
| [x] | Self-update from GitHub Releases | OK | 0 |
| [x] | Diagnostics / probe report | OK | 0 |
| [ ] | "Camera Dev" side-by-side build, emulator, scrcpy | OK | 0 |
| [ ] | Crash log + share | OK | 0 |
| [x] | Permission flow + denied screen | OK | 1 |
| [ ] | Camera busy / disconnected / hot / storage full handled | OK | 1 |
| [x] | Camera released on background (bank scanner works after) | OK | 1 |
| [x] | Lens pills 0.6/1/2/3.5/7 + pinch zoom | OK | 1 |
| [x] | Extra focal lengths 28/35/243 mm, default 24/28/35 | OK | 1 |
| [x] | Flash off/auto/on/torch, front screen flash | OK | 1 |
| [x] | Tap focus/exposure, AE/AF lock, EV dial | OK | 1 |
| [x] | Aspect ratio, timer, interval shooting | OK | 1 |
| [x] | HEIF/JPEG, EXIF, location, mirror selfie, shutter sound, orientation | OK | 1 |
| [x] | Volume key action, tap to capture, hold for burst | OK | 1 |
| [x] | Thumbnail opens my-gallerymanager | OK | 1 |
| [ ] | OnePlus AUTOMATIC/HDR processing for normal photos | NO (not exposed; best plain capture + Ultra HDR instead) | 1 |
| [x] | Ultra HDR JPEG (JPEG_R) photos | OK | 1 |
| [ ] | Video 720p / 1080p / 2K / 4K × 30/60 all lenses; 4K120 main only; 1080p120 all rear | OK | 2 |
| [ ] | 8K video | NO (camera outputs max 4096x3072 to apps) | — |
| [ ] | 10-bit HDR video HLG10 / HDR10 (HDR10+ is NO: needs per-frame metadata the GL redraw cannot carry) | OK | 2 |
| [ ] | Ultra Steady / stabilisation (Android PREVIEW_STABILIZATION) | OK | 2 |
| [ ] | Focus lock, lock lens, lock WB, bitrate, adaptive fps, HEVC | OK | 2 |
| [ ] | Photo while recording (still / motion) | OK | 2 |
| [ ] | Slo-mo 1080p/720p 240 fps all rear, 4K120 main; none on front | OK | 2 |
| [ ] | Time-lapse | OK | 2 |
| [ ] | Default camera + power double-press launch | PROBE (owner test on the phone; app already answers the intents) | 3 |
| [ ] | Other apps' "take a photo" (`IMAGE_CAPTURE`) | NO by default (Android 11+: stock camera only; works only from apps with a chooser) | 3 |
| [ ] | **Beta 1 milestone** (Phases 0–3 confirmed) | — | 3 |
| [ ] | Lock-screen secure mode + mini viewer | OK | 3 |
| [ ] | Swipe through roll in my-gallerymanager | OK | 3 |
| [ ] | QS tile, launcher shortcuts | OK | 3 |
| [ ] | Quick launch via volume keys, screen off | NO | — |
| [ ] | Master Auto/Pro manual controls | OK | 4 |
| [ ] | RAW / RAW+JPG (12 MP, all lenses + front) | OK | 4 |
| [ ] | Histogram, focus peaking, zebra, face metering | OK | 4 |
| [ ] | Pro video, audio meter | OK | 4 |
| [ ] | Log video | OnePlus log NO; own flat profile OK | 4 |
| [ ] | Filters (own looks) | OK | 5 |
| [ ] | Master styles page | OK | 5 |
| [ ] | Scenes Stage/Silhouette/Fireworks (via Video label dropdown) | OK | 5 |
| [ ] | Soft light (10-position mist/bloom) | OK | 5 |
| [ ] | Watermark photos + videos | OK | 5 |
| [ ] | Grid/guides, level | OK | 5 |
| [ ] | Tilt-shift, Underwater | OK | 5 |
| [ ] | QR scanning | OK | 6 |
| [ ] | Text mode + editor, recommend text | OK | 6 |
| [ ] | Own document scanner | OK | 6 |
| [ ] | Google Lens button | OK | 6 |
| [ ] | Show-palm timer | OK | 6 |
| [ ] | Smart suggestions | OK | 6 |
| [ ] | Night mode + night badge (NIGHT extension, rear + front) | OK | 7 |
| [ ] | Daytime NIGHT-extension experiment (keep only if it beats plain capture) | OK | 7 |
| [ ] | Auto HDR (own bracket merge) | OK | 7 |
| [ ] | Hi-Res 50MP | NO (no ULTRA_HIGH_RESOLUTION_SENSOR; apps get 12.6 MP binned) | — |
| [ ] | Action mode, Clear/Classic burst | OK | 7 |
| [ ] | Long exposure | OK | 7 |
| [ ] | Live / Motion photo | OK | 7 |
| [ ] | Portrait blur photo (BOKEH extension rear; own for front) | OK | 7 |
| [ ] | Video depth of field (own) | OK | 7 |
| [ ] | Panorama | OK | 7 |
| [ ] | Macro shot + auto macro (ultrawide, 3.5 cm) | OK | 7 |
| [ ] | AI Telescope Zoom | OK | 7 |
| [ ] | Retouch presets + 11 sliders | OK | 8 |
| [ ] | Portrait distortion correction | OK | 8 |
| [ ] | Dual-view video, all layouts | OK | 9 |
| [ ] | Quad view (session proven; smoothness/heat checked in Phase 9) | OK | 9 |
| [ ] | Preserve settings, restore defaults, privacy, mode reorder | OK | 10 |
| [ ] | Dolby Vision recording | NO for now (camera lists a DV profile; Phase 2 checks for a `video/dolby-vision` encoder) | — |

## Open questions

All answered (2026-11-23):

1. **Soft light**: mist/bloom effect, no flash, 10 positions — see Phase 5.
2. **Scenes panel**: opened from the Video mode label dropdown — see Phase 5.
3. **Master style sliders**: confirmed on the phone — see Phase 5.
4. **`docs/stock-reference/`**: kept local only via `.gitignore` (shows OnePlus UI and personal surroundings). Coding sessions read it from disk.

## How a coding session runs

1. Open a new session in `C:\Users\Amir Rashid\my-camera`; say "Do Phase N of PLAN.md" (or a named part of it).
2. The session reads `CLAUDE.md`, this file and the relevant screenshots, presents its plan, and waits for approval (global rule).
3. Build → tests → check on screen: install "Camera Dev" with `adb install` (phone) or run it on the emulator, take screenshots and compare with `docs/stock-reference/` → commit on `main` → push only when the owner says so (a push makes CI publish an APK the phone picks up as an update).
4. Owner checks the phase's "done when" list on the phone; the session ticks the checklist rows and stops.
