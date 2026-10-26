# Decisions

Decisions made while building Kaff, with the reasoning. Newest phase last.

## Phase 1 — Foundation and gestures

- **Toolchain:** AGP 9.2.1 (built-in Kotlin, so `org.jetbrains.kotlin.android` is not applied), Kotlin 2.4.20, KSP 2.3.12, Gradle 9.7.1, JDK 17 bytecode. compileSdk/targetSdk 37 (the latest stable platform installed), minSdk 26.
- **Single `:app` module** with `core / domain / data / presentation / ui / service` packages. The app is small enough that Gradle modules would add build time without real isolation benefits. The domain package has no Android imports, so it can be extracted into a module later.
- **Model not committed.** `hand_landmarker.task` (7.8 MB) is fetched by `scripts/fetch_model.sh` from Google's official model storage (`storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/`). A `verifyHandModel` task fails `preBuild` with a clear message if it is missing. The asset is stored uncompressed (`noCompress "task"`).
- **Icons:** a small in-house `KaffIcons` set traced from the design SVGs instead of `material-icons-extended` (tens of MB of unused vectors before R8).
- **Classifier geometry.** Every cue is a ratio, so the classifier is invariant to hand size, position and in-plane rotation:
  - finger curl = mean of `dist(wrist,tip)/dist(wrist,pip)` and `dist(tip,mcp)/palm`. The second cue separates a half-curled "claw" from a real fist; with only the first cue, a claw scored about 0.68.
  - x and z are multiplied by the image aspect ratio before measuring, because MediaPipe normalizes x by width and y by height.
  - distances are 3D (MediaPipe z shares the x scale), which reduces foreshortening errors when fingers point at the camera.
- **Palm facing** uses the sign of the cross product of wrist→index MCP and wrist→pinky MCP, interpreted with handedness. Frames are mirrored before MediaPipe for the front camera, so MediaPipe's handedness, which assumes selfie input, is the user's real hand and landmarks line up with the mirrored preview. For an unmirrored back camera the label is swapped. Because this depends on a MediaPipe convention, `strictPalmCheck` can be turned off in Settings, and the Gesture Lab shows handedness and palm-facing live (see *Needs device testing*).
- **Debouncer:** EMA-smoothed confidence per gesture, N consecutive candidate frames to confirm (default 6 ≈ 200 ms at 30 fps), and release only after the smoothed confidence stays under a lower exit threshold for 4 frames (hysteresis). One sensitivity slider maps to all thresholds (`GestureThresholds.forSensitivity`); 0.5 gives exactly the defaults.
- **Synthetic fixtures:** tests build hands from a small 3D kinematic model (segment lengths plus MCP/PIP/DIP flexion) rather than recorded data, so they cover rotation, scale, aspect ratio, handedness and back-of-hand cases deterministically.
- **Camera:** preview and analysis both request 4:3 so the overlay (FILL_CENTER mapping) matches the visible preview. PreviewView uses COMPATIBLE (TextureView) so it can be clipped to rounded shapes. Analysis uses RGBA output and `KEEP_ONLY_LATEST`; every `ImageProxy` is closed via `use {}`.
- **Auto-timeout:** `GestureCamera` restarts its timer on every frame that contains a hand (`collectLatest` over an activity counter, so it is thread-safe and cheap). Timeout puts the tracker in `paused` and unbinds the camera.
- **Battery guard:** battery saver limits analysis to about 15 fps; thermal status `SEVERE` or worse pauses analysis (API 29+).

## Phase 2 — Transfer

- **Protocol extended deliberately** (see `PROTOCOL.md`): `sha256` in OFFER so the receiver can
  verify before saving, a `file` header to map FILE payloads to offer indexes, and a 16 KB
  thumbnail cap so OFFER fits Nearby's 32 KB BYTES limit.
- **Transport interface in domain** (`NearbyTransport` plus `TransportEvent`). The production class
  wraps `ConnectionsClient`; `FakeNearbyNetwork` links fake transports in memory and runs messages
  through the real codec, so the two-phone flow is tested on the JVM.
- **Saving without storage permissions.** Android 10+ uses MediaStore with `IS_PENDING` while
  hashing (images → `Pictures/Kaff`, videos → `Movies/Kaff`, others → `Download/Kaff`). Android
  8–9 cannot write to shared storage without `WRITE_EXTERNAL_STORAGE`, which the brief forbids, so
  files land in the app's external folder and open through a `FileProvider`. The Done screen says
  so ("saved to app files").
- **Video goes to `Movies/Kaff`**, not `Download/Kaff`, so it shows up in the gallery like photos.
- **No distance in meters.** Nearby does not report distance; the peer card says "nearby" instead
  of inventing a number.
- **History stores metadata only** (name, size, type, peer, result, duration), capped at 500 rows.
- **No custom Result wrapper.** Each boundary returns the most specific type (`Boolean`, `Long?`,
  `SaveResult`), which reads better than a generic wrapper.

## Phase 3 — Gesture-driven flow

- **Explicit reducers** (`SendReducer`, `ReceiveReducer`): pure `(state, event) → (state, effects)`
  functions with exhaustive `when` over sealed states. Effects run in `SessionRunner`, which
  processes events strictly in order. There are 59 reducer tests plus 10 end-to-end flow tests.
- **Pairing is the security gate.** The receiver calls `acceptConnection` only after the user
  confirms the digits, so before that nothing can be received. Gestures and Accept/Decline are
  ignored outside `Offered`, and a ViewModel test proves a palm during pairing does nothing.
