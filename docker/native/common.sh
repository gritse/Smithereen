# Helpers shared by the native builds. Sourced, not run.
# The build helpers install static libraries into $PREFIX and read the usual CFLAGS/LDFLAGS from the environment, so a
# step can tune one build with e.g. `CFLAGS="$CFLAGS -O3" cmake_install ...`.

CURL="curl --fail --silent --show-error --location --retry 3 --retry-max-time 30"

# fetch <url> <name>: download a source tarball, unpack it into $SRC/<name> and cd there.
# bsdtar rather than GNU tar: it detects the compression itself, and GNU tar 1.36 can't create directories under the
# amd64 emulation used for local builds on ARM Macs.
fetch() {
	local url=$1 dir=$SRC/$2

	mkdir -p "$dir"
	$CURL "$url" | bsdtar -xf - -C "$dir" --strip-components 1
	cd "$dir"
}

# cmake_install [options...]: static Release build of the source in the current directory.
# CMAKE_SOURCE (default .) and CMAKE_BUILD_TYPE (default Release) can be overridden.
# The policy minimum lets CMake 4 build projects that still declare a pre-3.5 minimum version.
cmake_install() {
	cmake -G"Unix Makefiles" ${CMAKE_TOOLCHAIN:+-DCMAKE_TOOLCHAIN_FILE=$CMAKE_TOOLCHAIN} \
		-DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_BUILD_TYPE="${CMAKE_BUILD_TYPE:-Release}" \
		-DBUILD_SHARED_LIBS=FALSE -DCMAKE_POLICY_VERSION_MINIMUM=3.5 "$@" "${CMAKE_SOURCE:-.}"
	make install/strip
}

# meson_install [options...]: static release build of the source in the current directory.
# MESON_LIBRARY (default static) and MESON_INSTALL_TAG (default devel), which selects what gets installed, can be overridden.
meson_install() {
	meson setup _build --default-library="${MESON_LIBRARY:-static}" --buildtype=release --strip --prefix="$PREFIX" --libdir=lib \
		${MESON_CROSS_FILE:+--cross-file=$MESON_CROSS_FILE} "$@"
	meson install -C _build --tag "${MESON_INSTALL_TAG:-devel}"
}

# configure_static [options...]: run an autotools configure for a static-only build. The caller runs make install,
# since each project names its install-strip variables differently.
configure_static() {
	./configure --prefix="$PREFIX" --enable-static --disable-shared --disable-dependency-tracking "$@"
}
