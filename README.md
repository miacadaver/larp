<p align="center">
  <img src="docs/icon.svg" width="120" alt="larp.fm">
</p>

<h1 align="center">larp.fm</h1>

<p align="center">
  a little Last.fm and Libre.fm scrobbler for Android, dressed up like a DS system menu
</p>

<p align="center">
  <a href="https://github.com/teamomuito/larp.fm/actions/workflows/android.yml"><img src="https://github.com/teamomuito/larp.fm/actions/workflows/android.yml/badge.svg" alt="build"></a>
  <img src="https://img.shields.io/badge/android-8.0%2B-ff4fa3" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/made%20with-kotlin-c2185b" alt="made with Kotlin">
</p>

<br>

larp.fm picks up whatever's playing on your phone (Spotify, YouTube Music, Poweramp, pretty much
any app with a media notification) and scrobbles it to [Last.fm](https://www.last.fm) or
[Libre.fm](https://libre.fm). No internet? Plays get saved and sent later.

## features

- scrobble as soon as a song starts, or after up to 50% of it (Last.fm's default is 50%)
- once a song has counted, scrobble it again straight away when you resume it after a pause, or jump to another point in it (switch it off under Settings → Scrobble again)
- works with Last.fm or Libre.fm, you pick when you sign in
- send the album, or just artist + track
- clean up album titles: `Iron Maiden (Remaster) [Special]` becomes `Iron Maiden`
- scrobble just the first artist: `Artist A feat. Artist B` becomes `Artist A`
- choose which apps get scrobbled
- check what Last.fm or Libre.fm actually recorded, right from the app
- the look of [Pictoclip](https://github.com/teamomuito/pictoclip): grid paper, notched panels, and a pixel font

## install

1. Download **[larpfm.apk](https://github.com/teamomuito/larp.fm/releases/latest/download/larpfm.apk)**.
   That link always points to the newest build, and older ones are on the
   [releases page](https://github.com/teamomuito/larp.fm/releases).
2. Install the APK. Your phone will ask you to allow installs from your browser or files app.
3. Tap **Sign in with Last.fm**, hit Allow on the Last.fm page, and you're back in the app. If
   the build doesn't have an API key baked in, it'll ask for one first.
   [Grab one here](https://www.last.fm/api/account/create). It takes a minute, and the name and
   description can be anything. For Libre.fm, pick **Libre.fm** first. It doesn't need an API key.
4. Give it notification access when it asks. That's the only way Android lets an app see what's
   playing. larp.fm doesn't read your notifications.

> [!TIP]
> On Android 13+, if the notification access switch is greyed out, go to
> **App info → ⋮ → Allow restricted settings** and try again. Android does this to every app
> that doesn't come from the Play Store.

Updating? Uninstall the old version first, or Android won't install the new one over it.

<details>
<summary><b>building it yourself</b></summary>
<br>

You'll need JDK 17 and the Android SDK.

```sh
./gradlew test assembleRelease
```

The APK ends up in `app/build/outputs/apk/release/`. GitHub Actions builds every push, and
each push to the main branch is published as a release (the 5 newest are kept).

These repo secrets are optional:

| secret | what it's for |
| --- | --- |
| `LASTFM_API_KEY`, `LASTFM_API_SECRET` | bakes an API key into the app so nobody has to paste one |
| `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` | signs every build with the same key, so updates install over the old version |

To make a keystore:

```sh
keytool -genkeypair -v -keystore release.keystore -alias larpfm -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore
```

The code is split in two:

- `core/` is plain Kotlin with unit tests. It has the scrobble rules, the playback tracking, the
  album title cleanup and the Last.fm/Libre.fm client.
- `app/` is the Android app. It uses Jetpack Compose for the UI, a notification listener that
  follows media sessions, and WorkManager to send scrobbles.

</details>

## credits

Font: [DotGothic16](https://github.com/fontworks-fonts/DotGothic16), SIL Open Font License 1.1 (see `FONT-LICENSE-OFL.txt`).
The look is borrowed from [Pictoclip](https://github.com/teamomuito/pictoclip). Not affiliated with Nintendo.