- **Connection persists across attempts within a session, but never idles.** The brief asks to
  always disconnect after completion or timeout. Disconnecting after every armed timeout would
  force a new digit check for each retry. So: completion, failure and leaving the screen always
  disconnect; an armed timeout keeps the confirmed link for a new fist; a paired link with no
  activity is dropped after 90 s.
- **Palm hold is measured in camera time** (MediaPipe timestamps), so a dropped frame or a slow
  UI cannot fake a hold.
- **One role at a time.** Opening Send stops the receive session (and with it Standby
  advertising); Standby resumes once both roles are idle.
- **Auto-connect** to the first discovered phone, with chips to switch while connecting.

## Phase 4 — Smart wake

- **Standby = foreground service `connectedDevice`** with an ongoing notification and a Stop
  action. It only advertises and listens to the accelerometer at `SENSOR_DELAY_NORMAL`; it never
  opens the camera.
- **Shake in the background.** Android blocks background activity starts. If the user granted
  "display over other apps" (the bubble permission), a shake opens the receive screen directly;
  otherwise it posts a heads-up notification to tap. Full-screen intents are not used: Android 14
  reserves them for calls and alarms.
- **Floating bubble = foreground service `specialUse`**, with the subtype declared in the manifest.
  Tapping opens **Receive**, because that screen needs no file picking and the camera starts as
  soon as an offer arrives.
- **Two Quick Settings tiles** (Send and Receive), using `startActivityAndCollapse(PendingIntent)`
  on Android 14+. On Android 13+, Settings offers the system "add tile" prompt.
- **Services restart only from the foreground.** Opt-in services are restored when the app opens;
  starting a foreground service from the background is refused on Android 12+, and the code
  handles that instead of working around it.
- **Heads-up only when not visible.** `IncomingNotifier` checks `ProcessLifecycleOwner` and posts
  to an `IMPORTANCE_HIGH` channel only when Kaff is in the background, with a 60 s timeout.

## Phase 5 — Polish

- **Motion:** spring lift-off with tilt and a particle trail when the fist lands, pulse rings with
  a hold arc that fills during the palm hold, blur-to-sharp scale for the received file (blur needs
  Android 12+; older versions get scale and fade), a shared hero hand between Home and Receive, and
  a breathing open-to-fist hero on Home. Everything falls back to static or fade when the system
  animator scale is 0.
- **Haptics:** heavy click on grab, click on accept, double click on attention, and success/error
  waveforms. An optional chime uses `ToneGenerator`, so no audio asset is bundled.
- **Accessibility:** every icon button has a label, state changes are polite live regions, section
  titles are headings, pairing digits are read one by one, touch targets are ≥ 48 dp, and the
  hero and settings screens scroll at large font scales.

## Phase 6 — Release

- **R8 verified** on `assembleRelease`. MediaPipe and protobuf are kept broadly; it costs dex size
  but avoids runtime reflection crashes that cannot be caught without a device.
- **App size:** the universal APK is about 57 MB (MediaPipe ships native code for four ABIs). Ship
  an App Bundle (`bundleRelease`) so each phone downloads only its ABI (about 25 MB).
- **Release signing** uses the debug key so the release build can be installed for testing.
  Replace it before publishing.
- **CI** runs build, unit tests, lint, compiles the instrumented tests, and builds the R8 release
  on every push and pull request.

## Needs device testing

These cannot be verified without real phones.

- [ ] Gesture Lab: a fist and an open palm confirm within about 0.3 s and do not flicker at 30–60 cm.
- [ ] Handedness label is correct for both hands with the front camera (Lab shows "Hand: Left/Right").
- [ ] "Palm facing camera" is *yes* for the palm and *no* for the back of the hand. If it is inverted on a device, the MediaPipe handedness convention differs and `strictPalmCheck` must be off.
- [ ] Skeleton overlay lines up with the preview on phones whose preview aspect is not 4:3.
- [ ] GPU delegate works; on devices where it fails, the CPU fallback still reaches ≥ 15 fps.
- [ ] Camera turns off after the configured timeout and wakes on tap.

### Two-phone checklist

1. Install on two phones (Android 8+, Google Play services). Grant permissions on both.
2. Phone B: **Receive**. Phone A: **Start sending** → pick a photo.
3. Both screens show the **same 4 digits**. B taps "code matches"; A shows "connected".
4. A: make a **fist** at the front camera → the file lifts, haptic plays, 5 s countdown starts.
5. B: the offer card appears with a thumbnail. **Open palm** for about 0.7 s → the hold arc
   fills → transfer starts.
6. B lands on Done: photo is in `Pictures/Kaff`, "Open photo" works, speed and time are shown.
   A shows "delivered".
7. Repeat with a 200 MB video and with 3 mixed files (one PDF) → video in `Movies/Kaff`, PDF in
   `Download/Kaff`.
8. Let the 5 s window expire → A shows "time ran out", B shows "offer expired"; a new fist works
   without re-pairing.
9. Decline on B → A shows "declined". Decline the pairing → A goes back to searching.
10. Turn on airplane mode on A mid-transfer → both show a disconnect error; nothing partial in
    the gallery.
11. Standby on B, app in background: A sends → B gets a heads-up notification → tap → receive
    screen → palm.
12. Quick Settings tiles, bubble tap, and shake (with and without the overlay permission).
13. Battery saver on → Lab FPS drops to about 15. Hot phone (thermal SEVERE) → "phone is hot".
14. Leave any screen mid-flow → no lingering Nearby connection (B is discoverable again only if
    Standby is on).
15. Instrumented UI tests: `./gradlew connectedDebugAndroidTest` (no device was available while
    building, so they were compiled but not run).
