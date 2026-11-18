# 151 Grabber

Paste a link, get every image on the page saved in order: 001.jpg, 002.png, … in a folder named as you like. Desktop (Linux) and Android.

Part of The 151 Universe family of apps.

## What it does
- Reads the page and saves its images in the order the page shows them, at the best size the page offers.
- Follows the "next" arrow on sites that show one image per page, staying in the same chapter.
- "Pages it links to": lists the pages a page links to, guesses which ones belong together (chapters, episodes), lets you untick the ones you don't want, and saves each one in its own folder.
- Joins image strips that a site splits in two.
- Android: downloads keep going in the background with a progress notification; several jobs at once.

## What it does not do
No video, no pages behind a login, no pages that load images only while scrolling. No accounts, no tracking.

**Use it only on content you have the right to download.**

## Run
Desktop: Python 3.10+, `pip install PyQt6 requests beautifulsoup4 pillow`, then `./run.sh`.

Android: JDK 21 and the Android SDK, then `cd android && ./gradlew assembleDebug`. Signing is optional: put `keystore.properties` and your keystore in `android/.signing/` (ignored by git); without it debug uses the Android debug key and release is unsigned.

## Tests
- `python3 autotest.py` – the desktop core against a local fake website.
- `cd android && ./gradlew testDebugUnitTest` – the Android core on the JVM.

All test pages and images are synthetic. Code comments are in Italian.

## License
MIT (see `LICENSE`). jsoup, requests, Beautiful Soup, Pillow and PyQt6 keep their own licenses.

A personal project, shared as is: no support, but issues are welcome.
