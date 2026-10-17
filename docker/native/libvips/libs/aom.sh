# AV1 codec for AVIF in libheif
fetch https://storage.googleapis.com/aom-releases/libaom-${VERSION_AOM}.tar.gz aom
mkdir aom_build
cd aom_build
AOM_AS_FLAGS=$FLAGS CMAKE_SOURCE=.. CMAKE_BUILD_TYPE=MinSizeRel cmake_install \
	-DENABLE_DOCS=0 -DENABLE_TESTS=0 -DENABLE_TESTDATA=0 -DENABLE_TOOLS=0 -DENABLE_APPS=0 -DENABLE_EXAMPLES=0 \
	-DCONFIG_PIC=1 -DENABLE_NASM=1 -DENABLE_RVV=0 -DCONFIG_AV1_HIGHBITDEPTH=1 -DCONFIG_WEBM_IO=0
