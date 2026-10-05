from pathlib import Path
import subprocess
R=Path(__file__).resolve().parent.parent
TC=Path('F:/videoimageupscaler/toolchain')
J=TC/'jdk-21.0.12.1+1/bin'
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
cp=';'.join(str(next((cache/p).rglob(n))) for p,n in [('junit/junit/4.13.2','junit-4.13.2.jar'),('org.hamcrest/hamcrest-core/1.3','hamcrest-core-1.3.jar')])
out=TC/'test-lightweight-java';out.mkdir(exist_ok=True)
subprocess.run([str(J/'javac.exe'),'-cp',cp,'-d',str(out),str(R/'app/src/main/java/com/tumuyan/ncnn/realsr/NcnnSettings.java'),str(R/'app/src/test/java/com/tumuyan/ncnn/realsr/NcnnSettingsTest.java')],check=True)
subprocess.run([str(J/'java.exe'),'-cp',str(out)+';'+cp,'org.junit.runner.JUnitCore','com.tumuyan.ncnn.realsr.NcnnSettingsTest'],check=True)
