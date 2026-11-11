#!/usr/bin/env bash
# Build the release-signed Signal Station APK for direct download.
#
# The signing values come from the environment, so run this through the
# release toolkit, which reads them from the login Keychain:
#
#   ~/workspace/android-release-toolkit/scripts/android-release \
#     with-signing --profile signal-station -- scripts/build-signal-release.sh
#
# Commit first: the APK records the source revision it was built from, and the
# published source archive is cut from that same commit.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
stage_dir="${repo_root}/dist/signal-station"

for name in SIGNAL_STATION_RELEASE_STORE_FILE SIGNAL_STATION_RELEASE_STORE_PASSWORD \
            SIGNAL_STATION_RELEASE_KEY_ALIAS SIGNAL_STATION_RELEASE_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing ${name}. Run this script through the toolkit's with-signing command." >&2
    exit 1
  fi
done

cd "${repo_root}"
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Commit the source before building a release." >&2
  exit 1
fi
revision="$(git rev-parse HEAD)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -z "${JAVA_HOME}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  fi
  export JAVA_HOME
fi
export ANDROID_HOME="${ANDROID_HOME:-${HOME}/Library/Android/sdk}"
export PATH="${JAVA_HOME}/bin:${PATH}"

./gradlew :signalApp:testDebugUnitTest :signalApp:lintRelease :signalApp:assembleRelease --console=plain

build_tools="$(ls -1d "${ANDROID_HOME}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
if [[ -z "${build_tools}" ]]; then
  echo "Android build-tools were not found." >&2
  exit 1
fi

built="signalApp/build/outputs/apk/release/signalApp-release.apk"
badging="$("${build_tools}/aapt" dump badging "${built}")"
version_name="$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<<"${badging}")"
version_code="$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" <<<"${badging}")"
if grep -q '^application-debuggable' <<<"${badging}"; then
  echo "The release APK is debuggable; refusing to stage it." >&2
  exit 1
fi
certificate="$("${build_tools}/apksigner" verify --print-certs "${built}" \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ -z "${certificate}" || "${certificate}" == e1faa235* ]]; then
  echo "The release APK is unsigned or signed with the Android debug key." >&2
  exit 1
fi
identity="$(unzip -p "${built}" assets/signal-build.txt)"
if [[ "${identity}" != *"${revision:0:8}"* ]]; then
  echo "The APK does not record the committed revision ${revision:0:8}." >&2
  exit 1
fi

for notice in assets/signal-station/LICENSE-vosk.txt assets/signal-station/WAKE_MODEL_NOTICE.txt; do
  if ! unzip -l "${built}" "${notice}" >/dev/null 2>&1; then
    echo "The APK is missing the speech notice ${notice}." >&2
    exit 1
  fi
done

mkdir -p "${stage_dir}"
find "${stage_dir}" -maxdepth 1 -type f -delete
apk="signal-station-${version_name}-${version_code}-${revision:0:8}.apk"
cp "${built}" "${stage_dir}/${apk}"
(cd "${stage_dir}" && shasum -a 256 "${apk}" > SHA256SUMS.txt)
printf 'Release certificate SHA-256: %s\nSource revision: %s\n' "${certificate}" "${revision}" \
  > "${stage_dir}/RELEASE-CERTIFICATE.txt"
echo "Signed Signal Station ${version_name} (${version_code}) staged in ${stage_dir}."
