# JPEG, as libjpeg-turbo with mozjpeg's better compression available
fetch https://github.com/mozilla/mozjpeg/archive/${VERSION_MOZJPEG}.tar.gz mozjpeg
# fix: use saturating arithmetic in SIMD FDCT to prevent overflow
$CURL https://github.com/mozilla/mozjpeg/commit/f90668e0e4fb79c81e1f24a0ccc0e2090af761bf.patch | patch -p1
# Behave like libjpeg-turbo unless asked otherwise
sed -i 's/JCP_MAX_COMPRESSION/JCP_FASTEST/' jcapimin.c
CMAKE_BUILD_TYPE=MinSizeRel cmake_install -DWITH_JPEG8=1 -DWITH_TURBOJPEG=FALSE -DPNG_SUPPORTED=FALSE
