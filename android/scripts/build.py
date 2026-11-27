#!/usr/bin/env python3
"""Small SDK-only build: javac + aapt2 + D8 + apksigner. No cloud access."""
import os
import hashlib
import re
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
APPS = ('launcher', 'talk', 'audio-link')
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


def manifest_version(manifest_path: Path):
    text = manifest_path.read_text(encoding='utf-8')
    code_match = re.search(r'android:versionCode="(\d+)"', text)
    name_match = re.search(r'android:versionName="([^"]+)"', text)
    if not code_match or not name_match:
        raise SystemExit(f'Missing versionCode/versionName in {manifest_path}')
    return int(code_match.group(1)), name_match.group(1)


def signing_config():
    ks_env = os.environ.get('NEOTALKIE_KEYSTORE_PATH', '').strip()
    if ks_env:
        key = Path(ks_env)
        if not key.is_file():
            raise SystemExit(f'Missing configured keystore: {key}')
        store_pass = os.environ.get('NEOTALKIE_KEYSTORE_PASSWORD', '')
        key_alias = os.environ.get('NEOTALKIE_KEY_ALIAS', '')
        key_pass = os.environ.get('NEOTALKIE_KEY_PASSWORD', store_pass)
        if not store_pass or not key_alias:
            raise SystemExit('NEOTALKIE_KEYSTORE_PASSWORD and NEOTALKIE_KEY_ALIAS are required with NEOTALKIE_KEYSTORE_PATH')
        return key, store_pass, key_alias, key_pass
    key = ROOT / 'data/android/private/debug.keystore'
    key.parent.mkdir(parents=True, exist_ok=True)
    key.parent.chmod(0o700)
    if not key.exists():
        run(JAVA / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '10000', '-dname', 'CN=NeoTalkie Android Prototype,O=NeoTalkie,C=CN')
        key.chmod(0o600)
    return key, 'android', 'androiddebugkey', 'android'


def build(app, build_number=None):
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
    if OUT.exists():
        shutil.rmtree(OUT)
    for name in ['classes', 'generated', 'dex', 'assets']:
        (OUT / name).mkdir(parents=True)
    if app == 'talk':
        shutil.copyfile(ROOT / 'config/prompts/default.txt', OUT / 'assets/prompt.txt')
    if app == 'audio-link':
        shutil.copyfile(ROOT / 'LICENSE', OUT / 'assets/LICENSE-APACHE-2.0.txt')
    else:
        for name in ['THIRD_PARTY.md', 'LICENSE-APACHE-2.0.txt']:
            shutil.copyfile(APP / name, OUT / 'assets' / name)
    run(BT / 'aapt2', 'compile', '--dir', APP / 'res', '-o', OUT / 'resources.zip')
    link_args = [
        BT / 'aapt2', 'link', '-o', OUT / 'resources.apk', '-I', ANDROID,
        '--manifest', APP / 'AndroidManifest.xml', '--java', OUT / 'generated',
        '-A', OUT / 'assets',
    ]
    base_code, base_name = manifest_version(APP / 'AndroidManifest.xml')
    if build_number is not None:
        stamped_code = base_code * 10000 + int(build_number)
        stamped_name = f'{base_name}+{int(build_number)}'
    else:
        stamped_code = base_code
        stamped_name = f'{base_name}-dev'
    link_args += ['--replace-version', '--version-code', str(stamped_code), '--version-name', stamped_name]
    link_args.append(OUT / 'resources.zip')
    run(*link_args)
    sources = sorted((APP / 'src').rglob('*.java')) + sorted((OUT / 'generated').rglob('*.java'))
    if app != 'audio-link':
        sources += sorted((ROOT / 'android/common/src').rglob('*.java'))
    classpath = [ANDROID, *libs]
    d8_classpath = []
    if app == 'audio-link':
        # Signatures only. Android supplies the actual classes on the radio.
        api = OUT / 'mx-b5-api'
        api.mkdir()
        run(JAVA / 'bin/javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
            '-Xlint:-options', '-classpath', ANDROID, '-d', api,
            *sorted((APP / 'mx-b5-api').rglob('*.java')))
        api_jar = OUT / 'mx-b5-api.jar'
        run(JAVA / 'bin/jar', 'cf', api_jar, '-C', api, '.')
        classpath.insert(0, api_jar)
        d8_classpath = ['--classpath', api_jar]
    run(JAVA / 'bin/javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-Xlint:-options', '-classpath', os.pathsep.join(map(str, classpath)),
        '-sourcepath', ROOT / 'android/common/src', '-d', OUT / 'classes', *sources)
    run(JAVA / 'bin/jar', 'cf', OUT / 'classes.jar', '-C', OUT / 'classes', '.')
    run(BT / 'd8', '--min-api', '21', '--lib', ANDROID, *d8_classpath, '--output', OUT / 'dex', OUT / 'classes.jar', *libs)
    unsigned = OUT / 'unsigned.apk'
    shutil.copyfile(OUT / 'resources.apk', unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
        for dex in (OUT / 'dex').glob('*.dex'):
            z.write(dex, dex.name)
    run(BT / 'zipalign', '-f', '4', unsigned, OUT / 'aligned.apk')
    key, store_pass, key_alias, key_pass = signing_config()
    apk = OUT / ('neotalkie-' + app + '.apk')
    run(BT / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:' + store_pass,
        '--ks-key-alias', key_alias, '--key-pass', 'pass:' + key_pass,
        '--v1-signing-enabled', 'true', '--out', apk, OUT / 'aligned.apk')
    run(BT / 'apksigner', 'verify', '--verbose', apk)
    print(f'APK: {apk} ({apk.stat().st_size:,} bytes)')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', choices=[*APPS, 'all'], nargs='?', default='all')
    parser.add_argument('--build-number', type=int, default=None,
                        help='Optional monotonic build number stamped into versionCode/versionName')
    args = parser.parse_args()
    env_num = os.environ.get('NEOTALKIE_RELEASE_BUILD', '').strip()
    build_number = args.build_number if args.build_number is not None else (int(env_num) if env_num else None)
    for app in (APPS if args.app == 'all' else (args.app,)):
        build(app, build_number=build_number)
