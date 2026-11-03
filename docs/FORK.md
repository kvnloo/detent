# This fork (kvnloo/detent)

`main` here is the Detent I run on my phone. It is upstream
[bjarneo/detent](https://github.com/bjarneo/detent) plus:

| feature | where |
|---|---|
| `hyprland` theme: desktop colours sent over adb, shown over a live wallpaper | `Synced.kt`, `ColorsReceiver` (PR #1) |
| live themes: one theme per Wallpaper Engine wallpaper; picking it switches the wallpaper | `Synced.kt`, `MainActivity.openLiveWallpaper` |
| tabbed setup (shape / theme / apps / feel), custom dial, glass presets, hold-to-pick controls, haptics | `ui/Hold.kt`, `Glass.kt`, `Feedback.kt` |
| folders, sideways swipe to launch, dial reordering, auto density | `Groups.kt` (PR #4) |
| themes remembered by name; settings export and import | `Prefs.kt`, `SettingsBackup.kt` (PRs #3, #2) |

The full map for setting up the phone around it (adb, Hermes phone-harness, Wallpaper
Engine, colour sync) is in my dotfiles:
[`kvnloo/.files` docs/phone-setup](https://github.com/kvnloo/.files/blob/master/docs/phone-setup/README.md).

## Build and install your own copy

You need JDK 17 or 21, the Android SDK (Android Studio installs it; set `ANDROID_HOME`),
and adb. Build a **debug** build: the live-theme files are copied in with `run-as`, which
only works on debuggable apps.

```sh
git clone https://github.com/kvnloo/detent.git && cd detent
./gradlew :app:testDebugUnitTest :app:assembleDebug \
  -PdetentId=app.detent.launcher.fork -PdetentCode=$(git rev-list --count HEAD)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Pick your own `detentId` and keep it forever. It is a separate app from stock Detent, so
  both can be installed and you never have to uninstall anything.
- The debug key lives in `~/.android/debug.keystore` on the computer that built it. Build
  updates on the same computer (or copy that keystore), or `adb install -r` fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Don't uninstall to get past that: it wipes your
  dial, folders and themes.
- `detentCode` must grow with each install; the commit count does that.
- Open the app, finish the setup, and tap `set as home app` on the `ready` step.

## Talking to it over adb

Desktop colours into the `hyprland` theme (group `synced`) (hex **without** `#`; the device shell reads `#`
as a comment. `-f 0x20` reaches the app even when it is stopped):

```sh
adb shell am broadcast -f 0x20 -a app.detent.launcher.SET_COLORS \
  -n app.detent.launcher.fork/app.detent.launcher.ColorsReceiver \
  --es bg 1a1122 --es fg e1dae7 --es accent 6168d1
```

Live themes: put `themes.json` and the wallpapers' `.mpkg` files in the app's
`files/live/`, then send `app.detent.launcher.RELOAD_LIVE` to the same receiver.
`themes.json` is a list of `{"name", "bg", "fg", "accent", "file"}` (`file` = an `.mpkg`
in the same folder). `scripts/detent-live-themes` in the dotfiles derives the colours from
each wallpaper's preview and does the copy.

Picking a live theme hands its `.mpkg` to the Wallpaper Engine app
(`io.wallpaperengine.weclient`) through Detent's FileProvider (`<package>.live`); tap the
check mark in Wallpaper Engine once to apply it.

## My settings

Setup > shape: custom dial (`Bezel`, 560, 4 rows), dial on the left. Theme: a live theme.
Feel: glass off, `tap opens` on, voice and web search on, haptics and sound on. Settings
are stored in `shared_prefs/detent.xml` (`adb shell run-as <package> cat shared_prefs/detent.xml`
on a debug build).

## Things that bite

- The lock-screen wallpaper sync calls `setWallpaper`, which would replace a live
  wallpaper. The synced and live themes skip it; keep it that way in new layouts.
- Don't launch the home Detent on a virtual/hidden display to test it: it moves the home
  task off the main screen. Test with a second package id (`-PdetentId=app.detent.launcher.lab`).
- Hidden virtual displays never draw the wallpaper, so they can't prove see-through themes.
- Top-level `val`s in `Model.kt` initialise in file order; a list read by `LAYOUTS` must be
  declared above it (`CustomLayoutTest` catches it).
