# Lightning Browser [![Build](https://github.com/anthonycr/Lightning-Browser/actions/workflows/action.yml/badge.svg)](https://github.com/anthonycr/Lightning-Browser/actions/workflows/action.yml)

### Speed, Simplicity, Security
![](launcher_icon_small.png)

### Download
[<img src="https://f-droid.org/badge/get-it-on.png"
      alt="Get it on F-Droid"
      height="80">](https://f-droid.org/app/com.browser.minnal) [<img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" 
alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=com.browser.minnal)

### Features
* Bookmarks

* History

* Multiple search engines (Google, Bing, Yahoo, StartPage, DuckDuckGo, etc.)

* Incognito mode

* Unique utilization of navigation drawer or bottom drawer for tabs

* Customizable search suggestions

* In-built download manager with resume / pause / cancel, foreground notifications, and a Material-3 in-app downloads page

* "Discover" news feed on the home page (RSS-aggregated, Tamil + English, no API keys, no tracking)

### SSL certificate errors

Minnal Browser does **not** show the blocking dialog that used to prompt with “Connection to this site is not secure” and “Proceed anyway?”. When a certificate problem is detected, the load **continues without that confirmation step** (unless a per-domain choice was previously saved from an older build, in which case that stored behavior is still applied).

The address bar can still reflect a non-valid SSL state where the UI supports it.

### Permissions

#### Automatically granted
* `INTERNET`: necessary to access the internet.
* `ACCESS_NETWORK_STATE`: used by the browser to stop loading resources when network access is lost.
* `INSTALL_SHORTCUT`: used to add shortcuts with the "Add to home screen" option.
* `POST_NOTIFICATIONS`: used to display download progress notifications (Android 13+ also requires the user to grant this at runtime).
* `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`: used by the in-built download manager to keep large downloads running reliably (Android 14+ requires the `dataSync` type to be declared).
* `WAKE_LOCK`: used briefly while WorkManager runs a download so the OS doesn't suspend the transfer mid-flight.

#### Requested only when needed
* `WRITE_EXTERNAL_STORAGE` (Android 9 and below): needed to download files to the legacy Downloads folder and export bookmarks. Not requested on Android 10+ — the download manager writes to `MediaStore.Downloads` instead, no permission needed.
* `READ_EXTERNAL_STORAGE` (Android 12 and below): needed to import bookmarks and to read user-picked files. Not requested on Android 13+.
* `ACCESS_FINE_LOCATION`: needed for sites like Google Maps, requires "Location access" option to be enabled (default disabled).
* `RECORD_AUDIO`: needed to support WebRTC, requires "WebRTC Support" option to be enabled (default disabled).
* `CAMERA`: needed to support WebRTC, requires "WebRTC Support" option to be enabled (default disabled).
* `MODIFY_AUDIO_SETTINGS`: needed to support WebRTC, requires "WebRTC Support" option to be enabled (default disabled).

### Network endpoints contacted by the app itself

Aside from the websites the user navigates to, the app makes outbound network calls in two situations:

1. **In-built download manager** — directly fetches the URL the user clicked, no proxy / no third party.
2. **"Discover" news feed on the home page** — fetches a curated set of RSS feeds (configured in [`NewsSource.DEFAULTS`](app/src/main/java/com/browser/minnal/news/NewsSource.kt)). Currently:
   * `https://www.thehindu.com/news/national/feeder/default.rss`
   * `https://timesofindia.indiatimes.com/rssfeedstopstories.cms`
   * `https://feeds.feedburner.com/ndtvnews-top-stories`
   * `https://www.espncricinfo.com/rss/content/story/feeds/0.xml`
   * `https://www.hindutamil.in/rss/headline.xml`
   * `https://www.dinamalar.com/rss.aspx?cat=1`

   These publishers will see the device IP address and a generic `MinnalBrowserNewsBot/1.0` User-Agent on each fetch. No personal identifiers, no advertising ID, no cookies are sent. The feed can be turned off in **Settings &rarr; General &rarr; Show news on home page**, after which no RSS request is made.

### Play Store listing — what to update before the next release

If you publish updated builds to Google Play, the changes in this version trigger a few Play Console / store-listing updates. **None of them require new accounts or paid services**, but the review may bounce the build until they're filled in.

1. **App content &rarr; Data safety form**

   * Under **Files and docs &rarr; Files and docs** add a disclosure: *"Collected: No. Shared: No. Processed ephemerally: Yes — files the user explicitly downloads via in-app links are written to the device's Downloads folder."* (Required because we now own the download stack instead of delegating to the system DownloadManager.)
   * Under **App activity &rarr; In-app actions** keep "No data collected" (the news feed does not record which articles the user opens).
   * Under **Web browsing &rarr; Web browsing history** keep the existing disclosure unchanged.
   * Confirm **Data is encrypted in transit = Yes** (all RSS feeds are HTTPS; downloads use whatever scheme the URL uses).

2. **App content &rarr; Government apps / target audience &rarr; Foreground services**

   Add or update the foreground-service declaration for the `dataSync` type. Suggested justification text: *"Used by the in-built download manager to keep file downloads reliably running while the user navigates away from the app. Notification shows progress and a Cancel action throughout."* This is a **mandatory** declaration since Android 14; without it a release build can be rejected.

3. **App content &rarr; Permissions declaration form**

   Re-acknowledge `WRITE_EXTERNAL_STORAGE` is bounded to `maxSdkVersion="28"` (already in the manifest). Google occasionally re-prompts for confirmation when a manifest entry of a sensitive permission changes scope.

4. **Store listing &rarr; What's new in this version**

   Suggested copy: *"New in-built download manager with pause / resume / live progress, plus a Discover news feed (Tamil + English) on the home page. No accounts, no tracking."*

### Privacy policy — what to update

The bundled privacy policy at [`app/src/main/assets/privacy_policy.html`](app/src/main/assets/privacy_policy.html) has been updated in this commit to disclose:

* The in-built download manager (where downloaded files are stored, that no metadata is sent to us).
* The Discover news feed (which third-party publishers receive the device IP / User-Agent on each fetch, and that the feed can be disabled).
* The `FOREGROUND_SERVICE_DATA_SYNC` and `WAKE_LOCK` permissions and what they're used for.

If you host the privacy policy on a public URL (recommended, and required by Google Play), make sure that URL is updated to mirror the bundled HTML before you publish. The "Last updated" line should be bumped to the publication date.

> The bundled privacy policy is a **template** &mdash; it is not legal advice. Have an attorney review it for your jurisdiction (especially EU / UK / California) before publishing.

### Contributing
* Contributions are always welcome
* Make pull requests into the `main` branch.

### License
```
Copyright 2014 Anthony Restaino

Lightning Browser

   This Source Code Form is subject to the terms of the 
   Mozilla Public License, v. 2.0. If a copy of the MPL 
   was not distributed with this file, You can obtain one at 
   
   http://mozilla.org/MPL/2.0/
```
