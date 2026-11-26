#!/usr/bin/env python3
"""Small SDK-only build: javac + aapt2 + D8 + apksigner. No cloud access."""
import os
import hashlib
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'android/talk'
TOOLS = ROOT / '.tools/android'
SDK = Path(os.environ.get('NEOTALKIE_ANDROID_SDK', TOOLS / 'sdk' if (TOOLS / 'sdk').exists() else os.environ.get('ANDROID_HOME', TOOLS / 'sdk')))
JAVA = Path(os.environ.get('NEOTALKIE_JAVA_HOME', TOOLS / 'jdk/Contents/Home' if (TOOLS / 'jdk/Contents/Home').exists() else os.environ.get('JAVA_HOME', TOOLS / 'jdk/Contents/Home')))
BT = SDK / 'build-tools/35.0.0'
ANDROID = SDK / 'platforms/android-28/android.jar'
OUT = APP / 'build'
LIBS = [TOOLS / 'libs/okhttp-3.12.13.jar', TOOLS / 'libs/okio-1.15.0.jar']
HASHES = ['508234e024ef7e270ab1a6d5b356f5b98e786511239ca986d684fd1e2cf7bc82',
          '693fa319a7e8843300602b204023b7674f106ebcb577f2dd5807212b66118bd2']

def run(*args):
    env = dict(os.environ, JAVA_HOME=str(JAVA))
    env['PATH'] = str(JAVA / 'bin') + os.pathsep + env.get('PATH', '')
    subprocess.run([str(a) for a in args], check=True, env=env)

def build(app):
    global APP, OUT
    APP = ROOT / ('android/' + app)
    OUT = APP / 'build'
    libs = LIBS if app == 'talk' else []
    for p in [ANDROID, JAVA / 'bin/javac', BT / 'aapt2', *libs]:
        if not p.is_file():
            raise SystemExit(f'Missing build tool: {p}; see docs/getting-started.md')
    for lib, expected in zip(libs, HASHES):
        if hashlib.sha256(lib.read_bytes()).hexdigest() != expected:
            raise SystemExit(f'Dependency checksum mismatch: {lib.name}')
    if OUT.exists(): shutil.rmtree(OUT)
    for name in ['classes', 'generated', 'dex', 'assets']:
        (OUT / name).mkdir(parents=True)
    if app == 'talk': shutil.copyfile(ROOT / 'config/prompts/default.txt', OUT / 'assets/prompt.txt')
    if app == 'hfp-mic':
        shutil.copyfile(ROOT / 'LICENSE', OUT / 'assets/LICENSE-APACHE-2.0.txt')
    else:
        for name in ['THIRD_PARTY.md', 'LICENSE-APACHE-2.0.txt']:
            shutil.copyfile(APP / name, OUT / 'assets' / name)
    run(BT / 'aapt2', 'compile', '--dir', APP / 'res', '-o', OUT / 'resources.zip')
    run(BT / 'aapt2', 'link', '-o', OUT / 'resources.apk', '-I', ANDROID,
        '--manifest', APP / 'AndroidManifest.xml', '--java', OUT / 'generated',
        '-A', OUT / 'assets', OUT / 'resources.zip')
    sources = sorted((APP / 'src').rglob('*.java')) + sorted((OUT / 'generated').rglob('*.java'))
    if app != 'hfp-mic':
        sources += sorted((ROOT / 'android/common/src').rglob('*.java'))
    run(JAVA / 'bin/javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-Xlint:-options', '-classpath', os.pathsep.join(map(str, [ANDROID, *libs])),
        '-d', OUT / 'classes', *sources)
    run(JAVA / 'bin/jar', 'cf', OUT / 'classes.jar', '-C', OUT / 'classes', '.')
    run(BT / 'd8', '--min-api', '21', '--lib', ANDROID, '--output', OUT / 'dex', OUT / 'classes.jar', *libs)
    unsigned = OUT / 'unsigned.apk'
    shutil.copyfile(OUT / 'resources.apk', unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
        for dex in (OUT / 'dex').glob('*.dex'): z.write(dex, dex.name)
    run(BT / 'zipalign', '-f', '4', unsigned, OUT / 'aligned.apk')
    key = ROOT / 'data/android/private/debug.keystore'
    key.parent.mkdir(parents=True, exist_ok=True)
    key.parent.chmod(0o700)
    if not key.exists():
        run(JAVA / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '10000', '-dname', 'CN=NeoTalkie Android Prototype,O=NeoTalkie,C=CN')
        key.chmod(0o600)
    apk = OUT / ('neotalkie-' + app + '.apk')
    run(BT / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
        '--v1-signing-enabled', 'true', '--out', apk, OUT / 'aligned.apk')
    run(BT / 'apksigner', 'verify', '--verbose', apk)
    print(f'APK: {apk} ({apk.stat().st_size:,} bytes)')

if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', choices=['talk', 'launcher', 'hfp-mic', 'all'], nargs='?', default='all')
    args = parser.parse_args()
    for app in (['launcher', 'talk', 'hfp-mic'] if args.app == 'all' else [args.app]): build(app)
