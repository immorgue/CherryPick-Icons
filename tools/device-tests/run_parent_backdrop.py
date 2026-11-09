"""Pixel contracts in an isolated root app_process; does not install any APK."""
from pathlib import Path
import argparse, subprocess
p=argparse.ArgumentParser()
p.add_argument('--workspace',type=Path,default=Path(__file__).resolve().parents[2].parent)
args=p.parse_args();r=args.workspace;src=Path(__file__).parent;out=r/'scratch/parent-backdrop-check';sdk=r/'.tools/android-sdk';j=r/'.tools/jdk17/jdk-17.0.20.1+1/bin';a=sdk/'platform-tools/adb.exe';c=out/'classes';d=out/'dex';c.mkdir(parents=True,exist_ok=True);d.mkdir(exist_ok=True)
def run(argv):
 result=subprocess.run([str(x) for x in argv],capture_output=True,text=True,timeout=60)
 if result.returncode:raise RuntimeError(result.stdout+'\n'+result.stderr)
 return result
run([j/'javac.exe','-encoding','UTF-8','-source','17','-target','17','-cp',sdk/'platforms/android-35/android.jar','-d',c,*src.rglob('*.java')])
run([j/'java.exe','-cp',sdk/'build-tools/34.0.0/lib/d8.jar','com.android.tools.r8.D8','--min-api','31','--lib',sdk/'platforms/android-35/android.jar','--output',d,*c.rglob('*.class')])
run([a,'push',d/'classes.dex','/data/local/tmp/cherrypick-parent-test.dex'])
run([a,'push',src.parents[1]/'app/build/outputs/apk/release/app-release.apk','/data/local/tmp/cherrypick-parent-test.apk'])
result=run([a,'shell',"su -c 'CLASSPATH=/data/local/tmp/cherrypick-parent-test.dex:/data/local/tmp/cherrypick-parent-test.apk app_process /system/bin ParentBackdropCheck'"])
(out/'result.txt').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout,result.stderr);assert 'PASS:' in result.stdout
