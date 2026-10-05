#!/usr/bin/env bash
set -eu
TOOLS=${REALSR_TOOLCHAIN:-F:/videoimageupscaler/toolchain}
export PATH="$(cygpath -u "$TOOLS")/msys-make/usr/bin:$PATH"
export MSYS2_ARG_CONV_EXCL='*'
export TMPDIR="$TOOLS/ffmpeg-tmp"
mkdir -p "$TMPDIR"
BIN="$TOOLS/android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin"
ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$TOOLS/ffmpeg-7.1.2"
bash configure --host-cc="bash $ROOT/host-clang.sh" --target-os=android --arch=aarch64 --enable-cross-compile --cc="$BIN/clang.exe --target=aarch64-linux-android24" --cxx="$BIN/clang++.exe --target=aarch64-linux-android24" --ar="$BIN/llvm-ar.exe" --nm="$BIN/llvm-nm.exe" --ranlib="$BIN/llvm-ranlib.exe" --strip="$BIN/llvm-strip.exe" --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network --disable-avdevice --disable-avfilter --disable-swresample --disable-shared --enable-static --enable-pic --disable-debug --disable-gpl --disable-nonfree --enable-pthreads --enable-avformat --enable-avcodec --enable-avutil --enable-swscale --enable-decoder=h264,hevc,vp8,vp9,mpeg4 --enable-parser=h264,hevc,vp8,vp9,mpeg4video --enable-demuxer=mov,matroska,avi,mpegts --extra-cflags='-O2 -fPIC' --extra-ldflags='-Wl,-z,max-page-size=16384' > configure-output.log 2>&1
make -j8 > build-output.log 2>&1
