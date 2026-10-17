# SIMD for libvips' own image operations
fetch https://github.com/google/highway/archive/${VERSION_HIGHWAY}.tar.gz highway
CFLAGS="$CFLAGS -O3" CXXFLAGS="$CXXFLAGS -O3" cmake_install \
	-DBUILD_TESTING=0 -DHWY_ENABLE_CONTRIB=0 -DHWY_ENABLE_EXAMPLES=0 -DHWY_ENABLE_TESTS=0
