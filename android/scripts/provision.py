#!/usr/bin/env python3
"""Install APK and copy explicit private configuration via run-as, never command arguments."""
import argparse
import json
import subprocess
import time
from pathlib import Path
from adb_path import default_adb

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--config', type=Path, required=True)
parser.add_argument('--adb', default=default_adb())
args = parser.parse_args()
cfg = json.loads(args.config.read_text())
if any(not isinstance(cfg.get(k), str) or not cfg[k].strip() for k in ['voiceApiKey','arkApiKey','model']):
    raise SystemExit('Missing voice/Ark/model configuration')
payload = json.dumps(cfg, ensure_ascii=False).encode()
adb = [args.adb, '-s', args.serial]
subprocess.run([*adb, 'install', '-r', str(ROOT / 'android/talk/build/neotalkie-talk.apk')], check=True)
subprocess.run([*adb, 'shell', 'run-as', 'com.neotalkie.talk', 'mkdir', '-p', 'files'], check=True)
subprocess.run([*adb, 'exec-in', 'run-as', 'com.neotalkie.talk', 'dd', 'of=files/config.json', 'bs=1', f'count={len(payload)}'],
               input=payload, check=True, stdout=subprocess.DEVNULL, timeout=20)
for attempt in range(20):
    received = subprocess.run([*adb, 'exec-out', 'run-as', 'com.neotalkie.talk', 'cat', 'files/config.json'],
                              check=True, stdout=subprocess.PIPE, timeout=10).stdout
    if received == payload: break
    time.sleep(0.1)
else: raise SystemExit('Private configuration transfer did not verify')
subprocess.run([*adb, 'shell', 'run-as', 'com.neotalkie.talk', 'chmod', '600', 'files/config.json'], check=True)
subprocess.run([*adb, 'shell', 'am', 'force-stop', 'com.neotalkie.talk'], check=True)
subprocess.run([*adb, 'shell', 'am', 'start', '-n', 'com.neotalkie.talk/com.neotalkie.device.MainActivity'], check=True)
print('Installed NeoTalkie; private configuration provisioned without printing credentials.')
