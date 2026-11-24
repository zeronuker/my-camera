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
| 11 | Plus Key double-press launch (optional, after Phase 10) | Sonnet 5.5 medium (Opus if the lock-screen launch gets stuck) |

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

Done when: APK installs; the app updates itself from a second CI build; "Camera Dev" installs next to it from the PC and runs on the emulator; scrcpy shows the phone on the PC; Diagnostics shows all sections and saves the JSON; the owner sends the JSON. **Then an Opus session reads it and rewrites every PROBE tag in this file to OK or NO.** (Done 2026-11-23 from `docs/camera-probe.json`; the power double-press turned out NO, see Phase 3.)

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
- 120 and 240 fps cannot go through the GL texture: a constrained high-speed session only delivers every frame to a *recording* surface, a preview surface gets about 30 fps. So while recording at 120/240 the camera is closed and the physical lens camera (ultra wide 3, main 2, tele 4 on the OnePlus 15, picked by zoom) is reopened with a constrained high-speed session that writes straight into the encoder. Confirmed on the phone: on the logical rear camera 0 this session crashed the camera service; the lens camera with a minimal request (frame rate only) works (the screen keeps a slower GL preview) and the file is turned by an orientation hint. Slo-mo files are stretched to play at 30 fps (120 fps = 4x slower, 240 = 8x), without sound.
- **HDR10+ is NO**: HDR10+ needs per-frame dynamic metadata, which the GL redraw cannot carry. 10-bit offers HLG10 and HDR10 (HEVC Main10). The on-screen preview of 10-bit is not tone-mapped.
- Time-lapse speeds 10x / 30x / 60x / 120x / 300x, one kept frame every speed/30 s, played at 30 fps, no sound. SLO-MO is hidden on a camera without high-speed sizes.
- The mode strip is VIDEO · PHOTO · SLO-MO · TIME-LAPSE until the More grid exists (Phase 10 reorders).
- Dolby Vision: the probe only listed AVC/HEVC encoders, so it could not answer; Diagnostics now also lists `dolbyVisionEncoders` (re-run it on the phone). Not offered.

### Phase 3 — system integration and gallery handoff

- Default-camera intents: `STILL_IMAGE_CAMERA`, `STILL_IMAGE_CAMERA_SECURE`, `VIDEO_CAMERA`. **Power double-press: NO on this phone** (owner test 2026-11-23: OxygenOS launches its own camera by name; with the stock camera disabled the press does nothing, and OxygenOS 16 has no Camera row under Default apps). The app still answers the intents for any launcher that sends them.
- `IMAGE_CAPTURE` / `VIDEO_CAPTURE` (return result to the calling app): declared, but since Android 11 only apps that show their own camera chooser or name this app can reach it; the system sends everyone else to the stock camera. Not a promise to make "the camera for all apps".
- Lock-screen (secure) mode: `showWhenLocked`, no settings access, thumbnail opens our own mini viewer limited to this session's shots.
- Unlocked thumbnail: open in my-gallerymanager **with swiping through `DCIM/Camera`**. This needs a small change in the my-gallerymanager repo (accept `com.android.camera.action.REVIEW` and open the viewer on the camera album at that item) — separate session in that repo, with the owner's OK.
- Quick Settings tile, launcher long-press shortcuts (selfie, video).
- **Quick launch by double-pressing a volume key with the screen off: NO** (Android does not deliver key presses to apps while the screen is off). Substitutes: QS tile (needs the phone unlocked: Android asks for the unlock first), launcher shortcuts, and the optional Plus Key launch (Phase 11).

