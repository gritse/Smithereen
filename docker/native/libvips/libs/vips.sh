# libvips itself, as a shared library with everything above linked in, copied to $OUTPUT
fetch https://github.com/libvips/libvips/releases/download/v${VERSION_VIPS}/vips-${VERSION_VIPS}.tar.xz vips

# Hide the symbols of the static libraries, except glib and gobject, which the server calls through JNA
EXCLUDE_LIBS=$(find "$PREFIX/lib" -maxdepth 1 -name '*.a' ! -name 'libglib-2.0.a' ! -name 'libgobject-2.0.a' -printf "-Wl,--exclude-libs=%f ")
# Localize g_param_spec_types to avoid collisions with other shared libraries, see https://github.com/lovell/sharp/issues/2535
printf "{local:g_param_spec_types;};" > vips.map

# Don't build man pages, translations, tools or (fuzz) tests
sed -i "/subdir('man')/{N;N;N;N;d;}" meson.build

# Every loader whose library isn't built above is disabled by name, so that an unexpected library in the build image
# fails the build instead of quietly widening what libvips will parse
CFLAGS="$CFLAGS -O3" CXXFLAGS="$CXXFLAGS -O3" MESON_LIBRARY=shared MESON_INSTALL_TAG=runtime meson_install \
	-Ddeprecated=false -Dexamples=false -Dcplusplus=false -Dintrospection=disabled -Dmodules=disabled \
	-Dcfitsio=disabled -Dfftw=disabled -Dfontconfig=disabled -Djpeg-xl=disabled -Dmagick=disabled -Dmatio=disabled \
	-Dnifti=disabled -Dopenexr=disabled -Dopenjpeg=disabled -Dopenslide=disabled -Dorc=disabled -Dpangocairo=disabled \
	-Dpdfium=disabled -Dpoppler=disabled -Dquantizr=disabled -Draw=disabled -Drsvg=disabled -Dspng=disabled \
	-Dtiff=disabled -Duhdr=disabled -Dppm=false -Danalyze=false -Dradiance=false \
	-Dc_link_args="$LDFLAGS -Wl,-Bsymbolic-functions -Wl,--version-script=$PWD/vips.map ${EXCLUDE_LIBS% }"

# Everything else is linked in, so libvips.so.42 may only need the C and C++ runtimes of the base image
cd "$PREFIX/lib"
if readelf -d libvips.so.42 | grep NEEDED | grep -v -E '\[(libc|libm|libstdc\+\+|libgcc_s|libpthread|libdl|ld-linux-x86-64)\.so'; then
	echo "libvips.so.42 needs a library that is not part of the base image" >&2
	exit 1
fi
cp -L libvips.so.42 "$OUTPUT/"
