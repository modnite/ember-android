# Ember

Ember is the native Android messenger for a Hearth server. It is a real Android app (Kotlin, Jetpack Compose, Material 3), not a web view. It talks to the same server as the web app and the Windows client.

Package `org.hearth.ember`, minSdk 26, targetSdk 35. No Google services, no push provider, no assistant features.

## What it does

- Chats: direct and group, replies, reactions, edits, pins, forwarding, photos, videos, GIFs, stickers, files, voice notes, link previews, nudges. Tap or long press a message to pick it: a top bar offers reply, copy, forward and delete, and your most used reactions float beside it.
- Photos and videos: take them with the camera button or pick them from the gallery, look at them and add a caption before they go. Videos play in the app (seeking, also in encrypted chats). A group's picture can be changed by anyone in it.
- Stickers and GIFs: the face button opens emoji, stickers and GIFs. A sticker is made from any picture (512 px WebP, drawn without a bubble) or kept from a chat; GIFs are added from files and move in the chat.
- Backup: to any number of places (a folder Android can reach, WebDAV or Nextcloud), by hand or daily, weekly or monthly, keeping the newest few, with your encryption key, settings and stickers and optionally a readable archive of your chats and their files. Encrypted with a passphrase unless you turn that off. A new phone restores from the sign-in screen. `tools/open_archive.py` opens an archive on a computer.
- Contacts: add by username, accept requests, people only see contacts and shared rooms.
- End-to-end encryption (Hearth E2EE v2): keys are set up per phone, chat text and files are sealed on the phone. Calls in encrypted chats are sealed with the same derived media key as the web app.
- Calls: pre-call screen with camera, lenses (front, 0.6x, 1x, 3x) and mic check, one-to-one and group, screen sharing (1080p, 60 fps, 8 Mbps target), audio output choice, picture in picture with the system controls.
- Search: chats by name, messages by text (server for plain chats, on this phone for encrypted ones).
- Group details: members, rename, leave.
- Notifications without a push service: a foreground service long-polls the server. Its notification channel is created switched off, so nothing is shown for it. Messages (reply and mark as read from the shade), calls (answer or decline), nudges (buzz, no sound).
- Layouts: portrait, landscape (one thing at a time on a phone), and a multi-pane layout for 1000dp and wider windows (Samsung DeX, desktop mode, tablets). Mouse right-click opens the message menu, Enter sends and Shift+Enter adds a line.
- Light, dark and optional wallpaper colours.

## Build

Needs JDK 17 and the Android SDK (compileSdk 35). From this folder:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew assembleRelease      # signed with keystore.properties, else with the debug key (installs and updates in place)
```

LiveKit's audio helper comes from JitPack, so the repository list in `settings.gradle.kts` must keep it.

## Signing a release

Create a key once and keep it safe. Losing it means users cannot update in place.

```bash
keytool -genkeypair -v -keystore ember-release.jks -alias ember -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` next to `gradlew` (it is git-ignored):

```properties
storeFile=ember-release.jks
storePassword=...
keyAlias=ember
keyPassword=...
```

`./gradlew assembleRelease` then produces a signed `app-release.apk`. Raise `versionCode` in `app/build.gradle.kts` for every release.

## Installing and updating

Download `Ember-x.y.z.apk` from the [latest release](../../releases/latest) and open it (Android asks once to allow installs from your browser or files app). Ember checks this repository's releases by itself about twice a day. When there is a newer version it offers the update, downloads it and hands it to Android's installer. Settings → the version line at the bottom also checks on a tap. Android only accepts an update signed with the same key as the installed app, so a file from anywhere else cannot replace Ember.

## Making a release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease` (needs `keystore.properties`, see above) and check the output with `apksigner verify --print-certs`.
3. Rename the file `Ember-<versionName>.apk`, create a GitHub release tagged `v<versionName>` and attach it, with a few lines of notes (they show in the update dialog).

```bash
gh release create v0.1.0 Ember-0.1.0.apk --title "Ember 0.1.0" --notes "What changed"
```

The release key (`ember-release.jks`) is never committed. Keep a copy somewhere safe: without it nobody can update in place.

## Using it

Open the app, enter your server address, sign in (or use an invite code). The first time you open an encrypted chat, choose "Set up on this phone". Turn on "Message and call alerts" in Settings to get notifications while the app is closed, and allow Ember to run without battery limits when it asks.

## Testing against a scratch server

`tools/scratch` in the repository root has a scratch Hearth server, a LiveKit dev setup and bots that join calls. Never point tests at a production server. Useful pieces: `start-hearth.sh`, `seed.mjs` (creates a second user and a conversation), `call-bot.mjs` (joins a call; set `BOT_TONE=1` for sound, `BOT_DUMP=path` to save a received camera frame), `nudge.mjs`.
With a phone over `adb`, `adb reverse tcp:18200 tcp:18200` lets the app reach the scratch server as `http://localhost:18200`.

## Known limits

- Calls in encrypted chats were checked against the web client (Firefox) and the native LiveKit SDK: audio and video decrypt both ways. A Chromium/Safari browser has not been tried.
- Viewing someone else's screen share is built and uses the same tracks as the web client, but has only been checked from the sharing side.
- Searching encrypted chats only covers messages already loaded on the phone.
- Android Telecom integration (system call UI, Bluetooth and watch call controls) is not built. UnifiedPush is not supported either.
- The XP look is intentionally absent and will come as a separate theme later.

## Licence note

Nothing is copied from other messengers. Element X Android (AGPL) was read for patterns only. If code from an AGPL project is ever copied in, Ember would have to be released under the AGPL as well.
