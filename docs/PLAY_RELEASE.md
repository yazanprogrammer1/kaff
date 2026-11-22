# Publishing Kaff on Google Play

Everything the Play Console asks for, prepared from the code as it is. Account type: organization
(production release without the 12-tester closed test).

## 1. Build

```bash
./gradlew :app:bundleRelease   # app/build/outputs/bundle/release/app-release.aab
```

- Signed with the **upload key** in `~/.kaff-signing/` (`kaff-upload.jks` + `keystore.properties`).
  Never commit it. Back the whole folder up to two private places. CI or another machine can point
  `KAFF_SIGNING_PROPERTIES` at a copy.
- Upload key certificate SHA-256:
  `BE:2A:41:4A:FC:03:D1:71:52:A0:A2:44:6F:64:60:56:AB:ED:96:31:0B:F0:21:24:7C:D2:08:F6:7F:01:09:FD`
- On first upload, accept **Play App Signing** (Google keeps the app signing key; a lost upload key
  can then be reset through Play support).
- Before each release bump `versionCode` (and `versionName`) in `app/build.gradle.kts`.

## 2. Store listing

Texts live in `fastlane/metadata/android/{ar,en-US}/` (title ≤ 30, short description ≤ 80, full
description ≤ 4000, `changelogs/<versionCode>.txt`). Default language: Arabic (`ar`), plus English.

| Asset | Size | Status |
| --- | --- | --- |
| App icon | 512 × 512 PNG | `fastlane/metadata/android/<lang>/images/icon.png` |
| Feature graphic | 1024 × 500 PNG | `…/images/featureGraphic.png` (source: `graphics-src/`) |
| Phone screenshots | 2–8, 9:16 | to capture on a device |

- Category: **Tools**. Tags: file transfer, sharing.
- Contact email: `kaff.app.support@gmail.com` (also in `website/privacy.html`).
- Privacy policy URL: `https://<github-user>.github.io/<repo>/privacy.html` once the site is live.

## 3. App content

- **Ads**: no ads.
- **App access**: all functionality available without login.
- **Target audience**: 13+ (general audience; not designed for children).
- **Content rating** (IARC questionnaire): utility app, no violence, no user-generated content shared
  publicly, no gambling; "users can interact/exchange content": only directly between their own nearby
  devices, no online sharing. Expected rating: Everyone / 3+.
- **News app**: no. **COVID-19 app**: no. **Government app**: no. **Financial features**: none.

### Data safety

Kaff has no backend; nothing leaves the device except directly to the user's own nearby device.

- Does the app collect or share user data? **No.**
  - Transfers between the user's devices are not "collection" (data is not sent to the developer
    or a third party) and are encrypted in transit.
- Is data encrypted in transit? **Yes** (Nearby Connections; AES-256-GCM on the fast lane; WPA2 on
  Kaff's network).
- Can users request deletion? History is local only: cleared in-app or by uninstalling.
- Note for reviewers: the camera is processed on-device (MediaPipe) and never stored or sent.

### Permission declarations

| Permission | Why (text for the declaration) |
| --- | --- |
| `CAMERA` | Recognise the send (fist) and receive (open palm) hand gestures on the device. Frames are never stored or sent. |
| `ACCESS_FINE_LOCATION` / `COARSE` | Required by Android and Google Play services Nearby Connections to discover nearby devices over Bluetooth/Wi-Fi. Location is never read or stored. Foreground only. |
| `NEARBY_WIFI_DEVICES`, `BLUETOOTH_*` | Discover and connect to the user's other device for the transfer. |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | **Needs the Photo and video permissions declaration.** Core use: a file-transfer app whose main screen is a picker of the user's photos and videos to send (multi-select, up to 50 items, live thumbnails). If Google rejects it, fall back to the system photo picker (already used for partial access) and drop these two permissions. |
| `READ_MEDIA_AUDIO` | Pick music and audio files to send. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Standby mode: an opt-in service that keeps the phone discoverable to the user's other device. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Opt-in floating bubble that opens the receive screen ("User-enabled floating shortcut bubble"). Play asks for a short video showing the toggle in Settings and the bubble. |
| `SYSTEM_ALERT_WINDOW` | The same opt-in floating bubble, granted by the user in system settings. |
| `CHANGE_WIFI_STATE` / `ACCESS_WIFI_STATE` | Create Kaff's local network (Wi-Fi Direct group or local-only hotspot) for computers. |
| `INTERNET` | Local sockets between the user's devices only; Kaff never contacts an internet server. |

Policy notes to keep in mind:
- **Sending installed apps (APK)** is peer-to-peer between the user's own devices, like other
  file-transfer apps. Kaff does not install anything itself (the system installer handles received
  APKs) and does not request `REQUEST_INSTALL_PACKAGES`.
- The **debug-only** throughput probe (`app/src/debug`) never ships in release builds.

## 4. Release

1. Production → Create release → upload `app-release.aab` → release notes from `changelogs/1.txt`.
2. Countries: all (or start with the Arab region), price: free.
3. Review usually takes a few days for a new app; permission declarations can add time.

## 5. Before the first public release

- Install the **release** build on two phones and run the full flow (gesture send/receive, a large
  video, desktop page, share sheet). R8 is on in release builds.
- Measure phone-to-phone speed on the release build with Wi-Fi on (target: a 600 MB file in under a
  minute) before the listing promises it.
