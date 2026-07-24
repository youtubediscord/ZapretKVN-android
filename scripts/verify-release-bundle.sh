#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:?release tag is required}"
BUNDLE_DIR="${2:?release bundle directory is required}"
RELEASE_ABIS=(arm64-v8a armeabi-v7a x86_64)

# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/release.properties"
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

if [[ "$ZAPRET_RELEASE_CHANNEL" != stable || "$ZAPRET_PRERELEASE" != false ]]; then
    echo "Only a stable vMAJOR.MINOR.PATCH bundle can be verified here" >&2
    exit 1
fi
if [[ ! "$RELEASE_SIGNER_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid public release signer fingerprint" >&2
    exit 1
fi
if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
    export ANDROID_HOME
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
AAPT2="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
for command in jq sha256sum stat unzip; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done
[[ -x "$AAPT2" && -x "$APKSIGNER" && -d "$BUNDLE_DIR" ]]

EXPECTED_FILES=(release-metadata-v2.json release-metadata.json)
for abi in "${RELEASE_ABIS[@]}"; do
    EXPECTED_FILES+=(
        "Zapret-KVN-$TAG-$abi.apk"
        "Zapret-KVN-$TAG-$abi.apk.sha256"
    )
done
mapfile -t EXPECTED_FILES < <(printf '%s\n' "${EXPECTED_FILES[@]}" | sort)
mapfile -t ACTUAL_FILES < <(find "$BUNDLE_DIR" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | sort)
if [[ "${ACTUAL_FILES[*]}" != "${EXPECTED_FILES[*]}" ]]; then
    echo "Published release asset set differs from the required eight files" >&2
    printf 'Expected: %s\nActual: %s\n' "${EXPECTED_FILES[*]}" "${ACTUAL_FILES[*]}" >&2
    exit 1
fi

METADATA_V2="$BUNDLE_DIR/release-metadata-v2.json"
METADATA_V1="$BUNDLE_DIR/release-metadata.json"
jq -e \
    --arg version_name "$ZAPRET_VERSION_NAME" \
    --argjson version_code "$ZAPRET_VERSION_CODE" \
    --arg core_tag "$CORE_TAG" \
    --arg core_commit "$CORE_COMMIT" \
    --arg core_patch_sha256 "$CORE_PATCH_SHA256" \
    --arg signer_sha256 "$RELEASE_SIGNER_SHA256" \
    '
        .schema == 2
        and .version_name == $version_name
        and .version_code == $version_code
        and .application_id == "io.github.zapretkvn.android"
        and .core_tag == $core_tag
        and .core_commit == $core_commit
        and .core_patch_sha256 == $core_patch_sha256
        and .signer_sha256 == $signer_sha256
        and (.artifacts | length) == 3
        and ([.artifacts[].abi] | sort) == ["arm64-v8a", "armeabi-v7a", "x86_64"]
    ' "$METADATA_V2" >/dev/null

for abi in "${RELEASE_ABIS[@]}"; do
    apk_name="Zapret-KVN-$TAG-$abi.apk"
    apk="$BUNDLE_DIR/$apk_name"
    checksum_file="$apk.sha256"
    apk_sha256="$(sha256sum "$apk" | awk '{print $1}')"
    apk_size="$(stat -c '%s' "$apk")"

    read -r recorded_sha256 recorded_name extra < "$checksum_file"
    if [[ "$recorded_sha256" != "$apk_sha256" || "$recorded_name" != "$apk_name" || -n "${extra:-}" ]]; then
        echo "Invalid checksum file for $apk_name" >&2
        exit 1
    fi

    jq -e \
        --arg abi "$abi" \
        --arg apk_file "$apk_name" \
        --arg apk_sha256 "$apk_sha256" \
        --argjson apk_size "$apk_size" \
        '
            [.artifacts[] | select(
                .abi == $abi
                and .apk_file == $apk_file
                and .apk_sha256 == $apk_sha256
                and .apk_size == $apk_size
            )] | length == 1
        ' "$METADATA_V2" >/dev/null

    mapfile -t native_abis < <(
        unzip -Z1 "$apk" \
            | sed -n 's#^lib/\([^/]*\)/.*\.so$#\1#p' \
            | sort -u
    )
    if [[ "${#native_abis[@]}" -ne 1 || "${native_abis[0]}" != "$abi" ]]; then
        echo "Unexpected native ABI set in $apk_name: ${native_abis[*]:-none}" >&2
        exit 1
    fi
    unzip -Z1 "$apk" | grep -Fx "lib/$abi/libbox.so" >/dev/null

    badging="$("$AAPT2" dump badging "$apk" | sed -n '1p')"
    [[ "$badging" == *"name='io.github.zapretkvn.android'"* ]]
    [[ "$badging" == *"versionCode='$ZAPRET_VERSION_CODE'"* ]]
    [[ "$badging" == *"versionName='$ZAPRET_VERSION_NAME'"* ]]

    signature_info="$("$APKSIGNER" verify --verbose --print-certs "$apk")"
    mapfile -t signer_digests < <(
        sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' <<<"$signature_info"
    )
    [[ "${#signer_digests[@]}" -eq 1 ]]
    signer="$(tr '[:upper:]' '[:lower:]' <<<"${signer_digests[0]}" | tr -d ':[:space:]')"
    if [[ "$signer" != "$RELEASE_SIGNER_SHA256" ]]; then
        echo "Production signer mismatch in $apk_name" >&2
        exit 1
    fi
done

arm64_name="Zapret-KVN-$TAG-arm64-v8a.apk"
arm64_sha256="$(sha256sum "$BUNDLE_DIR/$arm64_name" | awk '{print $1}')"
arm64_size="$(stat -c '%s' "$BUNDLE_DIR/$arm64_name")"
jq -e \
    --arg version_name "$ZAPRET_VERSION_NAME" \
    --argjson version_code "$ZAPRET_VERSION_CODE" \
    --arg core_tag "$CORE_TAG" \
    --arg core_commit "$CORE_COMMIT" \
    --arg core_patch_sha256 "$CORE_PATCH_SHA256" \
    --arg signer_sha256 "$RELEASE_SIGNER_SHA256" \
    --arg apk_file "$arm64_name" \
    --arg apk_sha256 "$arm64_sha256" \
    --argjson apk_size "$arm64_size" \
    '
        .schema == 1
        and .version_name == $version_name
        and .version_code == $version_code
        and .application_id == "io.github.zapretkvn.android"
        and .core_tag == $core_tag
        and .core_commit == $core_commit
        and .core_patch_sha256 == $core_patch_sha256
        and .signer_sha256 == $signer_sha256
        and .abi == ["arm64-v8a"]
        and .apk_file == $apk_file
        and .apk_sha256 == $apk_sha256
        and .apk_size == $apk_size
    ' "$METADATA_V1" >/dev/null

echo "Stable release bundle verified: $TAG"
