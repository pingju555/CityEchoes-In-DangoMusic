#!/usr/bin/env bash
#
# Fetch the three build-time dependency jars into libs/.
#
# The jars are not committed (see .gitignore), so a fresh clone cannot build
# until they are in place. This script downloads them from their upstream
# sources and checks their SHA-256.
#
#   ./tools/fetch-libs.sh                 fetch what is missing, verify all
#   ./tools/fetch-libs.sh --force         re-download even if the file exists
#   ./tools/fetch-libs.sh --verify-only   no network, just check libs/
#   ./tools/fetch-libs.sh --strict        any checksum surprise becomes fatal
#
# Environment overrides:
#   DANGO_VERSION   DangoMusic release, default 1.7.3
#   MIXIN_VERSION   mixin-patched version, default 0.8.5.12
#   LIBS_DIR        output directory, default <repo>/libs
#
# Requires: bash 4+, curl, sha256sum (or shasum on macOS).

set -euo pipefail

DANGO_VERSION="${DANGO_VERSION:-1.7.3}"
MIXIN_VERSION="${MIXIN_VERSION:-0.8.5.12}"

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SELF_DIR/.." && pwd)"
LIBS_DIR="${LIBS_DIR:-$ROOT_DIR/libs}"

FORGE_JAR="DangoMusic-$DANGO_VERSION+1.20.1-forge.jar"
NEO_JAR="DangoMusic-$DANGO_VERSION+1.21.1-neoforge.jar"
MIXIN_JAR="mixin-patched-$MIXIN_VERSION.jar"

# Known-good DangoMusic 1.7.3 builds. The upstream author re-uploaded the release
# assets after the first publication (the rebuild touched NeteaseApi, GlassBackdrop
# and the zh_cn translations), so more than one hash is legitimate for the same
# version. The six classes this addon injects into are byte-identical in both, so
# either build works -- hence an unknown hash is a warning, not a failure.
KNOWN_FORGE="40044c8dbcdc307082fa267309f50272f98f4f6ebbf0ddbfc043bdc2e8dd81b6
403c665f4f8bc02594bf891cf708bab33c0a0c69c97baf9607d70ecb2203360a"
KNOWN_NEO="789e32b7bcfa54c5e8514106af20f703a17b5c9f8a0264a642c12dbac1f60873
18d1a08582df3cf998141832e31937be39281aa951f33abcb333bc8779602dc0"

# mixin-patched comes from Architectury's own maven and never changes under a
# released version, so its hash is authoritative.
SHA_MIXIN="936d515ac41de42f5c0c6bb0fedaa470c9c404cae321f730a5c060d5721d41ca"

FORCE=0
VERIFY_ONLY=0
STRICT=0

usage() {
    sed -n '3,21p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        -f|--force) FORCE=1 ;;
        -V|--verify-only) VERIFY_ONLY=1 ;;
        -s|--strict) STRICT=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
    esac
done

# ---------------------------------------------------------------- helpers

say()  { printf '%s\n' "$*"; }
warn() { printf '%s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        die "need sha256sum or shasum to verify downloads"
    fi
}

# curl on Windows uses Schannel, which refuses to proceed when the CRL/OCSP
# responder is unreachable -- common behind corporate proxies. Only Schannel
# builds understand --ssl-no-revoke, so probe for it instead of assuming.
CURL_TLS=()
if curl --ssl-no-revoke --version >/dev/null 2>&1; then
    CURL_TLS=(--ssl-no-revoke)
fi

url_dango() {
    # GitHub asset names contain '+', which must be percent-encoded.
    local name="$1"
    printf 'https://github.com/Sorihiko/DangoMusic/releases/download/v%s/%s' \
        "$DANGO_VERSION" "${name//+/%2B}"
}

url_mixin() {
    printf 'https://maven.architectury.dev/dev/architectury/mixin-patched/%s/mixin-patched-%s.jar' \
        "$MIXIN_VERSION" "$MIXIN_VERSION"
}

# expected_hashes <kind>  -> newline separated list, empty when unverifiable
expected_hashes() {
    case "$1" in
        forge) [ "$DANGO_VERSION" = "1.7.3" ] && printf '%s\n' "$KNOWN_FORGE" || true ;;
        neo)   [ "$DANGO_VERSION" = "1.7.3" ] && printf '%s\n' "$KNOWN_NEO"   || true ;;
        mixin) [ "$MIXIN_VERSION" = "0.8.5.12" ] && printf '%s\n' "$SHA_MIXIN" || true ;;
    esac
}

