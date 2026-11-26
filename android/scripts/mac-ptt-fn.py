#!/usr/bin/env python3
"""Map this radio's Globe report to a sustained Fn on the current macOS connection."""
import argparse
import json
import plistlib
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('radio', help='paired device name, e.g. NeoTalkie-EC74')
parser.add_argument('--remove', action='store_true')
args = parser.parse_args()
if not re.fullmatch(r'NeoTalkie-[0-9A-Fa-f]{4}', args.radio):
    parser.error('Specify the exact NeoTalkie-XXXX device name.')

match = json.dumps(dict(Product=args.radio, Transport='Bluetooth', PrimaryUsagePage=1, PrimaryUsage=6))
command = ['/usr/bin/hidutil', 'property', '--matching', match]
src = 'HIDKeyboardModifierMappingSrc'
dst = 'HIDKeyboardModifierMappingDst'
globe = (0x0c << 32) | 0x29d
fn = (0xff01 << 32) | 3


def read():
    output = subprocess.check_output(command + ['--get', 'UserKeyMapping'], text=True)
    rows = re.split(r'(?m)^\s*[0-9a-fA-F]+\s+UserKeyMapping\s+', output)
    if len(rows) != 2:
        raise SystemExit('Connect exactly one matching radio keyboard, then retry.')
    value = rows[1].strip()
    if value == '(null)':
        return []
    xml = subprocess.check_output(['/usr/bin/plutil', '-convert', 'xml1', '-o', '-', '--', '-'],
                                  input=value.encode())
    entries = plistlib.loads(xml)
    if not isinstance(entries, list):
        raise SystemExit('Unexpected mapping format.')
    return [{k: int(v, 0) if isinstance(v, str) and k in (src, dst) else v
             for k, v in entry.items()} for entry in entries]


current = read()
ours = {src: globe, dst: fn}
if any(entry.get(src) == globe and entry != ours for entry in current):
    raise SystemExit('The radio already has a different Globe mapping.')
updated = [entry for entry in current if entry != ours] if args.remove else current
if not args.remove and ours not in current:
    updated = current + [ours]
if updated != current:
    subprocess.run(command + ['--set', json.dumps({'UserKeyMapping': updated})], check=True)
if read() != updated:
    raise SystemExit('The radio mapping could not be verified.')
print(args.radio, 'Globe mapping removed' if args.remove else 'Fn hold enabled')
