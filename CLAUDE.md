# Camera (my-camera)

Android camera app for the owner's OnePlus 15, replacing the stock OxygenOS camera. Kotlin + Jetpack Compose +
Camera2. **`PLAN.md` is the source of truth** for scope, phase order, decisions and the feature checklist; read it
before any work and do only the phase you were asked to do. Stock app screenshots: `docs/stock-reference/`.

# Design language (OxygenOS layout, ClaudeBorne brand)

Layout and behaviour follow the stock camera screenshots; colours, fonts and frames follow the ClaudeBorne brand kit
(`C:\Users\Amir Rashid\my-brandkit`), exactly as `C:\Users\Amir Rashid\my-gallerymanager` does. When unsure how
something should look, open the matching file in my-gallerymanager and copy its approach.

- **Tokens** come from `ui/Theme.kt` (`LocalCb`): surfaces, mint/blue/violet, ink, lines. No new hex colours; the only extra is `Warn` (amber) for warning lines. Where the stock app uses amber/yellow for "selected" or "on" (mode pill text, toggles, filter frame, document outline), we use mint.
- **Viewfinder chrome** (top pills, lens pills, 6-dot menu, More grid, filter/retouch/scene panels): floating glass pills and panels from `ui/Glass.kt` / `ui/Pill.kt`, always on the dark palette regardless of phone theme.
- **Settings screens**: the stock structure (back button, segmented General / Photo / Video tabs, grouped rows, sub-pages, pickers) drawn with ClaudeBorne frames (`doubleChamferFrame(cb)`), and following the phone's light/dark theme.
- **Buttons**: square, mono, uppercase, a faint line border that turns mint when pressed; one gradient primary at most per view. Use `BrandButton` (or `WideBrandButton`). The shutter is the exception: round, as in the stock app.
- **Dialogs**: `CbDialog(title, onDismiss, subtitle = …)`. Result dialogs use a mint line for success and an amber (`Warn`) line for a problem.
- **Live states** (waiting for the camera, processing) use `WaitingLine` with the pulsing mint `LiveDot`.
- **Text**: labels and mode names uppercase mono; body text sentence case; short.

# Animation rule (applies to every new or changed feature)

Nothing in the app may snap instantly unless it logically has to. Use the shared helpers in `ui/Motion.kt` (do not
hand-roll timings per screen).

- **Appearing / disappearing**: screens, panels, menus, pills, toasts, badges, dialogs, settings sub-pages.
- **State changes**: mode-strip pill sliding between modes, lens pill selection, flash/HDR/live icon swaps, record button shape change, timer countdown, theme changes.
- **Camera transitions**: switching lens, mode or front/rear camera crossfades or blurs the last frame; never a black flash.
- **Progress**: every operation that takes time shows a moving indicator. Known percentage: a smoothed progress bar (night merge, panorama, AI zoom, saving). Unknown: the indeterminate shimmer bar. Never a static "PROCESSING…" label alone.
- **Allowed to be instant**: anything that tracks the finger live (pinch zoom, EV dial drag, manual focus, slider drags, PiP drag) and the shutter's own capture feedback.

Before finishing any UI change, check each new composable against this list and verify on the phone.

# Camera rules

- **Never fake a feature.** Every control that depends on hardware checks the capability first (see the Diagnostics/probe data). If the phone does not support it, hide the control. Never fast-switch cameras to imitate simultaneous streams.
- Camera work never runs on the main thread. Open the camera in `onResume`, release it in `onPause`; a camera left open blocks every other camera app, including the owner's banking apps' QR and face-check scanners.
- Controls for features from a later phase stay hidden until that phase builds them.
- Camera busy, disconnected, overheating, storage full and denied permissions each get a clear message and a way out; never a crash, a frozen or a black viewfinder.
- Every native library must ship x86_64 too (the emulator build), or its control is hidden on the emulator.
- All preview and recording frames go through the single GL pipeline in `gl/`; do not add a second preview path.
- Photos and videos are saved through MediaStore to `DCIM/Camera/` with `IMG_…` / `VID_…` names (PLAN.md, Tech stack).
- Lock-screen (secure) launches must never show photos taken before that launch.
- New libraries are added only in the phase PLAN.md lists them for, and only after the owner approves.

# Workflow

Solo developer, no CI review gate: work directly on `main`, no feature branches. For each phase:
understand the screenshots and PLAN.md section → present a short plan and wait for approval → build → add a JUnit test
for any new pure logic (parsers, maths, file naming, settings migration, Motion Photo writer) → run tests → install the
"Camera Dev" debug build (`adb install` to the phone, or the emulator), take screenshots (`adb exec-out screencap -p`)
and compare with `docs/stock-reference/` → commit on `main` → push only when the owner says so (a push publishes a
release APK the phone will offer as an update) → the owner checks the phase on the phone → tick PLAN.md's checklist.

Never see, ask for in chat, or type keystore passwords or other secrets: give the owner the exact command to run
(e.g. `gh secret set KEYSTORE_PASSWORD`) and let them enter the value. Downloads and new tools (e.g. scrcpy) need the
owner's OK first.
