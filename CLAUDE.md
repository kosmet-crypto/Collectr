# Collectr — notes for Claude

Collection and stock tracker, first for LEGO limited edition foil packs, usable for any collection.
Owner writes in Serbian (Cyrillic); reply in Serbian. Code, comments and commits in English.

## Layout
- `index.html` — the whole app: vanilla JS, no build step, no framework. State in `localStorage`
  (`collectr.v1`), own photos in IndexedDB (`collectr` / `photos`).
- `vendor/xlsx.full.min.js` — SheetJS, loaded lazily for Excel import/export.
- `catalog/lego-foil.json` — foil packs by series, rebuilt by `.github/workflows/catalog.yml`
  (weekly, from Rebrickable CSV dumps) with `scripts/build_catalog.py` and the rules in `catalog/series.json`.
- `android/` — WebView wrapper (package `app.collectr`, bridge `window.CollectrAndroid`):
  `WebUpdater` (over-the-air page updates), `ImageCache` (`/imgcache/…` pictures kept on the phone),
  `ApkInstaller` (in-app APK updates), camera/file chooser, file saving.
- `sw.js`, `manifest.json`, `icons/` — web (PWA / GitHub Pages) version.

## Releasing
- Everything ships from `main`. Each push that touches the app builds an APK and a GitHub Release
  (`v1.0.<run number>`, asset `collectr.apk`).
- Page-only changes reach phones silently: the app downloads `index.html` from `main` at launch
  and switches to it right away. The APK update is offered only when `android/` changed
  (the release notes carry a `native:` hash of `android/`).
- A downloaded page must call `CollectrAndroid.ready()` within 15 s or it is rolled back and that
  exact page is never retried. Keep `ready()` right after the first `render()`, before any await.
- The page must keep `id="tab-home"`, be > 20 000 chars and declare
  `<meta name="collectr-native-api">`. When the page starts calling a new bridge method, guard it
  (`APP && APP.method`) or raise that meta and `WebUpdater.NATIVE_API` together.
- Web version: bump `VERSION` in `sw.js` when the shell files change.

## Product rules (agreed with the owner)
- Nothing personal as a default: the app opens in English (Serbian and Norwegian bokmål selectable),
  locations start as "Location 1" (selling) and "Location 2" (storage), shown in the chosen language
  until the user names them; currency EUR, no preset place to sell. The owner's own setup
  (Oslo/Belgrade, NOK, Finn) is data, never code.
- Per item: collection target defaults to 2 (all you have if fewer). Collection copies are counted
  first from the "keep first" location (storage), best condition first; the rest is for sale.
  "Sell now" = surplus at selling locations, "waiting" = surplus elsewhere.
- Conditions: mint (default), opened, damaged pack. Surplus moves and sells worst condition first.
- Purchase prices are stored in their own currency plus the main-currency value at entry time.
- Catalog numbers the user removes go to `S.hidden` and never come back with catalog updates.

## Data changes
- Never drop or rename stored fields without a migration in `load()`.
- A migration that rewrites user data must run once (flag in `S.meta`), otherwise it overrides what
  the user types later (this bit us with location names).
- Backups are JSON `{app: 'collectr', v: 1, state, photos}`; keep restore working for old backups.

## Texts
- UI strings live in `L` (`[Serbian, English]`) and `NB` (Norwegian, falls back to English).
  Add every new string to all three. Native Android messages use `tr(sr, en, nb)`.

## Checking a change
- `node -e` syntax check of the `<script>` in `index.html`.
- Serve the repo (`npx http-server -p 8765 -s -c-1 .`) and drive it with Playwright
  (Chromium at `/opt/pw-browsers/chromium`, phone viewport 390×844): create a foil pack collection,
  count, buy, sell, move, export and re-import Excel (must not change stock), backup, reload.
- Java cannot be built here (no Android SDK); `javac` on single files only catches syntax errors.
  CI on `main` is the real build — check the Android APK run after pushing.
- Picture hosts (BrickLink, Rebrickable) are blocked in the cloud sandbox; placeholders there are expected.
