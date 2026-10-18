# Games Library

A free phone app (installable web app) for cataloguing your games, consoles, soundtracks, books and accessories.

- **Camera:** scan the barcode on the box (EAN/UPC/ISBN) or take a photo of the cover.
- **Inventory fields:** title, log date, procurement cost, shipping cost (with total), condition (Sealed, New, Like new, Used, Damaged, Incomplete, Digital, Wishlist), platform, store, region/edition, notes.
- **Game completion:** Not started, Playing, On hold, Beaten, 100% done, Dropped, plus progress %, hours played, start/finish dates, 1–5 star rating and a HowLongToBeat link.
- **Missing info from public sources:** Wikipedia + Wikidata (games, consoles), Open Library + Google Books (books), MusicBrainz + Cover Art Archive (soundtracks), UPCitemdb free tier (retail barcodes). Only empty fields are filled unless you choose to replace. "Update missing info" does the whole library at once.
- **Stats:** totals spent, shipping share, finished %, backlog, hours, by type / platform / condition.
- **Local only:** data is stored in the browser on your device (IndexedDB). No account, server, ads or tracking.
- **Backups:** save a `.json` backup file (photos included) to Files/Drive/USB, restore it (replace or merge), or export a CSV spreadsheet. The app reminds you when a backup is overdue.
- **Offline:** once opened, it works without internet; only lookups need a connection.

## Android app (APK)

The native Android app (Kotlin + Jetpack Compose) is in `android/`. Add an item with the camera or a picture from the gallery, one item per picture. The phone reads the picture (Google ML Kit text recognition and image labelling, on-device, free), works out the title, platform and type, then fills in the details from Wikipedia/Wikidata, Open Library and MusicBrainz. Images are stored square (1:1). The library can be searched, filtered (type, platform, condition, progress, missing value), sorted, grouped and shown as a grid, small grid, list or compact list. Analytics shows total cost, market value and breakdowns; Settings holds the main currency (AED by default), exchange rates and zip backups.

Every push to `main` builds the APK with GitHub Actions and publishes it as the latest release:
https://github.com/hosani69/games-library/releases/latest/download/games-library.apk

Builds are signed with a key kept in the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS` (the key password is the same as the keystore password). Without those secrets each build uses a one-off key, so a new APK won't install over the old one.

## Web version

The files at the repository root are the earlier web version, served at https://hosani69.github.io/games-library/.

## Run the web version

It is plain HTML/CSS/JS with no build step. Host the folder on any free static host (GitHub Pages, Netlify, Cloudflare Pages), open it on your phone and choose **Add to Home Screen**. The camera needs HTTPS, which these hosts provide.

To try it on a computer: `python3 -m http.server` in this folder, then open http://localhost:8000.

Barcode scanning uses the phone's built-in barcode detector where available (Android Chrome) and the bundled ZXing library elsewhere (iPhone Safari). ZXing is Apache-2.0 licensed (`vendor/ZXING-LICENSE`).
