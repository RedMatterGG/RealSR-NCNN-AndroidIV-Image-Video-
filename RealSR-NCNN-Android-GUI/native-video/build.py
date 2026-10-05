"""Build genuine ARM64 JNI and run native host pixel tests with private pinned deps."""
from pathlib import Path
import os, subprocess, shutil
HERE=Path(__file__).resolve().parent
TC=Path('F:/videoimageupscaler/toolchain')
CMAKE=TC/'cmake-3.31.6-windows-x86_64/bin/cmake.exe'
BUILD=TC/'build-ncnn-video-arm64'
def run(*args): subprocess.run([str(x) for x in args],check=True)
if __name__=='__main__':
 ninja=shutil.which('ninja')
 if not ninja: raise RuntimeError('Ninja required on PATH (private build tool, not a global configuration change)')
 run(CMAKE,'-S',HERE,'-B',BUILD,'-G','Ninja','-DCMAKE_MAKE_PROGRAM='+ninja,
     '-DCMAKE_TOOLCHAIN_FILE='+str(TC/'android-ndk-r28c/build/cmake/android.toolchain.cmake'),
     '-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-24','-DANDROID_STL=c++_static',
     '-Dncnn_DIR='+str(TC/'ncnn-20250916-android-vulkan/arm64-v8a/lib/cmake/ncnn'),'-DCMAKE_BUILD_TYPE=Release')
 run(CMAKE,'--build',BUILD,'--parallel','4')
 dest=HERE.parent/'app/src/main/jniLibs/arm64-v8a'
 dest.mkdir(parents=True,exist_ok=True)
 shutil.copy2(BUILD/'libncnn_video.so',dest/'libncnn_video.so')
 # Host test uses installed MSVC generator; no Android/emulator runtime claim.
 host=TC/'build-ncnn-video-host'
 host_cmake=Path(shutil.which('cmake'))
 run(host_cmake,'-S',HERE,'-B',host,'-G','Visual Studio 18 2026','-A','x64')
 run(host_cmake,'--build',host,'--config','Release')
 run(TC/'cmake-3.31.6-windows-x86_64/bin/ctest.exe','--test-dir',host,'-C','Release','--output-on-failure')
