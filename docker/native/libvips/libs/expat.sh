# XML parser libvips needs for XMP metadata
fetch https://github.com/libexpat/libexpat/releases/download/R_${VERSION_EXPAT//./_}/expat-${VERSION_EXPAT}.tar.xz expat
configure_static --without-xmlwf --without-docbook --without-getrandom --without-sys-getrandom \
	--without-libbsd --without-examples --without-tests
make install-strip dist_cmake_DATA= nodist_cmake_DATA=
