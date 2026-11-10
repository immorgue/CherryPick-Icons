"""Bounded FrameTimeline ring buffer. Does not launch apps or send input events."""
from pathlib import Path
import argparse, subprocess

parser=argparse.ArgumentParser()
parser.add_argument('action',choices=['start','save'])
parser.add_argument('--output',type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[3]
adb=root/'.tools/android-sdk/platform-tools/adb.exe'
scratch=root/'scratch/passive-frames'
scratch.mkdir(parents=True,exist_ok=True)

def run(*arguments):
    result=subprocess.run([str(adb),*map(str,arguments)],capture_output=True,timeout=40)
    if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace')+result.stdout.decode(errors='replace'))
    return result.stdout

if args.action=='start':
    config=scratch/'frames.pbtxt'
    config.write_text('''unique_session_name: "cherrypick_jank"
buffers { size_kb: 16384 fill_policy: RING_BUFFER }
duration_ms: 1800000
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
''',encoding='utf-8')
    run('push',config,'/data/local/tmp/cherrypick-frames.pbtxt')
    output=run('shell','su','-c','perfetto --background-wait --txt -c /data/local/tmp/cherrypick-frames.pbtxt -o /data/local/tmp/cherrypick-frames-final.pftrace').decode().strip()
    pid=next(line.strip() for line in output.splitlines() if line.strip().isdigit())
    run('shell','su','-c',f'echo {pid} > /data/local/tmp/cherrypick-frames.pid')
    print('Recorder PID:',pid)
    print('Armed: 30 minutes maximum, 16 MiB ring. No screenshots, video, scheduling trace or input automation.')
else:
    destination=args.output or scratch/'frames-snapshot.pftrace'
    destination.parent.mkdir(parents=True,exist_ok=True)
    pid=run('shell','su','-c','cat /data/local/tmp/cherrypick-frames.pid').decode().strip()
    assert pid.isdigit()
    command=run('shell','su','-c',f"cat /proc/{pid}/cmdline").replace(b'\0',b' ')
    assert b'perfetto --background-wait --txt -c /data/local/tmp/cherrypick-frames.pbtxt' in command
    run('shell','su','-c',f'kill -INT {pid}')
    import time
    for _ in range(30):
        alive=subprocess.run([str(adb),'shell','su','-c',f'kill -0 {pid} 2>/dev/null'],capture_output=True).returncode==0
        if not alive:break
        time.sleep(.1)
    if alive:raise RuntimeError('Recorder did not finish flushing')
    run('pull','/data/local/tmp/cherrypick-frames-final.pftrace',destination)
    if destination.stat().st_size<1024:raise RuntimeError('FrameTimeline recording is empty')
    print(destination)
