#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:?release tag is required}"
SOURCE_APK="${2:?signed release APK is required}"
OUTPUT_DIR="${3:-$PROJECT_ROOT/release-out}"

# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
while IFS='=' read -r name value; do
    case "$name" in
        ZAPRET_VERSION_NAME|ZAPRET_VERSION_CODE|ZAPRET_PRERELEASE|ZAPRET_RELEASE_CHANNEL)
            printf -v "$name" '%s' "$value"
            ;;
        *)
            echo "Unexpected release variable: $name" >&2
            exit 1
            ;;
    esac
done < <("$PROJECT_ROOT/scripts/derive-release-version.sh" "$TAG")

if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
    export ANDROID_HOME
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
AAPT2="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
[[ -x "$AAPT2" && -x "$APKSIGNER" && -f "$SOURCE_APK" ]]

SIGNATURE_INFO="$("$APKSIGNER" verify --verbose --print-certs "$SOURCE_APK")"
mapfile -t SIGNER_DIGESTS < <(
    sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' <<<"$SIGNATURE_INFO"
)
[[ "${#SIGNER_DIGESTS[@]}" -eq 1 ]]
SIGNER_SHA256="$(tr '[:upper:]' '[:lower:]' <<<"${SIGNER_DIGESTS[0]}" | tr -d ':[:space:]')"
[[ "$SIGNER_SHA256" =~ ^[0-9a-f]{64}$ ]]
if [[ -n "${ZAPRET_EXPECTED_SIGNER_SHA256:-}" ]]; then
    EXPECTED_SIGNER="$(tr '[:upper:]' '[:lower:]' <<<"$ZAPRET_EXPECTED_SIGNER_SHA256" | tr -d ':[:space:]')"
    [[ "$EXPECTED_SIGNER" =~ ^[0-9a-f]{64}$ && "$SIGNER_SHA256" == "$EXPECTED_SIGNER" ]]
fi
BADGING="$($AAPT2 dump badging "$SOURCE_APK" | sed -n '1p')"
[[ "$BADGING" == *"name='io.github.zapretkvn.android'"* ]]
[[ "$BADGING" == *"versionCode='$ZAPRET_VERSION_CODE'"* ]]
[[ "$BADGING" == *"versionName='$ZAPRET_VERSION_NAME'"* ]]

if [[ -d "$OUTPUT_DIR" && -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "Release output directory must be empty: $OUTPUT_DIR" >&2
    exit 1
fi
mkdir -p "$OUTPUT_DIR"
APK_NAME="Zapret-KVN-$TAG-arm64-v8a.apk"
cp "$SOURCE_APK" "$OUTPUT_DIR/$APK_NAME"
APK_SHA256="$(sha256sum "$OUTPUT_DIR/$APK_NAME" | awk '{print $1}')"
APK_SIZE="$(stat -c '%s' "$OUTPUT_DIR/$APK_NAME")"
printf '%s  %s\n' "$APK_SHA256" "$APK_NAME" > "$OUTPUT_DIR/$APK_NAME.sha256"

printf '%s\n' \
    '{' \
    '  "schema": 1,' \
    "  \"version_name\": \"$ZAPRET_VERSION_NAME\"," \
    "  \"version_code\": $ZAPRET_VERSION_CODE," \
    '  "application_id": "io.github.zapretkvn.android",' \
    "  \"core_tag\": \"$CORE_TAG\"," \
    "  \"core_commit\": \"$CORE_COMMIT\"," \
    "  \"signer_sha256\": \"$SIGNER_SHA256\"," \
    '  "abi": ["arm64-v8a"],' \
    "  \"apk_file\": \"$APK_NAME\"," \
    "  \"apk_sha256\": \"$APK_SHA256\"," \
    "  \"apk_size\": $APK_SIZE" \
    '}' > "$OUTPUT_DIR/release-metadata.json"

printf '%s\n' \
    "## Zapret KVN $ZAPRET_VERSION_NAME" \
    '' \
    "- Канал: $ZAPRET_RELEASE_CHANNEL" \
    '- Android package: io.github.zapretkvn.android' \
    '- ABI: arm64-v8a' \
    "- Core tag: \`$CORE_TAG\`" \
    "- Core commit: \`$CORE_COMMIT\`" \
    "- Signing certificate SHA-256: \`$SIGNER_SHA256\`" \
    "- APK SHA-256: \`$APK_SHA256\`" \
    '' \
    'APK содержит libbox; отдельная динамическая загрузка ядра не используется.' \
    '' \
    '### Известные ограничения MVP' \
    '' \
    '- Android 8+; release APK содержит только arm64-v8a, интерфейс рассчитан на телефоны.' \
    '- Always-on/Lockdown и shared UID не поддерживаются как гарантированный per-app режим.' \
    '- Managed DNS перехватывает TCP/UDP 53, но не встроенный DoH, DoT или mDNS; FakeIP выключен.' \
    '- Domain-only block не является firewall: для гарантии нужен IP/CIDR rule-set.' \
    '- Clash YAML и Hysteria v1 URI пока не импортируются; raw JSON остаётся ответственностью пользователя.' \
    '- Подписки, core и APK обновляются только вручную; silent install и фоновая синхронизация отсутствуют.' \
    '- При неработающем proxy/DNS VPN закрывается без бесконечного retry и plaintext DNS fallback.' \
    > "$OUTPUT_DIR/RELEASE_NOTES.md"

echo "Release bundle created in $OUTPUT_DIR"
