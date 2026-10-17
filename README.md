# PersianRubinFilm (prototype v0.1)

An Android Kotlin/Jetpack Compose prototype with a dark, streaming-style catalog UI, English/Persian toggle, search, favorites, and an in-app WebView for source-site playback.

## Build on GitHub
1. Create a GitHub repository named `PersianRubinFilm`.
2. Upload the contents of this folder to the repository root (including `.github/workflows/android.yml`).
3. Open **Actions → Build Android APK → Run workflow** (or push to `main`).
4. Download the `PersianRubinFilm-debug-apk` artifact from the completed workflow.
5. Install the APK on your Android phone after allowing installation from your chosen file manager/browser.

You can also create a GitHub Release; its workflow will attach the debug APK to that release.

## Build locally
Open in Android Studio with Android SDK 35 and JDK 17, let Gradle sync, then **Build → Build APK(s)**. This package deliberately does not contain a generated Gradle wrapper; GitHub Actions provisions Gradle 8.10.2.

## Limitations / next steps
- **Prototype, not a tested APK.** This environment has no Android SDK or Gradle; the workflow must compile it.
- The catalog parser uses public HTML headings and may need updating if the site changes its structure. Catalog completeness and poster extraction are not guaranteed.
- Playback displays the original source page in WebView; it does not extract stream URLs, defeat DRM, or guarantee embedded video playback.
- The source site and third-party hosts may prohibit republishing catalog imagery or embedding. Confirm authorization before public distribution.
- Favorites persist on the device; watch progress, seasons/episodes navigation, caching, paging, and full-screen player handling are future work.
- Persian UI text is present, but full RTL layout and full localization are not yet implemented.
- For production distribution, use a **signed release APK** rather than a debug APK; do not publish your signing key.


## Version 0.2 changes
- Expanded poster extraction for lazy-loaded image attributes and srcset; image requests include a site referer. Posters still depend on the source website exposing usable images.
- Disables JavaScript-created popup windows and blocks top-level navigation to external domains. Embedded third-party player frames remain permitted; ads within a frame are not filtered.
- Local History tab: last opened title, last visited first-party page, manual watched toggle, and remove-history action. No account required.
- Exact episode numbers and video playback timestamps cannot be reliably extracted from arbitrary third-party players; History is page-level, not guaranteed playback resume.
- Updated GitHub Actions workflow with the SDK setup fix.

Build verification: source modified; a full Gradle/Android build has not been run in this environment. Build through GitHub Actions and inspect its log.
