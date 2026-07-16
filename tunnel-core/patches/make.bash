#!/usr/bin/env bash
# =============================================================================
# Optimized make.bash for psiphon-android open-source builds
# Changes from upstream:
#   1. arm64-v8a only  (-target=android/arm64)   → ~75% smaller .so
#   2. -trimpath                                   → removes build paths from binary
#   3. GOFLAGS=-trimpath passed to gomobile
# =============================================================================

set -e -u -x

if [ ! -f make.bash ]; then
  echo "make.bash must be run from the MobileLibrary/Android directory"
  exit 1
fi

# $1, if specified, is go build tags
if [ -z ${1+x} ]; then BUILD_TAGS=""; else BUILD_TAGS="$1"; fi

# psiphon-tunnel-core uses GOPATH (not modules) mode
export GO111MODULE=off
export GOCACHE=/tmp

# Strip build paths from binary (privacy + size)
export GOFLAGS="-trimpath"

BUILDINFOFILE="psiphon-tunnel-core_buildinfo.txt"
BUILDDATE=$(date --iso-8601=seconds)
BUILDREPO="https://github.com/Psiphon-Labs/psiphon-tunnel-core.git"
BUILDREV=$(git rev-parse --short=10 HEAD)
GOVERSION=$(go version | perl -ne '/go version (.*?) / && print $1')

# 16KB page size required for Android 15 compatibility
export CGO_LDFLAGS="${CGO_LDFLAGS:-} -Wl,-z,max-page-size=16384,-z,common-page-size=16384"

LDFLAGS="\
-checklinkname=0 \
-s \
-w \
-X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildDate=$BUILDDATE \
-X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildRepo=$BUILDREPO \
-X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildRev=$BUILDREV \
-X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.goVersion=$GOVERSION \
-extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384 \
"

echo -e "${BUILDDATE}\n${BUILDREPO}\n${BUILDREV}\n" > $BUILDINFOFILE
echo "Build info: date=$BUILDDATE rev=$BUILDREV go=$GOVERSION"

# ── Build: arm64 ONLY, API 21+ (upstream builds arm,arm64,386,amd64) ─────────
gomobile bind -v -x \
  -target=android/arm64 \
  -androidapi 21 \
  -tags="${BUILD_TAGS}" \
  -ldflags="$LDFLAGS" \
  github.com/Psiphon-Labs/psiphon-tunnel-core/MobileLibrary/psi

if [ $? != 0 ]; then
  echo "gomobile bind failed, exiting"
  exit 1
fi

# ── Package: merge with PsiphonTunnel Java wrapper ───────────────────────────
mkdir -p build-tmp/psi
unzip -o psi.aar -d build-tmp/psi
yes | cp -f PsiphonTunnel/AndroidManifest.xml build-tmp/psi/AndroidManifest.xml
mkdir -p build-tmp/psi/res/xml
yes | cp -f PsiphonTunnel/ca_psiphon_psiphontunnel_backup_rules.xml \
           build-tmp/psi/res/xml/ca_psiphon_psiphontunnel_backup_rules.xml

javac -d build-tmp \
  -bootclasspath "$ANDROID_HOME/platforms/android-${ANDROID_PLATFORM_VERSION}/android.jar" \
  -source 1.8 -target 1.8 \
  -classpath build-tmp/psi/classes.jar \
  PsiphonTunnel/PsiphonTunnel.java

if [ $? != 0 ]; then
  echo "javac PsiphonTunnel failed, exiting"
  exit 1
fi

cd build-tmp
jar uf psi/classes.jar ca/psiphon/*.class
cd -

cd build-tmp/psi
echo -e "-keep class psi.** { *; }\n-keep class ca.psiphon.** { *; }\n" >> proguard.txt
rm -f ../../ca.psiphon.aar
zip -r ../../ca.psiphon.aar ./
cd -

rm -rf build-tmp
echo "Done — ca.psiphon.aar built (arm64-v8a only)"
