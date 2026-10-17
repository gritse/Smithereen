# Colour management: converts photos with embedded ICC profiles to sRGB
fetch https://github.com/mm2/Little-CMS/releases/download/lcms${VERSION_LCMS}/lcms2-${VERSION_LCMS}.tar.gz lcms
CFLAGS="$CFLAGS -O3" meson_install -Dtests=disabled
