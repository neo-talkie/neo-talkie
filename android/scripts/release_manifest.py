#!/usr/bin/env python3
"""Generate ota-manifest.json for all built NeoTalkie APKs."""
import argparse
import datetime
import hashlib
import json
import re
import subprocess
from pathlib import Path
from build import APPS, BT, ROOT

AAPT2 = BT / 'aapt2'


def inspect_apk(apk_path: Path):
    out = subprocess.check_output([str(AAPT2), 'dump', 'badging', str(apk_path)], text=True)
    pkg_match = re.search(
        r"package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']*)'", out
    )
    if not pkg_match:
        raise SystemExit(f'Failed to parse package badging for {apk_path}')
    label_match = re.search(r"application-label:'([^']+)'", out)
    return {
        'packageName': pkg_match.group(1),
        'versionCode': int(pkg_match.group(2)),
        'versionName': pkg_match.group(3),
        'label': label_match.group(1) if label_match else pkg_match.group(1),
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--tag', required=True, help='GitHub release tag, e.g. v0.2.3-build.42')
    p.add_argument('--version', default='', help='Display version (defaults to launcher versionName)')
    p.add_argument('--repo', default='neo-talkie/neo-talkie', help='GitHub owner/repo')
    p.add_argument('--url-base', default='', help='Override download base URL (ends without trailing slash)')
    p.add_argument('--notes', default='', help='Short release notes')
    p.add_argument('--apps', nargs='*', default=list(APPS), help='App directory names to include')
    p.add_argument('--output', required=True, help='Destination path for ota-manifest.json')
    args = p.parse_args()

    url_base = (
        args.url_base.rstrip('/')
        if args.url_base
        else f'https://github.com/{args.repo}/releases/download/{args.tag}'
    )
    packages = []
    launcher_version = ''
    # Emit non-launcher apps before launcher so clients see natural install order too.
    ordered_apps = [a for a in args.apps if a != 'launcher'] + [a for a in args.apps if a == 'launcher']
    for app in ordered_apps:
        apk_path = ROOT / f'android/{app}/build/neotalkie-{app}.apk'
        if not apk_path.is_file():
            raise SystemExit(f'Missing built APK: {apk_path}; run build.py first')
        meta = inspect_apk(apk_path)
        data = apk_path.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        is_self = meta['packageName'] == 'com.neotalkie.launcher'
        if is_self:
            launcher_version = meta['versionName']
        packages.append({
            'id': app,
            'name': meta['label'],
            'packageName': meta['packageName'],
            'versionCode': meta['versionCode'],
            'versionName': meta['versionName'],
            'apk': apk_path.name,
            'url': f'{url_base}/{apk_path.name}',
            'sha256': sha,
            'size': len(data),
            'self': is_self,
        })

    manifest = {
        'schema': 'neotalkie-ota/v1',
        'releaseTag': args.tag,
        'version': args.version or launcher_version or args.tag,
        'publishedAt': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'notes': args.notes,
        'packages': packages,
    }
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'OTA manifest written: {out_path} ({len(packages)} packages, tag={args.tag})')


if __name__ == '__main__':
    main()
