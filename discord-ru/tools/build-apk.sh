#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 4 ]]; then
    echo "Usage: $0 DISCORD_SPLITS_DIR APKEDITOR_JAR KEYSTORE OUTPUT_APK" >&2
    exit 2
fi

input=$(realpath "$1")
editor=$(realpath "$2")
keystore=$(realpath -m "$3")
output=$(realpath -m "$4")
root=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}
tools="$sdk/build-tools/35.0.0"
gradle=${GRADLE_BIN:-gradle}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

shopt -s nullglob
splits=("$input"/*.apk)
if [[ ${#splits[@]} -eq 0 ]]; then
    echo "No Discord APK files in $input" >&2
    exit 2
fi
official_cert=3c39d23cf9367849a5c699395647fe0e5bfea5a1f1f40d8c717ddc70f8bfa113
for split in "${splits[@]}"; do
    certificate=$("$tools/apksigner" verify --print-certs "$split")
    if ! grep -Fxq "Signer #1 certificate SHA-256 digest: $official_cert" <<< "$certificate"; then
        echo "Discord signing certificate does not match: $split" >&2
        exit 2
    fi
done

"$root/tools/build-native.sh"
"$root/tools/build-settings.sh"
(cd "$root" && "$gradle" :app:assembleRelease)
payload="$root/app/build/outputs/apk/release/app-release-unsigned.apk"
java -jar "$editor" m -i "$input" -o "$work/host.apk"
java -Xmx2g --class-path "$editor" "$root/tools/PatchPayload.java" \
    "$work/host.apk" "$payload" "$work"
python3 "$root/tools/pack.py" "$work/host.apk" "$payload" "$work" "$work/unsigned.apk"
"$tools/zipalign" -P 16 -f 4 "$work/unsigned.apk" "$work/aligned.apk"
if [[ ! -f "$keystore" ]]; then
    mkdir -p "$(dirname "$keystore")"
    keytool -genkeypair -keystore "$keystore" -alias discordru -keyalg RSA \
        -keysize 3072 -validity 10000 -dname "CN=Discord RU Local Build"
fi
mkdir -p "$(dirname "$output")"
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias discordru \
    --v4-signing-enabled false --out "$output" "$work/aligned.apk"
"$tools/apksigner" verify --verbose "$output"
"$tools/zipalign" -c -P 16 4 "$output"
