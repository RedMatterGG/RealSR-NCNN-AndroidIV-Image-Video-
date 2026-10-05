import os,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parent
vs='C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231'
sdk='C:/Program Files (x86)/Windows Kits/10'
e=os.environ.copy();e['INCLUDE']=';'.join([vs+'/include']+[sdk+'/Include/10.0.26100.0/'+x for x in ['ucrt','shared','um']]);e['LIB']=';'.join([vs+'/lib/x64']+[sdk+'/Lib/10.0.26100.0/'+x+'/x64' for x in ['ucrt','um']]);e['PATH']=vs+'/bin/Hostx64/x64;'+e['PATH']
out='F:/videoimageupscaler/toolchain/ffmpeg-policy-test.exe'
subprocess.run([vs+'/bin/Hostx64/x64/cl.exe','/nologo','/std:c++17','/EHsc',str(root/'policy_test.cpp'),'/FoF:/videoimageupscaler/toolchain/ffmpeg-policy-test.obj','/Fe'+out],env=e,check=True)
subprocess.run([out],check=True);print('PASS native AVIO read extent / seek / reject bounds')
