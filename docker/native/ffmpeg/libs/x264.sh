# H.264 encoder for the transcoded videos. x264 makes no releases, so a commit on its stable branch is pinned.
fetch https://code.videolan.org/videolan/x264/-/archive/${VERSION_X264}/x264-${VERSION_X264}.tar.gz x264
./configure --prefix="$PREFIX" --enable-static --enable-pic --disable-cli --disable-opencl \
	--disable-avs --disable-swscale --disable-lavf --disable-ffms --disable-gpac --disable-lsmash
make install
