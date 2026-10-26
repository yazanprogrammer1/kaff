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

## Needs device testing

These cannot be verified without real phones.

- [ ] Gesture Lab: a fist and an open palm confirm within about 0.3 s and do not flicker at 30–60 cm.
- [ ] Handedness label is correct for both hands with the front camera (Lab shows "Hand: Left/Right").
- [ ] "Palm facing camera" is *yes* for the palm and *no* for the back of the hand. If it is inverted on a device, the MediaPipe handedness convention differs and `strictPalmCheck` must be off.
- [ ] Skeleton overlay lines up with the preview on phones whose preview aspect is not 4:3.
- [ ] GPU delegate works; on devices where it fails, the CPU fallback still reaches ≥ 15 fps.
- [ ] Camera turns off after the configured timeout and wakes on tap.
