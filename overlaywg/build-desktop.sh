#!/bin/sh
# Build the desktop WireGuard bridge for the current host.
#
# Output: overlaywg/build/<os>-<arch>/liboverlaywg.so | liboverlaywg.dylib | overlaywg.dll
# The app finds that path directly in a development tree (OverlayWgLibrary's source fallback),
# or from `native/<os>/` inside a packaged install.
#
# Requirements: Go >= 1.26 and a C toolchain — gcc on Linux, Xcode command-line tools on macOS,
# mingw-w64 on Windows. This is the same Go source the Android AAR is built from; only
# `cshared/main.go` (the C ABI) is desktop-specific.
set -eu
cd "$(dirname "$0")"

GO="${GO:-go}"
if ! command -v "$GO" >/dev/null 2>&1; then
    echo "error: $GO not found; install Go 1.26 or set GO=/path/to/go" >&2
    exit 1
fi

os_name="$(uname -s)"
arch_name="$(uname -m)"
case "$os_name" in
    Linux)  os_key=linux ;;
    Darwin) os_key=macos ;;
    MINGW*|MSYS*|CYGWIN*) os_key=windows ;;
    *) echo "error: unsupported OS '$os_name'" >&2; exit 1 ;;
esac
case "$arch_name" in
    x86_64|amd64) arch_key=amd64 ;;
    arm64|aarch64) arch_key=arm64 ;;
    *) arch_key="$arch_name" ;;
esac
case "$os_key" in
    linux)   lib_name=liboverlaywg.so ;;
    macos)   lib_name=liboverlaywg.dylib ;;
    windows) lib_name=overlaywg.dll ;;
esac

out_dir="build/$os_key-$arch_key"
mkdir -p "$out_dir"

echo "==> building $out_dir/$lib_name"
GOFLAGS=-mod=mod "$GO" build -buildmode=c-shared -o "$out_dir/$lib_name" ./cshared
echo "==> built $(ls -lh "$out_dir/$lib_name" | awk '{print $5}')"
echo "==> verify: ./gradlew :composeApp:desktopTest --tests '*OverlayWgBindingSmokeTest'"
