#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Commit the reviewed lab source before staging an installation package." >&2
  exit 1
fi
git merge-base --is-ancestor d52101ad3d8940c5aa392d6f224e774cb6f5ce84 HEAD
: "${JAVA_HOME:?Point JAVA_HOME at a JDK; the build also requires the Java 17 toolchain.}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew :androidApp:assembleInspectorLab :androidApp:lintInspectorLab --no-daemon "$@"

inspector_apk="androidApp/build/outputs/apk/inspectorLab/androidApp-inspectorLab.apk"
inspector_revision="$(git rev-parse HEAD)"
inspector_stage="dist/inspector-lab-${inspector_revision:0:12}"
mkdir -p "$inspector_stage"
python3 tools/verify_inspector_apk.py "$inspector_apk" > "$inspector_stage/package.json"
cp "$inspector_apk" "$inspector_stage/pebble-inspector-lab.apk"
git rev-parse HEAD > "$inspector_stage/SOURCE_COMMIT.txt"
(
  cd "$inspector_stage"
  shasum -a 256 pebble-inspector-lab.apk > SHA256SUMS.txt
  shasum -a 256 -c SHA256SUMS.txt
)
echo "Staged $inspector_stage/pebble-inspector-lab.apk"
