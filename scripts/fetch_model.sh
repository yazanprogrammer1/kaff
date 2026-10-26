#!/usr/bin/env sh
# Downloads the MediaPipe hand landmarker model into the app assets.
# Source: Google's official MediaPipe model storage, documented at
# https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker#models
set -eu

MODEL_URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT_DIR/app/src/main/assets/hand_landmarker.task"

if [ -s "$TARGET" ] && [ "${1:-}" != "--force" ]; then
  echo "Model already present: $TARGET (use --force to re-download)"
  exit 0
fi

mkdir -p "$(dirname "$TARGET")"
echo "Downloading $MODEL_URL"
curl -fsSL --retry 3 -o "$TARGET.part" "$MODEL_URL"
mv "$TARGET.part" "$TARGET"
echo "Saved $(wc -c < "$TARGET") bytes to $TARGET"
