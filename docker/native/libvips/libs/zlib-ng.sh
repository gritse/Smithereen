# zlib replacement, used by libpng and the others that compress
fetch https://github.com/zlib-ng/zlib-ng/archive/${VERSION_ZLIB_NG}.tar.gz zlib-ng
CFLAGS="$CFLAGS -O3" cmake_install -DBUILD_TESTING=FALSE -DZLIB_COMPAT=TRUE -DWITH_ARMV6=FALSE
