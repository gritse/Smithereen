# XML parser libvips needs for XMP metadata. VERSION_EXPAT is the release tag, R_2_9_0 for 2.9.0.
EXPAT=${VERSION_EXPAT#R_}
EXPAT=${EXPAT//_/.}
fetch https://github.com/libexpat/libexpat/releases/download/${VERSION_EXPAT}/expat-${EXPAT}.tar.xz expat
configure_static --without-xmlwf --without-docbook --without-getrandom --without-sys-getrandom \
	--without-libbsd --without-examples --without-tests
make install-strip dist_cmake_DATA= nodist_cmake_DATA=
