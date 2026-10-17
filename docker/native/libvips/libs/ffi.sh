# Needed by glib's GObject
fetch https://github.com/libffi/libffi/releases/download/v${VERSION_FFI}/libffi-${VERSION_FFI}.tar.gz ffi
configure_static --disable-builddir --disable-multi-os-directory --disable-raw-api --disable-structs --disable-docs
make install-strip
