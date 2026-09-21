#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_root"
delivery="$project_root/dist"
debug_apk="app/build/outputs/apk/debug/app-debug.apk"
release_apk="app/build/outputs/apk/release/app-release-unsigned.apk"
sample_apk="sample-plugin/build/outputs/apk/debug/sample-plugin-debug.apk"
netease_apk="netease-plugin/build/outputs/apk/debug/netease-plugin-debug.apk"

for artifact in "$debug_apk" "$release_apk" "$sample_apk" "$netease_apk"; do
    test -s "$artifact" || { echo "Missing artifact: $artifact" >&2; exit 1; }
done
mkdir -p -- "$delivery"
cp -- "$debug_apk" "$delivery/easepod-0.1.0-debug.apk"
cp -- "$release_apk" "$delivery/easepod-0.1.0-release-unsigned.apk"
cp -- "$sample_apk" "$delivery/easepod-sample-plugin-0.1.0-debug.apk"
cp -- "$netease_apk" "$delivery/easepod-netease-plugin-0.1.0-debug.apk"
cp -- LICENSE "$delivery/LICENSE"
cp -- DELIVERY.md "$delivery/README.md"

# Include build inputs and verification sources; machine state and signing keys are never source inputs.
tar --sort=name --mtime='UTC 2026-09-08' --owner=0 --group=0 --numeric-owner \
    --exclude='./dist' --exclude='./.gradle' --exclude='./.kotlin' --exclude='./.tools' \
    --exclude='./.git' --exclude='./.idea' --exclude='*/build' --exclude='*/captures' \
    --exclude='./local.properties' --exclude='*.jks' --exclude='*.keystore' \
    --exclude='./gradle-*-bin.zip' --exclude='./verification/device' \
    -cf - . | gzip -n > "$delivery/easepod-0.1.0-source.tar.gz"
cd -- "$delivery"
sha256sum easepod-0.1.0-debug.apk easepod-0.1.0-release-unsigned.apk \
    easepod-sample-plugin-0.1.0-debug.apk easepod-netease-plugin-0.1.0-debug.apk \
    easepod-0.1.0-source.tar.gz LICENSE README.md > SHA256SUMS
echo "$delivery"
