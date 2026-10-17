#!/usr/bin/env bash

# Builds ffmpeg and ffprobe for linux-x64 into the directory given as the first argument, with only what the server
# uses. x264 (H.264 encoding) and dav1d (AV1 decoding) are built first and linked in statically.

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUTPUT=${1:?usage: build.sh <output directory>}

source "$HERE/../common.sh"
source "$HERE/versions.env"

LIBS=(x264 dav1d ffmpeg)

export HERE OUTPUT
export SRC=/tmp/ffmpeg/src
export PREFIX=/tmp/ffmpeg/deps
export PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig
export MAKEFLAGS="-j$(nproc)"

mkdir -p "$SRC" "$PREFIX" "$OUTPUT"

for lib in "${LIBS[@]}"; do
	echo "=== $lib"
	(source "$HERE/libs/$lib.sh")
done