# check_jar <path> <kind> <label> <fatal-on-unknown: 0|1>
# 0 = present and trusted, 1 = missing or broken
check_jar() {
    local path="$1" kind="$2" label="$3" fatal="$4"
    local actual expected hit=0

    if [ ! -f "$path" ]; then warn "  $label: missing"; return 1; fi
    if [ ! -s "$path" ]; then warn "  $label: empty file"; return 1; fi

    actual="$(sha256_of "$path")"
    expected="$(expected_hashes "$kind")"

    if [ -z "$expected" ]; then
        say "  $label: $actual"
        say "    no reference checksum for this version, accepted as-is"
        return 0
    fi

    for h in $expected; do
        if [ "$actual" = "$h" ]; then hit=1; break; fi
    done

    if [ "$hit" -eq 1 ]; then
        say "  $label: ok"
        return 0
    fi

    if [ "$fatal" -eq 1 ] || [ "$STRICT" -eq 1 ]; then
        warn "  $label: checksum mismatch"
        warn "    actual   $actual"
        warn "    expected one of:"
        for h in $expected; do warn "      $h"; done
        return 1
    fi

    # A different build of the same version. Upstream did re-upload 1.7.3 once.
    warn "  $label: checksum differs from the builds this project was tested against"
    warn "    actual $actual"
    warn "    This is usually a re-uploaded upstream asset. The injection targets are"
    warn "    unchanged in the known rebuilds, but verify in game before shipping."
    return 0
}

# fetch <url> <dest> <kind> <label>
fetch() {
    local url="$1" dest="$2" kind="$3" label="$4"
    local part="$dest.part"

    if [ -f "$dest" ] && [ "$FORCE" -eq 0 ]; then
        if check_jar "$dest" "$kind" "$label" 0; then
            say "    already present, skipped"
            return 0
        fi
        warn "    existing file cannot be used, re-downloading"
    fi

    say "    GET $url"
    rm -f "$part"
    # -sS: no progress meter, keep error text; -f: fail on 4xx/5xx instead of
    # saving an error page as if it were a jar.
    if ! curl -sS -fL --retry 3 --retry-delay 2 --connect-timeout 20 \
              "${CURL_TLS[@]}" -o "$part" "$url"; then
        rm -f "$part"
        die "download failed: $url"
    fi

    if [ ! -s "$part" ]; then
        rm -f "$part"
        die "downloaded file is empty: $url"
    fi

    # A jar that does not start with the ZIP magic means an HTML error page or a
    # captive portal answered instead of the file.
    local magic
    magic="$(head -c 2 "$part" || true)"
    if [ "$magic" != "PK" ]; then
        rm -f "$part"
        die "not a jar (no PK header), the response was probably an error page: $url"
    fi

    if ! check_jar "$part" "$kind" "$label" 0; then
        rm -f "$part"
        die "downloaded file failed verification: $url"
    fi

    mv -f "$part" "$dest"
    say "    saved to $dest"
}

# ---------------------------------------------------------------- checks

command -v curl >/dev/null 2>&1 || die "curl is required"

if [ "$DANGO_VERSION" != "1.7.3" ]; then
    warn "note: DangoMusic $DANGO_VERSION is not the version this project was tested"
    warn "      against; its checksums are not enforced. The injection points may"
    warn "      not line up -- check logs/latest.log for [CityEchoes] afterwards."
fi

# ---------------------------------------------------------------- run

say "libs directory: $LIBS_DIR"

if [ "$VERIFY_ONLY" -eq 1 ]; then
    say "verify only:"
    failed=0
    check_jar "$LIBS_DIR/$FORGE_JAR" forge "$FORGE_JAR" 0 || failed=1
    check_jar "$LIBS_DIR/$NEO_JAR"   neo   "$NEO_JAR"   0 || failed=1
    check_jar "$LIBS_DIR/$MIXIN_JAR" mixin "$MIXIN_JAR" 1 || failed=1
    if [ "$failed" -ne 0 ]; then
        die "libs/ is incomplete or has been modified"
    fi
    say "all three jars present"
    exit 0
fi

mkdir -p "$LIBS_DIR"

say "[1/3] $FORGE_JAR"
fetch "$(url_dango "$FORGE_JAR")" "$LIBS_DIR/$FORGE_JAR" forge "$FORGE_JAR"

say "[2/3] $NEO_JAR"
fetch "$(url_dango "$NEO_JAR")" "$LIBS_DIR/$NEO_JAR" neo "$NEO_JAR"

say "[3/3] $MIXIN_JAR"
fetch "$(url_mixin)" "$LIBS_DIR/$MIXIN_JAR" mixin "$MIXIN_JAR"

say ""
say "done. next: ./gradlew build"
say "  (or ./gradlew build --init-script init.gradle.kts on a restricted network)"
