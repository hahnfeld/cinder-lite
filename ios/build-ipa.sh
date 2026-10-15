#!/bin/sh
# Builds an unsigned build/CinderLite.ipa for iPhones (arm64), for SideStore or AltStore to sign
# with your Apple ID when it installs it. Needs Xcode, and: brew install xcodegen meson ninja llvm lld
set -e
cd "$(dirname "$0")"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export PATH="/opt/homebrew/bin:$PATH"

git submodule update --init ish-AOK
git -C ish-AOK submodule update --init deps/libapps deps/libarchive deps/smallclue
xcodegen generate --quiet

rm -rf build/archive build/Payload build/CinderLite.ipa
xcodebuild archive -project CinderLite.xcodeproj -scheme CinderLite -configuration Release \
  -destination generic/platform=iOS -archivePath build/archive/CinderLite.xcarchive \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= -quiet

mkdir -p build/Payload
cp -R build/archive/CinderLite.xcarchive/Products/Applications/CinderLite.app build/Payload/
(cd build && zip -qry CinderLite.ipa Payload)
rm -rf build/Payload
echo "build/CinderLite.ipa ($(du -h build/CinderLite.ipa | cut -f1)), unsigned"
