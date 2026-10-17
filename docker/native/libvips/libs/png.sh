# PNG
fetch https://github.com/pnggroup/libpng/archive/v${VERSION_PNG}.tar.gz png
configure_static --disable-tools --without-binconfigs --disable-unversioned-libpng-config
make install-strip dist_man_MANS=
