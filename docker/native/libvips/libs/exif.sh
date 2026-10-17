# EXIF metadata, mainly the orientation of photos
fetch https://github.com/libexif/libexif/releases/download/v${VERSION_EXIF}/libexif-${VERSION_EXIF}.tar.xz exif
configure_static --disable-nls --disable-docs --without-libiconv-prefix --without-libintl-prefix \
	CPPFLAGS="$CPPFLAGS -DNO_VERBOSE_TAG_DATA"
make install-strip doc_DATA=
