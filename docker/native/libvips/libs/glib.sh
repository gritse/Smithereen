# The object system and utilities libvips is built on
fetch https://download.gnome.org/sources/glib/${VERSION_GLIB%.*}/glib-${VERSION_GLIB}.tar.xz glib
# Drop GRegex, and with it the PCRE2 dependency libvips has no use for
$CURL https://gist.github.com/kleisauke/284d685efa00908da99ea6afbaaf39ae/raw/bdad5489a61c217850631571caf57f5db6ea8b2c/glib-without-gregex.patch | patch -p1
# bin-devel installs glib-mkenums, which the libvips build runs
MESON_INSTALL_TAG=bin-devel,devel meson_install --datadir="$PREFIX/share" --force-fallback-for=gvdb \
	-Dintrospection=disabled -Dnls=disabled -Dlibmount=disabled -Dsysprof=disabled -Dlibelf=disabled \
	-Dtests=false -Dglib_assert=false -Dglib_checks=false -Dglib_debug=disabled
