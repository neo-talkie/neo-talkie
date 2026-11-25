#!/usr/bin/env python3
"""Check the public source set; ignored local builds/tools/data are excluded."""
from pathlib import Path
import subprocess, re, json
ROOT = Path(__file__).resolve().parents[2]
files = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
problems = []
legacy = ''.join(['mo','la'])
for name in sorted(set(filter(None, files))):
    path = ROOT / name
    if not path.is_file(): continue
    if path.suffix.lower() in {'.keystore','.jks','.pem','.key','.pcm','.wav','.mp3','.m4a','.sqlite','.db'} or name.startswith(('data/','.tools/','.env')):
        problems.append(name + ': private/runtime file in public source set'); continue
    try: text = path.read_text()
    except UnicodeDecodeError: continue
    if legacy in text.lower() or legacy in name.lower(): problems.append(name + ': old product identifier')
    if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', text): problems.append(name + ': private key')
    if ('/' + 'Users/') in text or ('oss:' + '//') in text: problems.append(name + ': machine/account-specific destination')
    if path.suffix == '.json' and name.endswith('device.example.json'):
        obj=json.loads(text)
        if obj.get('voiceApiKey') or obj.get('arkApiKey'): problems.append(name + ': nonempty credential template')
    if path.suffix == '.md':
        for target in re.findall(r'\]\(([^)]+)\)', text):
            if '://' in target or target.startswith('#') or target.startswith('mailto:'): continue
            target=target.split('#')[0]
            if target and not (path.parent / target).exists(): problems.append(name + ': missing link ' + target)
if problems: raise SystemExit('\n'.join(problems))
print('Public-source preflight passed: ' + str(len(set(files))-1) + ' files; no private file types, old identifiers or broken local Markdown links detected.')
