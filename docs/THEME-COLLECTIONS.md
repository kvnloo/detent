# Theme collections

## Import

Setup → theme → **import +**. Paste a public HTTPS URL and choose **find themes**.
Select the items to keep (up to 20 per batch), choose **import**, then **view imported**.
The theme picker previews the result; **done** commits the selection as usual.
Android's share menu can send a `text/plain` link to Detent. A website can link to
`detent://import?url=<percent-encoded-https-url>`; opening it only fills the importer,
never downloads or changes the wallpaper automatically.

Supported sources:
- Public GitHub repository, folder (`/tree/<branch>/<folder>`) or file (`/blob/...`).
  A repository uses its default branch, not an assumed `main` branch. Folder links
  currently use a single-segment branch name; use a raw collection URL for branch names containing `/`.
- A JSON manifest, a direct JPG/PNG/WebP image, or an HTML gallery linking those images.
  HTML galleries are scanned one page deep; JavaScript-only galleries, login pages,
  private repositories and arbitrary theme configuration scripts aren't supported.
- GitHub repo discovery favors `background`, `backgrounds`, `wallpaper`, `wallpapers`
  and `walls` folders rather than importing app icons. It also reads up to 12 files named
  `detent-themes.json`, `themes.json`, `collection.json` or `live.json`.

For example, `https://github.com/kvnloo/.files` includes background images plus
Workshop favorites in `config/wallpapers/live.json`. Desktop filesystem paths such as
`workshop_dir`, `mobile_store` and `mobile.file` are not public URLs and aren't downloaded.

## Publish a collection

Publish `detent-themes.json` next to your images (or link any JSON filename directly):

```json
{
  "themes": [
    {
      "name": "night mountains",
      "image": "walls/mountains.webp"
    },
    {
      "name": "my light palette",
      "image": "walls/paper.jpg",
      "bg": "fffaf0",
      "fg": "232323",
      "accent": "2563eb"
    },
    {
      "name": "live favorite",
      "preview": "previews/scene.jpg",
      "workshop_url": "https://steamcommunity.com/sharedfiles/filedetails/?id=2133182232"
    },
    {
      "name": "redistributable phone scene",
      "preview": "previews/phone-scene.jpg",
      "mobile": {"url": "phone-scene.mpkg"}
    }
  ]
}
```

A bare array or an object with `wallpapers` instead of `themes` also works. Paths are
relative to the manifest. Images without explicit colours get a palette derived on-device.
A live entry can have both `workshop_url` and `mobile.url`. Publish packages only when
redistribution is permitted by the wallpaper author/license.

## Wallpaper Engine

A **Workshop link** is a saved favorite, not a phone-ready wallpaper. Choosing it opens
its Workshop page. Install Wallpaper Engine on your desktop, subscribe to the item and
transfer it to the phone through Wallpaper Engine. Detent cannot download paid Steam
assets or convert a link into a phone package.

An explicit **mobile.url** downloads a package to private storage and shares it with the
Wallpaper Engine app when selected. Wallpaper Engine still asks you to apply it. Detent
never extracts or executes package contents. An optional preview is shown only in the
picker; the home screen remains transparent so the applied live wallpaper shows through.

Existing grid layouts still use their dot-field background; imported static wallpapers
are visible on radial layouts. A separately selected custom photo also keeps precedence
until you select **theme wallpaper**.

## Storage, limits and privacy

Imports are local copies, not subscriptions. Re-importing the same image/package/Workshop
URL does not duplicate it or silently overwrite a saved theme. Saved import IDs keep the
selection stable if the list of desktop-synced live themes changes. Cancelling setup does
not remove themes you explicitly added to the library.

Discovery displays at most 200 distinct entries, with a notice if capped. Metadata is
limited to 4 MiB per request, images to 16 MiB and 50 million source pixels, packages to
48 MiB, and each import batch to 64 MiB downloaded data. Images are downsampled to a
maximum dimension of 2560 pixels. Failed entries are reported individually; successful
entries remain saved. No repository code or install hooks are run.

HTTPS only; no embedded login credentials, custom ports or local-network targets.
Redirects are checked, downloads have timeouts and byte limits, and files use generated
names inside app-private storage rather than untrusted remote paths. Scanning/importing
contacts the chosen hosts (and GitHub's public API); those services see the requested
URLs and your IP address. GitHub's unauthenticated rate limits apply. No account token,
clipboard scraping, telemetry or uploads of your apps/settings/photos are needed.
