<p align="center">
  <a href="docs/media/detent-promo.mp4"><img src="docs/media/home.jpg" alt="Detent on a phone with the retro-82 theme. The apps sit on a dial at the right edge of the screen." width="100%"></a>
</p>

# Detent

Detent is a home screen for Android that you turn. Your apps sit on a dial at the edge of the screen. Turn the dial to find an app. Tap the app to open it.

[Try the dial in your browser](https://bjarneo.github.io/detent/) · [Watch the 48-second video](docs/media/detent-promo.mp4) · [Download the latest APK](../../releases/latest/download/detent.apk)

## Install

You need Android 10 or later.

1. Download `detent.apk` from the [latest release](../../releases/latest).
2. Open the file on your phone.
3. If Android asks, allow your browser or file manager to install apps.
4. Open Detent and follow the setup.
5. On the `ready` step, tap `set as home app`.

To install from a computer, connect the phone with USB debugging on and run this command:

```sh
adb install detent.apk
```

## Use the dial

| Action | Result |
| --- | --- |
| Drag or flick the dial | The dial turns. Each app clicks into the notch. |
| Tap any app on the dial | The app opens. Its name lifts off the dial, and the dial turns the app into the notch. |
| Tap the readout | On `grid · readout`, the app in the notch opens. |
| Tap the dial while it turns | The dial stops. No app opens. |
| Hold the dial | Letters replace the apps. Slide to a letter and release to jump to it. |
| Hold the app in the notch | A menu shows the app shortcuts, `app info`, and `remove from dial`. |
| Tap the clock | Opens the phone's Clock app (alarms). |
| Hold the clock or an empty area | The setup opens: shape, theme, apps, and feel. |
| Drag up from the bottom of the screen | Search all your apps. The drag starts on the row of the quick buttons. The `apps` hint marks the place. The 5 apps that you last opened from search sit at the top. Tap the cross on a recent app to remove it, or tap `clear`. Tap `+ dial` to add an app to the dial. |
| Tap a quick button | Its app opens. If the corner has no quick app yet, pick one. |
| Hold a quick button | A menu shows the app shortcuts, `change icon`, `change app`, `add a quick app`, and `remove quick app`. |
| Tap the mic button and say an app name | The dial swells with your voice, and your words show at the top. When the words name one app, the dial turns to it and the app opens. If not, search opens with the words. |
| Tap the web button and type | Press `Enter` or tap `search`. Your default browser searches the web with its own search engine. If the browser takes no web search, Android gives the words to a search app, such as Google, or asks you to pick one. Detent only hands the words over; it does not run web searches itself. |
| Drag down from the top of the screen | The notifications open. The drag starts above the clock. |

The quick buttons sit in the bottom corner on the dial side. They hold up to 3 apps that you need fast, such as the camera. Each button shows an icon that you pick, and Detent marks the icon that fits the app. With 1 quick app, the button can show the app name. With 2 or 3, a button without an icon shows the first letter of the name. The web button sits next to the quick buttons, and the mic button sits at the screen edge. To hide the mic button or the web button, turn off `voice` or `web search` on the `feel` step.

Detent suggests changes to the dial. If you open an app from search 3 times in a week, a card asks you to add it to the dial. If you do not open a dial app from the home screen for 3 weeks, a card asks you to remove it. Tap `not now` or `keep` to hide the card for that app. Detent shows 1 card a day at most. It counts the opens on the phone only.

With a hardware keyboard, `j` and `k` turn the dial. `l` or `Enter` opens the app. `/` opens search. `Esc` goes back.

<p>
  <img src="docs/media/letter.jpg" alt="Hold the dial to jump by letter." width="49%">
  <img src="docs/media/menu.jpg" alt="Hold an app to see its shortcuts." width="49%">
</p>
<p>
  <img src="docs/media/themes.jpg" alt="Turn the dial to try a theme." width="49%">
  <img src="docs/media/search.jpg" alt="Search all apps and add one to the dial." width="49%">
</p>

## Home widgets

Hold the clock or an empty area, choose `feel`, then tap `add widget`. Pick a widget in Android's picker, approve binding if prompted, and complete its setup if required. It appears below the clock, opposite the dial. To remove one, hold the `widgets` chip in `feel` and select the widget to remove. Widgets stay in place after restarting Detent; a widget's app must remain installed.

## Shapes and themes

Detent has 8 layouts. Layouts 1 to 5 put a radial dial over the wallpaper. Each name follows the curve of the dial.

| Layout | Look |
| --- | --- |
| `soft · radial · double` | The default. Dots swell toward the notch, with a fainter inner arc. |
| `round · radial · double` | The same dots on a tighter curve. |
| `soft · radial · ticks` | Hairline ticks on a thin rim. |
| `flat · radial · dots` | A dot matrix on a wide curve. |
| `flat · radial · nodes` | Small nodes on a wide curve. |

Layouts 6 to 8 draw the whole screen as one grid of dots. The clock is a dot-matrix readout. The grid layouts do not show a wallpaper.

| Layout | Look |
| --- | --- |
| `grid · field` | The grid draws the dial. A swell runs through the dots as you turn. The names sit on spokes. |
| `grid · lens` | Each app is one dot in a column at the edge. Scroll the column like the dial. It loops without end and flows through a lens at the notch. The lens spreads out the names near the notch. |
| `grid · readout` | The dial has no names. Turn the dial and the readout shows the app name. Release and the readout shows the time again. |

On the grid layouts, an app opens with a ripple of dots from the notch.

The dial can sit on the right or the left edge.

Detent has 28 built-in themes. The 4 phone themes are dusk, ember, signal, and paper. They use tinted dot-hand art. The 23 Omarchy themes each bring their own wallpaper. The `hyprland` synced theme uses desktop colours and keeps the system wallpaper. On the theme step, wallpapers reveal in the swipe direction while browsing; distant taps crossfade directly to their destination. Detent remembers built-in themes by name and imported themes by ID, so adding themes does not change your choice.

### Import a collection

In setup → theme, tap `import +`, paste a public collection link, then tap `find themes`. Review and select up to 20 entries, import, and tap `view imported` to browse them. You can also share a link to Detent from your browser. Imports are saved offline and re-importing does not duplicate them. Your current wallpaper is not replaced until you finish choosing a theme.

GitHub repositories/folders, image galleries, direct images and JSON collections are supported. For example, `https://github.com/kvnloo/.files` discovers the background images and the Wallpaper Engine favorites in `config/wallpapers/live.json`. Workshop links are saved as links: Steam content and phone packages stored only on the author's PC cannot be downloaded from GitHub. See [theme collections](docs/THEME-COLLECTIONS.md) for the manifest format and limits.

### Synced theme (`hyprland`)

Choose `hyprland` on the theme step to match Detent's background (`bg`), text (`fg`), and accent (`accent`) colours to a desktop theme, such as Hyprland with pywal. The colours are saved on the phone and update when you send a new palette. The background colour also controls whether the theme uses light or dark styling.

The synced theme never sets or replaces the system wallpaper. It shows the current Android wallpaper, including live wallpapers, behind the radial dial. Grid layouts draw a shade over that wallpaper behind their dots.

With USB debugging enabled and the phone connected, send six-digit RGB hex values **without `#`**:

```sh
adb shell am broadcast -a app.detent.launcher.SET_COLORS \
  -n app.detent.launcher/app.detent.launcher.ColorsReceiver \
  --es bg 181f23 --es fg d1deda --es accent c8be72
```

If you installed Detent with a custom application ID, replace the package name before `/` with that ID.

### Use your own wallpaper

Your photo replaces the theme wallpaper. The theme still sets the colours. The grid layouts do not show a wallpaper.

1. Hold the clock to open the setup.
2. Tap `next` to go to the theme step.
3. Tap `your wallpaper` and pick a photo.
4. Turn the dial to pick the colours.
5. Tap `next` until you see `done`.
6. Tap `done`.

Detent crops the centre of the photo to the shape of your screen and keeps a copy. It does not need access to your other photos. To go back to the theme wallpaper, tap `theme wallpaper` on the same step. If the `lock screen` switch is on, the lock screen uses your photo too.

## Settings

The `feel` step has 6 switches and a choice of status bar.

| Switch | What it does |
| --- | --- |
| `sound` | Plays a short click for each app that passes the notch. The phone mutes it in silent or vibrate mode. |
| `haptics` | Gives a light tick for each app and a firmer tick for each letter. It follows the system touch feedback setting. |
| `dots` | Shows an accent dot on apps with new notifications. The dot clears when you open the app. This switch needs notification access. |
| `lock screen` | Sets the lock screen and the system wallpaper to the theme. On the grid layouts, it sets the dot grid. Android then takes the lock screen colours from it. |
| `voice` | Shows the mic button in the bottom corner. |
| `web search` | Shows the web button in the bottom corner. |

| Status bar | What it shows |
| --- | --- |
| `system bar` | The Android status bar. This is the default. |
| `detent bar` | Only wifi, signal, and battery, in the style of the layout. The radial layouts draw them as hairlines. The grid layouts draw them as dots. Detent hides the Android status bar. |

The `feel` step also has `export settings` and `import settings`. Export uses Android's file picker to save one `.json` file. Import chooses one `.json` file and applies its settings immediately. Keep the file if you plan to uninstall Detent: uninstalling removes the saved setup.

The version 1 file stores the layout and theme **by name**, dial side, selected dial app keys, up to 3 quick buttons (app key and optional icon), recent search app keys, and the `sound`, `haptics`, `dots`, `lockWall`, `ownBar`, `voice`, and `webSearch` switches. The switches match the labels in the table above; `ownBar` means `detent bar`. Apps no longer installed on the phone are skipped. If the saved layout or theme is unavailable, Detent keeps the current one. Unknown file fields are ignored; unsupported file versions are rejected.

The file does not include your wallpaper photo, Android permissions, notification history, app usage counts, or synced desktop theme colours. Import keeps the wallpaper photo already stored on this device. Notification dots still need notification access on the new phone.

With the `detent bar`, drag down from the top of the home screen to open the notifications. A swipe from the top edge shows the Android status bar for a short time.

If Android shows "Restricted setting" when you allow notification access, open the app info for Detent. Tap ⋮, then `Allow restricted settings`. Then try again.

## Permissions

Detent uses the internet only when you explicitly scan or import a theme collection. It contacts GitHub/the linked hosts, which see your IP address and requested URLs. It does not upload your apps, photos or settings, run repository code, or read your clipboard automatically. Imported images and palettes stay on the phone.

The mic button uses the speech service of your phone, such as Speech Services by Google. That service turns your voice into text and gives Detent only the text. The service is a separate app with its own permissions.

| Permission | Why Detent needs it |
| --- | --- |
| `QUERY_ALL_PACKAGES` | To list all the apps you can open and to find your default apps for the first dial. |
| `VIBRATE` | For haptics. |
| `INTERNET` | To scan and download theme collections you explicitly request. |
| `SET_WALLPAPER` | For the `lock screen` switch. |
| `EXPAND_STATUS_BAR` | To open the notifications when you drag down from the top of the home screen. |
| `ACCESS_NETWORK_STATE` | For the wifi signal in the `detent bar`. Detent reads only the signal strength. |
| `RECORD_AUDIO` | Optional. For the mic button. The microphone is on only while Detent listens. If you do not allow it, the mic button opens the speech dialog of your phone. |
| Notification access | Optional. For unread dots. Detent reads only which apps have notifications and when they arrived. It does not keep the notification text. |

## Build from source

You need JDK 17 or later and the Android SDK with platform 36.

To build the APK and install it on a connected phone, run these commands in the repository folder:

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without the signing variables, the build uses your local debug key.

## Release

The `build` workflow builds the APK on each push and pull request. You can download that APK from the workflow run.

To publish a release, push a version tag:

```sh
git tag v0.2.0
git push origin v0.2.0
```

The workflow then builds a signed APK and attaches `detent.apk` to a GitHub release.

The workflow signs releases with these repository secrets:

| Secret | Value |
| --- | --- |
| `DETENT_KEYSTORE_BASE64` | The release keystore, encoded as base64. |
| `DETENT_KEYSTORE_PASSWORD` | The keystore password. |
| `DETENT_KEY_ALIAS` | The key alias. |
| `DETENT_KEY_PASSWORD` | The key password. |

To sign a local build with the same key, set the variables `DETENT_KEYSTORE`, `DETENT_KEYSTORE_PASSWORD`, `DETENT_KEY_ALIAS`, and `DETENT_KEY_PASSWORD`. `DETENT_KEYSTORE` is the path to the keystore file.

Caution: if your phone has a build signed with a different key, Android refuses the update. Uninstall Detent first. This removes your Detent setup.

## Credits

- The wallpapers and the dot-hand art come from the [Omarchy](https://github.com/basecamp/omarchy) themes.
- The font is [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), under the SIL Open Font License 1.1. See [`licenses/JetBrainsMono-OFL.txt`](licenses/JetBrainsMono-OFL.txt).

## License

MIT. See [`LICENSE`](LICENSE).
