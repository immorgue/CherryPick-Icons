"""Render backdrop pixel contracts offscreen; never installs an APK or changes settings."""
from pathlib import Path
import subprocess

repo = Path(__file__).resolve().parents[2]
workspace = repo.parent
out = workspace / 'scratch/material-finish-check'
sdk = workspace / '.tools/android-sdk'
java = workspace / '.tools/jdk17/jdk-17.0.20.1+1/bin'
adb = sdk / 'platform-tools/adb.exe'
classes = out / 'classes'
dex = out / 'dex'
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(exist_ok=True)

def run(argv):
    result = subprocess.run([str(x) for x in argv], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stdout + '\n' + result.stderr)
    return result

run([java / 'javac.exe', '-encoding', 'UTF-8', '-source', '17', '-target', '17', '-cp', sdk / 'platforms/android-35/android.jar', '-d', classes,
    Path(__file__).with_name('MaterialFinishCheck.java'), Path(__file__).with_name('CouiInteractionCheck.java')])
run([java / 'java.exe', '-cp', sdk / 'build-tools/34.0.0/lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '31', '--lib', sdk / 'platforms/android-35/android.jar', '--output', dex, *classes.rglob('*.class')])
run([adb, 'push', dex / 'classes.dex', '/data/local/tmp/cherrypick-finish-test.dex'])
run([adb, 'push', repo / 'app/build/outputs/apk/release/app-release.apk', '/data/local/tmp/cherrypick-finish-test.apk'])
result = run([adb, 'shell', "su -c 'CLASSPATH=/data/local/tmp/cherrypick-finish-test.dex:/data/local/tmp/cherrypick-finish-test.apk app_process /system/bin MaterialFinishCheck'"])
touch = run([adb, 'shell', "su -c 'CLASSPATH=/data/local/tmp/cherrypick-finish-test.dex:/data/local/tmp/cherrypick-finish-test.apk app_process /system/bin CouiInteractionCheck'"])
(out / 'result.txt').write_text(result.stdout + result.stderr + touch.stdout + touch.stderr, encoding='utf-8')
print(result.stdout)
print(touch.stdout)
assert result.stdout.count('PASS:') == 4
assert touch.stdout.count('PASS:') == 1
