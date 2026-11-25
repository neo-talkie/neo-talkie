#!/usr/bin/env python3
"""Explicit operator actions on a physical device, never invoked by automated tests.

ptt-wake-check arms physical PTT for capture/discard only until process exit, no networking.
local-audio records/plays 3 seconds without networking.
capture-limit captures up to 30 seconds, discards audio, never networks or plays.
ui-text displays supplied UI text only, with optional completion/reset, no audio/network.
ui-level previews a fixed microphone meter value for five seconds, no audio/network.
live-pcm performs a real, billable ASR/Ark/TTS turn using operator-supplied PCM.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path
from adb_path import default_adb

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--adb', default=default_adb())
p.add_argument('action', choices=['ptt-wake-check', 'local-audio', 'capture-limit', 'live-pcm', 'ui-text', 'ui-level', 'ui-context'])
p.add_argument('--context', choices=['new', 'continue'], help='UI-only context visibility preview')
p.add_argument('--level', type=int, choices=range(101), default=0, metavar='0..100')
p.add_argument('--pcm', type=Path)
p.add_argument('--text', default='')
p.add_argument('--progress-ms', type=int, default=0, help='UI-only elapsed processing time')
p.add_argument('--transcript', default='', help='Inject ASR text to verify it stays hidden; UI-only')
p.add_argument('--state', default='正在回答')
p.add_argument('--complete', action='store_true')
p.add_argument('--complete-after-ms', type=int, default=0)
a = p.parse_args()
adb = [a.adb, '-s', a.serial]

def write(name, data):
    subprocess.run([*adb, 'exec-in', 'run-as', 'com.neotalkie.talk', 'dd', f'of=files/{name}', 'bs=1', f'count={len(data)}'],
                   input=data, check=True, timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    for attempt in range(20):
        received = subprocess.run([*adb, 'exec-out', 'run-as', 'com.neotalkie.talk', 'cat', f'files/{name}'],
                                  check=True, stdout=subprocess.PIPE, timeout=10).stdout
        if received == data: return
        time.sleep(0.1)
    raise SystemExit('Private command transfer did not verify')

subprocess.run([*adb, 'shell', 'am', 'force-stop', 'com.neotalkie.talk'], check=True)
if a.action == 'live-pcm':
    if not a.pcm: p.error('live-pcm requires --pcm (16 kHz mono signed PCM16 LE, max 30 seconds)')
    data = a.pcm.read_bytes()
    if not data or len(data) % 2 or len(data) > 960000: p.error('invalid PCM size')
    # Use adb sync and a synchronous app-private copy so the complete large
    # audio file is available before launching on legacy Android shell.
    remote = '/data/local/tmp/neotalkie-acceptance.pcm'
    subprocess.run([*adb, 'push', str(a.pcm), remote], check=True)
    subprocess.run([*adb, 'shell', 'run-as com.neotalkie.talk sh -c "cat /data/local/tmp/neotalkie-acceptance.pcm > files/acceptance.pcm"'], check=True)
    subprocess.run([*adb, 'shell', 'rm', remote], check=True)
command = {'action': a.action, 'transcript': a.transcript, 'progressMs': a.progress_ms}
if a.context is not None:
    command['hasContext'] = a.context == 'continue'
if a.action == 'ui-level':
    command['level'] = a.level
if a.action == 'ui-text':
    command.update(text=a.text, state=a.state, complete=a.complete, completeAfterMs=a.complete_after_ms)
write('command.json', json.dumps(command).encode())
subprocess.run([*adb, 'shell', 'am', 'start', '-n', 'com.neotalkie.talk/com.neotalkie.device.MainActivity'], check=True)
print('Started explicit device action:', a.action)
