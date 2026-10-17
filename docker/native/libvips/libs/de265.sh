# HEVC decoder for HEIC in libheif. Not in sharp-libvips; the flags are ours.
fetch https://github.com/strukturag/libde265/releases/download/v${VERSION_DE265}/libde265-${VERSION_DE265}.tar.gz de265
CFLAGS="$CFLAGS -O3" CXXFLAGS="$CXXFLAGS -O3" cmake_install -DENABLE_DECODER=OFF -DENABLE_ENCODER=OFF -DENABLE_SDL=OFF
