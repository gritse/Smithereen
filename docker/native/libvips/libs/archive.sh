# Zip output for libvips' dzsave; built without every optional compressor
fetch https://github.com/libarchive/libarchive/releases/download/v${VERSION_ARCHIVE}/libarchive-${VERSION_ARCHIVE}.tar.xz archive
configure_static --disable-bsdtar --disable-bsdcat --disable-bsdcpio --disable-bsdunzip --disable-posix-regex-lib \
	--disable-xattr --disable-acl --without-bz2lib --without-libb2 --without-iconv --without-lz4 --without-zstd \
	--without-lzma --without-lzo2 --without-cng --without-openssl --without-xml2 --without-expat
make install-strip libarchive_man_MANS=
