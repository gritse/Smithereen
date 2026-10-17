# WebP, still and animated
fetch https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-${VERSION_WEBP}.tar.gz webp
configure_static --enable-libwebpmux --enable-libwebpdemux
make install-strip bin_PROGRAMS= noinst_PROGRAMS= man_MANS=
