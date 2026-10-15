#!/bin/bash
# Builds iSH-AOK's emulator core (libish.a, libish_emu.a, libfakefs.a) for the iOS device or the
# Simulator, with only what Cinder Lite needs: the arm64 guest, and none of the optional native
# shells, editors or GPU support. Called by the Xcode project's Engine target; can also be run by
# hand:  ios/scripts/build-engine.sh iphonesimulator|iphoneos <output dir>
set -euo pipefail

sdk=${1:-${PLATFORM_NAME:-iphonesimulator}}
out=${2:-${MESON_BUILD_DIR:-$(dirname "$0")/../build/engine-$sdk}}
src=$(cd "$(dirname "$0")/../ish-AOK" && pwd)
min_ios=${IPHONEOS_DEPLOYMENT_TARGET:-16.0}

# Homebrew's meson/ninja/lld aren't on Xcode's PATH.
export PATH="/opt/homebrew/bin:/opt/homebrew/opt/lld/bin:/opt/homebrew/opt/llvm/bin:/usr/local/bin:$PATH"
for tool in meson ninja python3; do
    command -v $tool >/dev/null || { echo "error: $tool not found (brew install meson ninja lld llvm)" >&2; exit 1; }
done

case "$sdk" in
    iphonesimulator) triple="arm64-apple-ios$min_ios-simulator" ;;
    iphoneos)        triple="arm64-apple-ios$min_ios" ;;
    *) echo "error: unknown sdk $sdk" >&2; exit 1 ;;
esac
sysroot=$(env -u SDKROOT xcrun --sdk "$sdk" --show-sdk-path)
mkdir -p "$out"
cross="$out/cross.txt"
cat >"$cross.tmp" <<EOF
[binaries]
c = 'clang'
objc = 'clang'
cpp = 'clang++'
ar = 'ar'

[host_machine]
system = 'darwin'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = ['-target', '$triple', '-isysroot', '$sysroot']
c_link_args = ['-target', '$triple', '-isysroot', '$sysroot']
objc_args = ['-target', '$triple', '-isysroot', '$sysroot']
objc_link_args = ['-target', '$triple', '-isysroot', '$sysroot']
cpp_args = ['-target', '$triple', '-isysroot', '$sysroot']
cpp_link_args = ['-target', '$triple', '-isysroot', '$sysroot']

[properties]
needs_exe_wrapper = true
EOF
cmp -s "$cross.tmp" "$cross" && rm "$cross.tmp" || { mv "$cross.tmp" "$cross"; rm -rf "$out/meson"; }

# Tools that run on the Mac during the build (gadget generators) use the host compiler.
export CC_FOR_BUILD="env -u SDKROOT -u IPHONEOS_DEPLOYMENT_TARGET xcrun clang"
export CC="$CC_FOR_BUILD"
if [ ! -f "$out/meson/meson-private/coredata.dat" ]; then
    meson setup "$out/meson" "$src" --cross-file "$cross" --buildtype=debugoptimized \
        -Dguest_archs=arm64 -Dgpu=disabled -Dnative_rust=disabled -Dnative_helix=disabled \
        -Dnative_bash=disabled -Dnative_zsh=disabled -Dnative_dash=disabled
fi
ninja -C "$out/meson" libish.a libish_emu.a libfakefs.a
for lib in libish.a libish_emu.a libfakefs.a; do ln -sf "meson/$lib" "$out/$lib"; done

# hterm's single-file bundle (term.html loads it) is a build product, not a source file.
hterm="$src/deps/libapps/hterm"
if [ ! -f "$hterm/dist/js/hterm_all.js" ]; then
    (cd "$hterm" && python3 bin/mkdist >/dev/null)
fi
