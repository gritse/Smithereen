#!/usr/bin/env bash

# Builds libvips for linux-x64 with every library it needs linked in statically, and puts libvips.so.42 into the
# directory given as the first argument. Each library is built by its own script in libs/, in the order below.
#
# Adapted from https://github.com/lovell/sharp-libvips/blob/cd6d208b01ecf4ad970e5c2a9f6c6d85e3c1f769/build/posix.sh
# The flags in libs/ follow upstream, so its changes can be brought over library by library. Differences:
# - linux-x64 only
# - no librsvg (with cairo, pango, fontconfig, freetype, harfbuzz, fribidi, pixman, libxml2), libtiff or libultrahdr:
#   the server does not read SVG, TIFF or Ultra HDR, and every loader left out is attack surface gone
# - libde265 in libheif, so that HEIC photos from phones can be read
# - only the C library: the server calls libvips through JNA, so libvips-cpp is not built

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUTPUT=${1:?usage: build.sh <output directory>}

source "$HERE/../common.sh"
source "$HERE/versions.env"

# Dependencies come before the libraries that use them
LIBS=(zlib-ng ffi glib exif lcms aom de265 heif mozjpeg png imagequant webp highway expat archive cgif vips)

export SRC=/tmp/libvips/src
export PREFIX=/tmp/libvips/target
export OUTPUT
export CMAKE_TOOLCHAIN=$HERE/Toolchain.cmake
export MESON_CROSS_FILE=$HERE/meson.ini

# Optimised for size by default; steps that benefit switch to -O3. No unwind tables, and sections the linker can drop.
export FLAGS="-march=nehalem -Os -fPIC -fno-asynchronous-unwind-tables -ffunction-sections -fdata-sections"
export CFLAGS=$FLAGS
export CXXFLAGS=$FLAGS
export CPPFLAGS="-I$PREFIX/include"
# libvips.so finds anything next to it; everything else is static anyway
export LDFLAGS="-L$PREFIX/lib -Wl,--gc-sections -Wl,-rpath=\$ORIGIN/"

# Only the libraries built here, never the ones installed in the build image
export PKG_CONFIG="pkg-config --static"
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig
unset PKG_CONFIG_PATH

export PATH=$PATH:$PREFIX/bin
export LD_LIBRARY_PATH=$PREFIX/lib
export MAKEFLAGS="-j$(nproc)"

mkdir -p "$SRC" "$PREFIX" "$OUTPUT"

for lib in "${LIBS[@]}"; do
	echo "=== $lib"
	# A subshell per step, so that one step's cd and variables don't leak into the next
	(source "$HERE/libs/$lib.sh")
done
