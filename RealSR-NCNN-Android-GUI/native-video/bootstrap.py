"""Pinned private native toolchain; does not modify the Android SDK/global config."""
from pathlib import Path
import urllib.request, zipfile, hashlib, concurrent.futures
ROOT = Path('F:/videoimageupscaler/toolchain')
DEPS = {
 'android-ndk-r28c': 'https://dl.google.com/android/repository/android-ndk-r28c-windows.zip',
 'cmake-3.31.6-windows-x86_64': 'https://github.com/Kitware/CMake/releases/download/v3.31.6/cmake-3.31.6-windows-x86_64.zip',
 'ncnn-20250916-android-vulkan': 'https://github.com/Tencent/ncnn/releases/download/20250916/ncnn-20250916-android-vulkan.zip',
}
SHA256 = {
 'android-ndk-r28c': '6bec98ac2354d8a919760889a1a41d020132e5e8cfa1b1fe51610a72c36a466b',
 'cmake-3.31.6-windows-x86_64': 'd163cd3ab4959b0a53fa8988f2ddbd2e6c501658201e6a154386bad9dbe4f836',
 'ncnn-20250916-android-vulkan': '27e94224e63a74359e4cbf37b9ad86bdeff9d751f737ab7a32971c01ff048ee8',
}
def install(item):
 name, url = item
 ROOT.mkdir(parents=True, exist_ok=True)
 archive = ROOT/(name+'.zip')
 if not archive.exists():
  temp=archive.with_suffix('.download')
  urllib.request.urlretrieve(url,temp)
  temp.replace(archive)
 digest=hashlib.sha256(archive.read_bytes()).hexdigest()
 if digest != SHA256[name]: raise RuntimeError('Dependency checksum mismatch: '+name)
 print(name, digest, flush=True)
 (ROOT/(name+'.sha256')).write_text(digest+'  '+archive.name+'\n')
 if not (ROOT/name).exists():
  with zipfile.ZipFile(archive) as z: z.extractall(ROOT)
if __name__ == '__main__':
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
  list(pool.map(install,DEPS.items()))
