# Development

## Requirements

- JDK 17
- Android SDK, compileSdk 35
- A Hearth server to talk to. [hearth-server](https://github.com/modnite/hearth-server) runs locally with Docker.

## Build and test

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug build installs as `org.hearth.ember.debug`, next to a release install. With several adb devices attached, set `ANDROID_SERIAL` before `installDebug`.

## Signing

Create a key once. Losing it means nobody can update in place.

```bash
keytool -genkeypair -v -keystore ember-release.jks -alias ember -keyalg RSA -keysize 4096 -validity 10000
```

Create `keystore.properties` next to `gradlew`. Both files are git-ignored.

```properties
storeFile=ember-release.jks
storePassword=...
keyAlias=ember
keyPassword=...
```

Without `keystore.properties`, `assembleRelease` signs with the debug key.

## Releasing

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease`, then check the signature with `apksigner verify --print-certs`.
3. Rename the file to `Ember-<versionName>.apk`.
4. Commit, tag `v<versionName>`, push the tag.
5. Create the release. The notes show in the update dialog.

```bash
gh release create v1.0.0 Ember-1.0.0.apk --title "Ember 1.0.0" --notes "What changed"
```

Pushing a tag also runs the `backup-reader` workflow, which attaches the desktop packages to the release.

The updater reads `releases/latest` of this repository, so a release is also how users get an update.

## Testing against a local server

- Run hearth-server (`docker compose up -d` in that repo) or its dev server, with open registration on.
- With a phone on adb, `adb reverse tcp:8080 tcp:8080` lets the app use `http://localhost:8080`. The emulator reaches the host at `http://10.0.2.2:8080`.
- Test accounts and chats come from that server's API. `server/scripts/smoke.mjs` in hearth-server shows the calls.
- Do not point tests at a production server.

## Testing the update notifier

The hourly check compares the installed `versionName` with the latest release.

1. Build a debug APK with `versionName` temporarily set to 0.1.0, so the real release counts as newer.
2. Allow notifications for the app.
3. Force-stop the app, then run:

```bash
adb shell am start -a org.hearth.ember.TEST_UPDATE_CHECK -n org.hearth.ember.debug/org.hearth.ember.MainActivity
```

4. Restore `versionName` before releasing.

Do not force-stop the app afterwards. That clears its notifications.

## Layout notes

- Every screen is checked in portrait and landscape, and with the keyboard open. Fields must stay visible.
- Windows of 1000 dp or wider use the multi-pane layout. In an emulator: `adb shell wm size 1600x1000` and `adb shell wm density 213`. Reset with `wm size reset` and `wm density reset`.
- Styling is flat: no glow, gradients or halos, and no explanatory text under titles.

## Code map

| Package | Contents |
|---|---|
| `data/` | API client, repository, realtime, E2EE, backups, updater |
| `call/` | LiveKit call controller, call screen, Telecom bridge |
| `notify/` | Foreground receiver, notifications, sounds |
| `ui/chat/` | Chat screen, message bubble, pickers, viewers, spell check |
| `ui/settings/` | Settings pages, backup UI, two-step, updates |
| `ui/home`, `ui/contacts`, `ui/login` | Chat list, people, sign-in |
