import os, shutil
from pathlib import Path
def default_adb():
    if os.environ.get('NEOTALKIE_ADB'): return os.environ['NEOTALKIE_ADB']
    found = shutil.which('adb')
    if found: return found
    root = Path(__file__).resolve().parents[2]
    for base in [os.environ.get('NEOTALKIE_ANDROID_SDK', ''), root / '.tools/android/sdk', os.environ.get('ANDROID_HOME', ''), Path.home() / 'Library/Android/sdk']:
        if not base: continue
        candidate = Path(base) / 'platform-tools/adb'
        if candidate.is_file(): return str(candidate)
    return 'adb'
