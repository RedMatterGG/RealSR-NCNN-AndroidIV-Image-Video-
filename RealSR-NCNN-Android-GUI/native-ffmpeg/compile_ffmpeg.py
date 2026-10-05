import os, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parent
vs='C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231'
sdk='C:/Program Files (x86)/Windows Kits/10'
e=os.environ.copy()
e['INCLUDE']=';'.join([vs+'/include']+[sdk+'/Include/10.0.26100.0/'+x for x in ['ucrt','shared','um']])
e['LIB']=';'.join([vs+'/lib/x64']+[sdk+'/Lib/10.0.26100.0/'+x+'/x64' for x in ['ucrt','um']])
e['PATH']=vs+'/bin/Hostx64/x64;'+e['PATH']
subprocess.run(['C:/Program Files/Git/bin/bash.exe', str(root/'configure-build.sh')],env=e,check=True)
