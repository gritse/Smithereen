# Palette quantisation for GIF output
fetch https://github.com/lovell/libimagequant/archive/v${VERSION_IMAGEQUANT}.tar.gz imagequant
CFLAGS="$CFLAGS -O3" meson_install
