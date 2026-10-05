"""Relink an independently supplied/modified FFmpeg static archive set with the supplied JNI object."""
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--ndk',default='F:/videoimageupscaler/toolchain/android-ndk-r28c');p.add_argument('--output',default='libffmpeg_video-relinked.so');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parent
clang=pathlib.Path(a.ndk)/'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
command=[str(clang),'--target=aarch64-linux-android24','-shared','-static-libstdc++','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384','-Wl,--no-undefined','-Wl,--exclude-libs,ALL','-Wl,-soname,libffmpeg_video.so',str(root/'ffmpeg_video.o')]+[str(root/('lib'+x+'.a')) for x in ['avformat','avcodec','swscale','avutil']]+['-ljnigraphics','-llog','-lm','-ldl','-o',str(root/a.output)]
subprocess.run(command,check=True);print('Relinked '+str(root/a.output))
