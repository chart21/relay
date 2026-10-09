#!/usr/bin/env bash
# Download the "Hey Jarvis" wake word models (openWakeWord v0.5.1 release) into the Android assets.
# They are CC BY-NC-SA 4.0 (non-commercial) and therefore not part of this repository; see NOTICE.
# Run once before building the app. Without them the app works, only the wake word reports "models missing".
set -euo pipefail
DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/android/app/src/main/assets/wake"
BASE="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
mkdir -p "$DEST"
declare -A SUM=(
  [melspectrogram]=ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f
  [embedding_model]=70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f
  [hey_jarvis_v0.1]=94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb
)
for name in melspectrogram embedding_model hey_jarvis_v0.1; do
  f="$DEST/$name.onnx"
  if [ -f "$f" ] && [ "$(sha256sum "$f" | cut -d' ' -f1)" = "${SUM[$name]}" ]; then echo "ok      $name.onnx"; continue; fi
  echo "fetch   $name.onnx"
  curl -fL --retry 3 -o "$f.part" "$BASE/$name.onnx"
  got="$(sha256sum "$f.part" | cut -d' ' -f1)"
  if [ "$got" != "${SUM[$name]}" ]; then rm -f "$f.part"; echo "checksum mismatch for $name.onnx ($got)" >&2; exit 1; fi
  mv "$f.part" "$f"
done
echo "Done. Models are CC BY-NC-SA 4.0 (non-commercial); keep the attribution in NOTICE if you redistribute a build."
