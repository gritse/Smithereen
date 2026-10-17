# AV1 decoder: ffmpeg's native one only works with hardware acceleration
fetch https://code.videolan.org/videolan/dav1d/-/archive/${VERSION_DAV1D}/dav1d-${VERSION_DAV1D}.tar.gz dav1d
meson_install -Denable_tools=false -Denable_examples=false -Denable_tests=false -Denable_docs=false