**Built in Phase 3 (camera side):** `LaunchRequest` maps the intents to a start mode (STILL, VIDEO, SECURE_STILL, IMAGE_CAPTURE, VIDEO_CAPTURE) and the launcher shortcuts (Selfie, Video); `MainActivity` is `singleTop` and takes `onNewIntent`. A lock-screen launch shows over the lock, hides Settings and the update dialog, forgets every earlier picture (also when it reaches a camera that was already open), and its thumbnail opens `SessionViewer` (only this launch's shots). IMAGE_CAPTURE / VIDEO_CAPTURE take one shot, copy it to `EXTRA_OUTPUT` (or return its Uri and a small picture) and close. The Quick Settings tile opens the camera (the lock-screen camera when locked). The thumbnail sends `com.android.camera.action.REVIEW` to the gallery manager first and falls back to a plain view; **the gallery manager side (accept REVIEW, open its viewer on the camera album at that item) is not done: it is a separate change in my-gallerymanager and needs the owner's OK.**

Done when: the QS tile and the launcher shortcuts open this app, and a lock-screen launch (any launcher that sends the secure intent) opens it; locked users cannot see older photos; unlocked thumbnail swipes through the roll.

### Phase 4 — Master (Pro) and Pro video

- Master mode (`04-master-mode.jpg`): Auto / Pro switch; JPG / RAW / RAW+JPG pill; lens pills; styles button (stacked cards).
- Pro controls: ISO, shutter speed, white balance (presets + Kelvin), manual focus, EV — each with an AUTO state. Only what MANUAL_SENSOR allows.
- Master 6-dot menu (`41-master-6dot-menu.jpg`): Aspect ratio, Timer, Histogram, Face metering, Interval shooting, Settings.
- Assistants: live histogram, focus peaking, zebra stripes, level.
- RAW DNG via `DngCreator`: every lens including the front, 12 MP (4096x3072; front 3280x2464).
- Pro video (More → PRO VIDEO): the same manual controls while recording, shutter shown as shutter angle option, audio level meter, our own log-style flat profile (no OnePlus log exposed; tonemap CONTRAST_CURVE or a GL curve, best on 10-bit HLG), LUT preview.

**Built in Phase 4 (facts learned, details in the hand-over note):**
- Modes in the strip: MASTER · VIDEO · PHOTO · SLO-MO · TIME-LAPSE · PRO VIDEO (the strip scrolls to keep the chosen name centred; Phase 10 reorders with the More grid). Master and Pro video are hidden on a lock-screen launch (DNG files and manual controls are not for it).
- Pro exposure is one group: the first ISO or SHUTTER touch puts both on manual (starting at what the camera was using), AUTO on either gives both back. While manual, EV is off. Live picture exposure is capped at 1/4 s (a longer one would freeze the viewfinder); the photo gets the real time. In Pro video the shutter is capped at one frame time.
- RAW: a second ImageReader (RAW_SENSOR, largest size) in the photo session; DngCreator writes the file next to the JPEG (RAW+JPG) or alone. If the camera refuses the session with RAW, Master falls back to JPG with a toast. RAW is uncropped (no zoom crop).
- Kelvin white balance uses the camera's own colour calibration matrices, not a measurement of this sensor: needs checking on the phone. Presets use the camera's AWB modes.
- Peaking, zebra, histogram and the level line are drawn on the screen only; nothing of them reaches a photo or video. Pro video "LOG" is a GL curve (flat profile) applied in the encoder pass; "LUT" shows the normal look on screen while the file is flat. Pro video has no 120/240 fps (manual exposure cannot ride the high-speed path).

### Phase 5 — looks and overlays

- Filters panel (`14-filters-a.jpg`…`19`): Original + ~14 of our own looks (film, CCD-flash, B&W, food, vivid…), live preview, info button. 3D LUTs in the GL pipeline, applied identically to the saved photo.
- Master styles page (`21-master-styles-a.jpg`): swipeable presets starting with Standard; reset button; seven controls, left to right (confirmed on the phone): Soft light (plain circle, same 10-position scale as below, default None), Tonality (half circle), Saturation (bars), Cold/Warm (thermometer), Cyan/Magenta (ring of dots), Sharpness (triangle) — these five default 0 — and Vignette (square with circle, On/Off, default On).
- Scenes: None / Stage / Silhouette / Fireworks (exposure + tone presets). Opened from the Video mode label in the mode strip, which is both a swipe target and a dropdown (`13-scenes-panel.jpg`).
- Soft light (Photo and Portrait 6-dot menus, and Master styles): software mist/bloom look; the flash never fires. 10 positions: None, 2 notches, Weak, 2 notches, Medium, 2 notches, Strong. Effect at Strong (`46-soft-light-off.webp` vs `47-soft-light-strong.webp`): bright areas bleed a wide white halo over nearby edges, blacks lift slightly, contrast drops, highlights clip; shadow detail mostly unchanged. Build in the GL pipeline: threshold highlights → wide blur → screen blend + small black lift, all scaled by the position, so preview and saved photo match.
- Watermark (`26-settings-watermark.jpg`): on/off; add to Photos / Videos / Photos & videos; "Shot on <phone model>", location, date and time, custom text; font size; position. Burned into video through the GL pipeline.
- Composition: grid None / Grid / Diagonal lines / Golden spiral; level.
- Tilt-shift mode (blur band, movable/rotatable), Underwater mode (colour correction + volume keys as shutter).

**Built in Phase 5 (facts learned, details in the hand-over note):**
- One effect chain (`gl/Effects.kt`: filter LUT, Soft light, style controls, vignette, Underwater, Tilt-shift) is used by the live picture, the video encoder pass and the offscreen pass for saved photos (`gl/PhotoLook.kt`), so all three match. A photo with a look or watermark is decoded, turned upright, run through the shader, and saved as a **plain JPEG** (Ultra HDR gain map and HEIF cannot survive; RAW/DNG is never touched). A photo with no look and no watermark is saved exactly as before.
- 14 own looks (+ Original), baked into a 16x16x16 LUT from a small grade each. Filters panel (Photo, Video, Time-lapse, Tilt-shift, Underwater) from the button right of the lens pills; Master has the styles button there instead (6 preset pages, 7 controls, reset). Scenes (Stage, Silhouette, Fireworks) open by tapping the VIDEO label again; each is an exposure shift plus a tone preset.
- **120/240 fps video and slo-mo get none of this** (the camera writes straight into the encoder): the filter button is hidden there and no look is applied. 30/60 fps video and time-lapse get filters, Soft light, scenes and the watermark.
- Master styles: Vignette is a gentle corner darkening and defaults **Off** (the stock default On is the phone's own lens look, which an app cannot switch), so a plain Master photo stays untouched. Soft light is shared between the Photo menu (None / Weak / Medium / Strong) and the Master styles dial (all 10 positions).
- Watermark: Settings page as the stock one; "Shot on OnePlus 15" (model CPH2747 mapped to its name), location as coordinates (last known place), date and time, custom text, size, position. Photos burn it on the CPU after the shader; video draws it as a texture in the encoder pass.
- Composition: grid / diagonal lines / golden spiral and the level line, drawn on the screen only. Tilt-shift mode: drag moves the band, pinch resizes, two-finger twist turns it. Underwater mode: colour correction, volume keys are the shutter whatever the volume setting says. Modes in the strip now end with TILT-SHIFT and UNDERWATER (Phase 10 reorders).

### Phase 6 — text, QR, Lens, doc scanner, gestures

- QR scanning in Photo mode (setting), result chip → open / copy / share.
- "Recommend Text Scanner": when text fills the frame in Photo mode, suggest Text mode.
- Text mode (`07-text-mode.jpg`): live page outline (`42-text-6dot-menu-b.jpg` shows the yellow outline — ours is mint), capture → recognised text in an editor: select, edit, copy, share. 6-dot: Aspect ratio, Settings.
- Scan Docs (More): our own scanner — live edge detection, auto/manual corners, perspective flatten, enhance (colour / greyscale / B&W), multi-page, save as JPEG or PDF.
- Google Lens button: current frame → Google Lens via the Google app; hidden if the Google app is missing.
- Shooting methods: tap to capture, show palm → 3 s timer.
- Smart suggestions: "Move further away" in Portrait, "Try Night" in low light, lens-smudge hint is **not** promised.

**Built in Phase 6 (facts learned, details in the hand-over note):**
- Libraries added (owner OK'd): ML Kit Barcode Scanning and Text Recognition Latin + Chinese (bundled, have x86_64), OpenCV 4.10 (`org.opencv:opencv`, all ABIs in debug, arm64 in release), MediaPipe tasks-vision 0.10.14 plus the bundled `hand_landmarker.task` model. **MediaPipe ships no x86_64 library, so Show palm is hidden on the emulator** (`HandScanner.supported`). The release APK grew from about 2 MB to about 65 MB (arm64).
- The GL pipeline hands small (640 px wide) look-free, unmirrored copies of the live picture to one scanner thread (`PreviewRenderer.analysis`, `scan/FrameScanner.kt`); frames that arrive while it is busy are dropped. QR (Photo, ~450 ms), page text (Text mode ~500 ms; Photo looks every third pass), page outline (Scan Docs) and palm (Photo) share it.
- Text mode and Scan Docs keep no photo of the scene: the shutter reads it (OCR with Latin, plus Chinese when it finds Chinese) or opens the page editor. They take one picture at once (no timer, interval or burst). Modes in the strip now: MASTER · VIDEO · PHOTO · TEXT · SCAN DOCS · SLO-MO · TIME-LAPSE · PRO VIDEO · TILT-SHIFT · UNDERWATER.
- Scan Docs: OpenCV finds the page (Canny and Otsu, biggest convex four-sided shape, 15 % of the frame at least; tested on a generated page: corners within 1 %), the editor has four draggable corners and COLOUR / GREY / B&W previews (shadow-flattening divide, adaptive threshold for B&W), pages are kept as JPEGs, saved as one PDF in `Documents/Scans` (about 190 dpi) or as photos in `DCIM/Camera`. Pages live in memory until saved or discarded.
- QR chip: OPEN only for http, https, tel, mailto, sms, geo; Wi-Fi codes show only the network name; nothing opens without a tap; no OPEN on a lock-screen launch.
- Google Lens: the frame is shared to `com.google.android.googlequicksearchbox/com.google.android.apps.search.lens.LensShareEntryPointActivity` (not verified on the phone); the button is hidden when that activity does not exist.
- Show palm: all four fingers stretched and the thumb out for three looks in a row starts a 3 s timer, then one photo. Default on, as stock.
- Smart suggestions: the switch exists and gates "Try Text mode". "Try Night" and "Move further away" come with Night and Portrait in Phase 7 (they need a mode to point to).

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

**Built in Phase 7 so far (parts 1, 2 and 4; details in the hand-over note):**
- **Night** (strip, after PHOTO): the NIGHT Camera Extension as an extension session (`CameraEngine.configureExtension`); only the zoom, EV, orientation and quality keys the extension lists are sent; a refused session falls back to the normal camera with a message and hides Night until restart. Hidden where the camera has no NIGHT extension (the emulator). **Not testable on the emulator: needs the phone.** Settings > Photo has a test switch "Night processing for all photos" (the daytime experiment). "Try Night mode" chip in low light (ISO 1600 or 1/10 s).
- **Action mode** (Photo menu, only where the camera lists the ACTION scene mode), **Clear / Classic burst** (Clear waits up to 0.5 s for focus before each frame), **Macro shot + Auto macro** (ultra wide lens through zoom 0.6x; auto switches when the subject is closer than 9 diopters and out of focus for a moment; Settings > Photo).
- **Auto HDR** (Photo menu, rear camera): three exposures (0, -2, +2 EV) fused with OpenCV Mertens after AlignMTB. **Long exposure** (mode LONG EXP): 2 / 5 / 10 / 30 s of frames, averaged (smooth) or brightest-pixel (light trails); the phone must be still. **Panorama** (mode PANO): the gyroscope triggers a picture every 18 degrees, tap again to stitch. OpenCV's Android package has no Stitcher, so the joiner is ours (cylindrical warp, ORB matching, feathered blend; checked on frames from a true rotating-camera model).
- **Live photo** (Photo menu, rear camera): a small H.264 encoder fed by the GL pipeline keeps about 1.7 s in memory; on the shutter it adds 1.5 s, muxes an MP4 (no sound) and appends it to the JPEG with Google's Motion Photo XMP. Forces plain JPEG (no Ultra HDR or HEIF). Looks and the watermark are applied before the clip is appended.
- **Portrait** (strip, after PHOTO): rear uses the BOKEH extension where offered (no blur control: the extension has none, so there is no f-number slider there); otherwise (front camera, or BOKEH missing or refused) our own cut-out (ML Kit bundled selfie segmentation) and an even background blur (no depth map), with an f/1.4 to f/4.0 pill. "Move further away" shows when a person fills more than 55 % of the frame. Soft light works in Portrait. The blur is applied to the saved photo only (no live preview of it).
- **AI Telescope Zoom** (Settings > Photo, default on): beyond about 10x the normal photo is saved first, then a second picture redrawn by Real-ESRGAN x4v3 (LiteRT 1.4.0, model copied from the gallery manager, 1024 px in, 4096 px out, tiles, progress bar). Takes about a minute on the phone (6 tiles took 33 s on the x86 emulator); the app must stay open.
- **Video depth of field** (Settings > Video > "Depth of field (test)", off by default): the scanner thread segments the whole upright frame about 8 times a second (ML Kit selfie segmenter, 256 px), the mask goes to the GL pipeline as a texture on its own texture unit (2), and the shared shader blurs the background (about f/2.8) in the preview and in the encoder pass. Rear camera, normal video (not 120/240 fps). Quick movements lag a little. Checked on the emulator only (no person: the whole picture blurred, no black frame, video recorded); the real result needs the phone.
- **Motion Photo while recording** (Settings > Video > Motion Photo, off by default): the renderer has a second encoder slot (`startClip`) so the Live clip encoder runs next to the recorder; the snapshot button then saves a Motion Photo. Rear, portrait, not 10-bit.
- **Night long option**: in NIGHT, the top pill switches PHONE NIGHT (extension) and LONG NIGHT (about 8 frames at +1.5 EV, aligned by phase correlation, averaged, shadows lifted, tap to finish early). NIGHT also shows where the phone has no Night extension (then it is always the long night).

### Phase 8 — retouch / beauty (Opus)

- Retouch panel (`12-retouch-presets.jpg`, `11`, `15`, `16`): presets Original / Natural / Classic; Reset; sliders Skin texture, Skin tone, Cheeks, Head size, Eye size, Face width, Face length, Hairline, Temples, Cheekbones, Nose. Live, via face mesh + GL warp and skin smoothing; same result in the saved photo.
- Portrait distortion correction (setting): straighten faces near the ultrawide edges.
- All our own: the phone exposes no FACE_RETOUCH extension.

**Built in Phase 8 (facts learned, details in the hand-over note):**
- Library added (owner OK'd): ML Kit Face Mesh Detection 16.0.0-beta3 (bundled; native libs include x86_64). Faces come from the scanner thread (`face/FaceFinder.kt`, `FrameScanner.faces`, `PreviewRenderer.faceSink` about 10 times a second, the whole upright frame at 384 px); `FaceTrack` smooths the points between looks. Up to 2 faces (the largest).
- The warp and the smoothing live in the shared effect code (`gl/Effects.kt` `WARP`, `retouchSkin`), so live picture, video and saved photo match. The warp works in camera-frame coordinates (never mirrored), so mirror selfie, crops and turned video need nothing special. Photos: `PhotoLook` finds the faces again on the full photo (at most 1280 px long) and saves a plain JPEG; no face found means the photo is saved without retouch.
- Panel (button left of the lens pills, Photo / Portrait / normal video, rear and front): Original, Natural, Classic, Reset and the 11 sliders, each 0 to 100, one direction (smoother, brighter, bigger eyes, smaller head / jaw / cheeks / chin / hairline / nose, fuller temples). Choice is remembered. Hidden in Master, slo-mo and 120/240 fps.
- Portrait distortion correction (Settings > Photo, only where a rear camera zooms below 1x, default on): in Photo and Portrait on the rear camera below 1x, faces away from the middle are squeezed along the line from the middle by cos(theta); theta comes from a rough half-diagonal angle that falls from 55 degrees at 0.67x to 0 at 1x (`RetouchOps.edgeAngle`). Approximate: check on the phone and tune.
- Emulator test aid (debug builds only): a file `files/debug_face.txt` in the app with "x y h" (face centre across and up the frame, height, as fractions) makes every look return one made-up face, so the warp, smoothing, panel and saved photo can be proven without a real face.

### Phase 9 — dual view and quad view (Opus)

- Dual-view video (More): front + rear. Layouts: PiP (move, resize, swap), top-bottom, side-by-side. Record combined (GL composite) or two files (setting). Photo capture of the composite.
- Probe: front `1` + rear `0` are a concurrent set, and front + 3 physical rear streams configured successfully. Dual view is OK.
- Quad view (3 rear + front): session proven. Phase 9 must check it streams smoothly (target 30 fps per tile, reduced stream sizes) and stays cool for a few minutes; if not, offer the largest combination that does (e.g. 2 rear + front).

**Built in Phase 9 (facts learned, details in the hand-over note):**
- `camera/MultiCameraEngine.kt` opens the logical rear camera and the front camera together (only where `CameraManager.concurrentCameraIds` lists them as a pair; debuggable builds try anyway so the emulator shows the layouts). Dual uses the logical rear camera at 1x; Quad asks the logical camera for its physical lenses (ultra wide, main, tele, sorted by focal length) as separate streams, each at most 1 MP, the front at its own size. Any refusal or lost camera ends in a message and the normal camera, never a crash.
- The GL renderer got extra camera sources (`PreviewRenderer.makeSources`, `Src`): every camera is drawn as a tile with the same shader (so crops, looks and mirroring work), for the screen, the encoder pass and snapshots (`drawComposite`). Layout maths and the Quad step-down rules are pure (`dual/DualRules.kt`, tested).
- DUAL and QUAD are modes at the end of the strip (Phase 10 moves them into More). Dual: layouts Picture-in-picture (drag to move, pinch to resize, tap to swap), Top and bottom, Side by side, a swap button; tiles glide between layouts. The record button records; the camera button (right) and the left button while recording take a photo of the composite (1080 x 1920 JPEG in `DCIM/Camera`). Flash, EV, lens pills, filters and the menu are hidden in these modes.
- Recording: Settings > Video > Dual-view recording (shown where the phone lists the pair): Combined (one 9:16 file, 1080 x 1920, or 720 x 1280 if the encoder cannot do 1080) or Separate files (`VID_..._REAR.mp4` and `VID_..._FRONT.mp4`, 3:4, each with sound, from the same frames). Files stay portrait whatever the phone orientation. No 10-bit, no slo-mo.
- Quad view: 2x2 tiles (ultra wide, main, tele, front). It counts the frames each camera delivers and watches `Heat`; under 24 fps after 3 s, or a warm phone, it steps down to 2 rear + front (3 tiles), then switches Quad off for the run. It never reopens cameras while recording. Hidden where the phone has fewer than 2 physical rear lenses. **Smoothness and heat are only known on the phone.**
- Bug found and fixed on the way: while an encoder runs the screen stops taking camera frames; a frame that was already waiting when the encoder was set stalled the camera. `startEncoding` / `startClip` now take it at once.
- The emulator (software GL) is too slow for 1080 x 1920 composite recording; debuggable builds only try 720 wide.
- **Dual Photo** (added after the first phone test): modes are now DUAL PHOTO and DUAL VIDEO (and QUAD). One press in DUAL PHOTO saves three files in `DCIM/Camera`: the composite (`IMG_..jpg`, 1080 x 1920) and one photo per camera (`IMG_.._REAR.jpg`, `IMG_.._FRONT.jpg`). The session asks each camera for a full-size JPEG stream next to the preview (`MultiCameraEngine.open(stills = true)`, `captureStills`); if the phone refuses that, the session reopens without it, a toast says so, and the two photos come from the screen picture of each camera (1080 x 1440). On the emulator the full-size path was accepted (1856 x 1392 rear, 1920 x 1440 front); whether the OnePlus 15 accepts 12 MP on both cameras at once is only known on the phone.
- **Quad fix** (phone test said "camera error 4"): streams are now the smallest size (about 640 x 480, as the probe configured them), no zoom ratio on separate lenses, the exact error goes to the log (tag `MultiCamera`), and a camera lost while streaming also steps down (3 to 2 rear + front) instead of leaving the mode.

### Phase 10 — polish

- Animation audit against CLAUDE.md for every screen.
- Preserve settings (all 11 switches) wired end to end; Restore defaults; Privacy page; mode-strip reorder (pencil in More).
- Performance: time-to-first-frame from launch and from lock screen; shutter lag; memory during bursts.

### Phase 11 — Plus Key double-press launch (optional, planned 2026-11-23, build after Phase 10)

Goal: double-press the OnePlus 15 Plus Key (the action button) to open this camera, also from the lock screen, with no second app. The power key and the volume keys are not possible (OxygenOS owns the power double-press; Android never gives key presses to apps with the screen off). Accessibility services are avoided on purpose: many banking apps refuse to run while an unknown one is on.

Idea: the OnePlus system writes a line (`KEYLOG_OplusKeyEventUtil`) to the phone's internal log when the Plus Key is pressed (source: a blog post, not verified; the Tasker + Shizuku trick relies on it). With the `READ_LOGS` permission, granted once from a PC (`adb shell pm grant <package> android.permission.READ_LOGS`), this app can read that log itself.

1. **Test (decision point).** Diagnostics gets a "Key log" screen that shows the live log lines. The owner presses the Plus Key once and twice, with the screen on, off and locked (and the power and volume keys for comparison). Continue only if there is a clear line for the Plus Key every time; otherwise stop and change nothing else.
2. **Listener.** A foreground service (permanent notification) reads the log, counts two presses within about 500 ms and starts the camera: the secure camera intent over the lock screen, the normal one when unlocked. Needs "Display over other apps" (Android 14+ blocks starting an app from the background otherwise) and a battery-optimisation exemption. New General setting "Plus Key launch", off by default. Must survive a reboot (start at boot), ignore single presses, stop cleanly when switched off, and not drain the battery (check after an hour idle).
3. **Setup screen.** Plain-words page in the app: the one PC command, the overlay switch, the battery exemption, and a check button that says which part is still missing.

Risks: the log line may not exist or may not appear with the screen off; OxygenOS may kill the service; the permission is lost if the app is reinstalled; Android may refuse a background start over the lock screen (fallback: open the camera right after unlock). Done when: a double-press of the Plus Key opens the camera from the lock screen and from the home screen on the phone, a single press does nothing, and it still works after a restart.

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
- Quick launch — **NO** (see Phase 3); row not shown. Plus Key launch (Phase 11): a switch here, off by default, only once Phase 11 is built.
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
| [ ] | Default camera + power double-press launch | NO (OxygenOS opens its own camera by name; no Camera row in Default apps; owner tested 2026-11-23). Substitute: Phase 11 | 3 |
| [ ] | Other apps' "take a photo" (`IMAGE_CAPTURE`) | NO by default (Android 11+: stock camera only; WhatsApp and the banking apps tested 2026-11-23 go straight to it, no picker). The code answers the request if an app does send it | 3 |
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
| [ ] | Plus Key (action button) double-press opens the camera, no second app | PROBE (step 1 of Phase 11 finds out whether the phone logs the key) | 11 |
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
