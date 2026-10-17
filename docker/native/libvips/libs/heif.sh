# HEIC and AVIF, with libde265 and aom built before it
fetch https://github.com/strukturag/libheif/releases/download/v${VERSION_HEIF}/libheif-${VERSION_HEIF}.tar.gz heif
CFLAGS="$CFLAGS -O3" CXXFLAGS="$CXXFLAGS -O3" cmake_install \
	-DBUILD_DOCUMENTATION=0 -DBUILD_TESTING=0 -DENABLE_PLUGIN_LOADING=0 -DWITH_EXAMPLES=0 -DWITH_LIBDE265=1 -DWITH_X265=0
