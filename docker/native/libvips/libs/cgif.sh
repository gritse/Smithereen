# GIF output
fetch https://github.com/dloebl/cgif/archive/v${VERSION_CGIF}.tar.gz cgif
CFLAGS="$CFLAGS -O3" meson_install -Dexamples=false -Dtests=false
