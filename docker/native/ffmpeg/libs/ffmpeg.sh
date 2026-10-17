# ffmpeg and ffprobe, from the release tarball checked against the FFmpeg release signing key
mkdir -p "$SRC/ffmpeg"
cd "$SRC"
$CURL -O https://ffmpeg.org/releases/ffmpeg-${VERSION_FFMPEG}.tar.xz -O https://ffmpeg.org/releases/ffmpeg-${VERSION_FFMPEG}.tar.xz.asc
gpg --dearmor < "$HERE/ffmpeg-devel.asc" > ffmpeg-devel.gpg
gpgv --keyring ./ffmpeg-devel.gpg ffmpeg-${VERSION_FFMPEG}.tar.xz.asc ffmpeg-${VERSION_FFMPEG}.tar.xz
bsdtar -xf ffmpeg-${VERSION_FFMPEG}.tar.xz -C ffmpeg --strip-components 1
cd ffmpeg

# --disable-everything turns off every component; only these are turned back on.
# Demuxers: the containers in FFmpeg.DEMUXERS, plus the raw stream demuxers ffmpeg uses to work out what an undeclared
# stream inside MPEG-PS or MPEG-TS is. FFmpeg.java's -format_whitelist keeps those from opening an upload themselves.
# Decoders: what phones, cameras and browsers record, plus older formats still found in people's archives
# (AVI, WMV, FLV, MPEG-1/2, 3GP).
# Filters: what FFmpeg.java asks for (scale, fps) and what ffmpeg inserts by itself for format conversion, resampling,
# -ss/-t trimming and rotation from the display matrix.
./configure --prefix="$OUTPUT" --pkg-config-flags=--static --extra-libs="-lpthread -lm" \
	--enable-gpl --enable-libx264 --enable-libdav1d --enable-zlib --enable-pthreads \
	--disable-everything --disable-autodetect --disable-network --disable-avdevice \
	--disable-doc --disable-debug --disable-ffplay \
	--enable-protocol=file \
	--enable-demuxer=mov,matroska,avi,mpegts,mpegps,flv,asf,ogg,mp3,wav,flac,aac,aiff,caf \
	--enable-demuxer=mpegvideo,h264,hevc,m4v,ac3,eac3,loas,mjpeg \
	--enable-decoder=h264,hevc,vp8,vp9,libdav1d,mpeg4,mpeg1video,mpeg2video,h263,msmpeg4v3,wmv1,wmv2,wmv3,vc1,theora,flv,mjpeg,png \
	--enable-decoder=aac,aac_latm,mp3float,mp2float,opus,vorbis,flac,alac,ac3,eac3,wmav2,amrnb,amrwb \
	--enable-decoder=pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_u8 \
	--enable-parser=h264,hevc,vp8,vp9,av1,mpeg4video,mpegvideo,aac,mpegaudio,opus,vorbis,flac,ac3 \
	--enable-encoder=libx264,aac,png \
	--enable-muxer=mp4,ipod,image2 \
	--enable-filter=scale,fps,aresample,aformat,format,null,anull,trim,atrim,transpose,hflip,vflip
make
make install
strip "$OUTPUT/bin/ffmpeg" "$OUTPUT/bin/ffprobe"
