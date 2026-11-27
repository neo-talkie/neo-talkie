#!/usr/bin/env python3
"""Explicit ADB operations. Never called by tests; never installs server code."""
import argparse, subprocess, json
from pathlib import Path
from adb_path import default_adb
from build import APPS

ROOT = Path(__file__).resolve().parents[2]
ADMIN_COMPONENT = 'com.neotalkie.launcher/com.neotalkie.device.OtaAdminReceiver'

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--adb', default=default_adb())
p.add_argument('action', choices=['doctor', 'install', 'home', 'status', 'device-owner'])
a = p.parse_args()
adb = [a.adb, '-s', a.serial]


def run(*cmd):
    return subprocess.check_output([*adb, *cmd], text=True, timeout=120).strip()


def ensure_device_owner():
    policy = run('shell', 'dumpsys', 'device_policy')
    if 'com.neotalkie.launcher' in policy and 'OtaAdminReceiver' in policy:
        print('Device Owner already active for silent OTA.')
        return
    proc = subprocess.run(
        [*adb, 'shell', 'dpm', 'set-device-owner', ADMIN_COMPONENT],
        text=True,
        capture_output=True,
        timeout=30,
    )
    output = (proc.stdout + '\n' + proc.stderr).strip()
    if proc.returncode == 0 and 'Success' in output:
        print(output)
    else:
        print('WARNING: Could not activate Device Owner for silent OTA:', output)
        print('Ensure no accounts are logged in on the device, then re-run: device.py --serial', a.serial, 'device-owner')


if run('get-state') != 'device':
    raise SystemExit('ADB device must be connected and authorized')

if a.action == 'doctor':
    info = {k: run('shell', 'getprop', v) for k, v in {'model': 'ro.product.model', 'android': 'ro.build.version.release', 'api': 'ro.build.version.sdk'}.items()}
    features = run('shell', 'pm', 'list', 'features')
    info['bleDeclared'] = 'android.hardware.bluetooth_le' in features
    info['deviceAdminDeclared'] = 'android.software.device_admin' in features
    info['note'] = 'BLE feature declaration is not proof of advertising/GATT support. Physical keys need device acceptance.'
    print(json.dumps(info, ensure_ascii=False, indent=2))
    if int(info['api'] or 0) < 21:
        raise SystemExit('Android API 21 or above required')
elif a.action == 'install':
    paths = [ROOT / f'android/{app}/build/neotalkie-{app}.apk' for app in APPS]
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        raise SystemExit('Build all APKs first (make android-build). Missing: ' + ', '.join(missing))
    for path in paths:
        print(f'Installing {path.name}...')
        print(run('install', '-r', '-d', str(path)))
    ensure_device_owner()
    print(run('shell', 'am', 'start', '-n', 'com.neotalkie.launcher/com.neotalkie.device.HomeActivity'))
    print('All apps installed and silent OTA owner checked. Press HOME and choose NeoTalkie desktop / Always.')
elif a.action == 'device-owner':
    ensure_device_owner()
elif a.action == 'home':
    print(run('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))
    print('Choose NeoTalkie desktop / Always. If already assigned, clear the previous launcher default in system settings.')
else:
    packages = {'launcher': 'com.neotalkie.launcher', 'talk': 'com.neotalkie.talk', 'audio-link': 'com.neotalkie.hfpmic'}
    for app, pkg in packages.items():
        value = run('shell', 'pm', 'path', pkg)
        print(app + ': ' + ('installed' if value.startswith('package:') else 'not installed'))
    policy = run('shell', 'dumpsys', 'device_policy')
    owner = 'active' if ('com.neotalkie.launcher' in policy and 'OtaAdminReceiver' in policy) else 'inactive'
    launcher_dump = run('shell', 'dumpsys', 'package', 'com.neotalkie.launcher')
    priv = 'granted' if 'android.permission.INSTALL_PACKAGES' in launcher_dump and 'PRIVILEGED' in launcher_dump else 'not-privileged'
    print('silent-ota (device-owner / Android 6+): ' + owner)
    print('silent-ota (priv-app INSTALL_PACKAGES / Android 5.1): ' + priv)
