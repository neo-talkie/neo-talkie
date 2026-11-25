#!/usr/bin/env python3
"""Explicit ADB operations. Never called by tests; never installs server code."""
import argparse, subprocess, json
from pathlib import Path
from adb_path import default_adb
ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--adb', default=default_adb())
p.add_argument('action', choices=['doctor','install','home','status'])
a = p.parse_args()
adb = [a.adb, '-s', a.serial]
def run(*cmd):
    return subprocess.check_output([*adb, *cmd], text=True, timeout=120).strip()
if run('get-state') != 'device': raise SystemExit('ADB device must be connected and authorized')
if a.action == 'doctor':
    info = {k:run('shell','getprop',v) for k,v in {'model':'ro.product.model','android':'ro.build.version.release','api':'ro.build.version.sdk'}.items()}
    info['bleDeclared'] = 'android.hardware.bluetooth_le' in run('shell','pm','list','features')
    info['note'] = 'BLE feature declaration is not proof of advertising/GATT support. Physical keys need device acceptance.'
    print(json.dumps(info,ensure_ascii=False,indent=2))
    if int(info['api'] or 0) < 21: raise SystemExit('Android API 21 or above required')
elif a.action == 'install':
    paths = [ROOT / ('android/'+app+'/build/neotalkie-'+app+'.apk') for app in ['launcher','talk']]
    if not all(path.is_file() for path in paths): raise SystemExit('Build both APKs first: make android-build')
    for path in paths: print(run('install','-r',str(path)))
    print(run('shell','am','start','-n','com.neotalkie.launcher/com.neotalkie.device.HomeActivity'))
    print('Both apps installed. Press HOME and choose NeoTalkie desktop / Always. No old apps were removed.')
elif a.action == 'home':
    print(run('shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.HOME'))
    print('Choose NeoTalkie desktop / Always. If already assigned, clear the previous launcher default in system settings.')
else:
    for app in ['launcher','talk']:
        value=run('shell','pm','path','com.neotalkie.'+app)
        print(app + ': ' + ('installed' if value.startswith('package:') else 'not installed'))
