#!/usr/bin/env python3
"""Unpack tests/export-gallery.js JSON into an offline review gallery (stdlib only)."""
import argparse
import base64
import html
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('bundle', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
data = json.loads(args.bundle.read_text())
# DevTools may wrap the returned value in a markdown JSON block.
if isinstance(data, str):
    data = json.loads(data)
manifest = data['manifest']
assert manifest['schema'] == 'neotalkie-radio-regression/v1'
args.output.mkdir(parents=True, exist_ok=True)
shots = args.output / 'screens'
shots.mkdir(exist_ok=True)
(args.output / 'all-states.png').write_bytes(base64.b64decode(data['board']['png'], validate=True))
(args.output / 'states.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
by_id = {s['id']: s for s in data['screens']}
assert len(by_id) == len(manifest['cases'])
cards = []
for case in manifest['cases']:
    name = f"{case['number']:03}-{case['id']}"
    assert re.fullmatch(r'[\w-]+', name), name
    filename = name + '.png'
    (shots / filename).write_bytes(base64.b64decode(by_id[case['id']]['png'], validate=True))
    # Each state file can be imported into the simulator separately.
    state = {k: case[k] for k in ['schema', 'androidBaseline', 'state']}
    (shots / (name + '.json')).write_text(json.dumps(state, ensure_ascii=False, indent=2))
    esc = html.escape
    cards.append(f'<article><img src="screens/{esc(filename)}" width="320" height="256" alt="{esc(case["name"])}"><h2>{case["number"]:03} · {esc(case["name"])}</h2><p>{esc(case["group"])} / {esc(case["note"])}</p><a href="screens/{esc(name)}.json">导入参数</a></article>')
(args.output / 'index.html').write_text('''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>NeoTalkie 全量 UI 回归</title><style>body{font:16px sans-serif;background:#eff3ef;color:#274b3d;margin:24px}main{display:grid;grid-template-columns:repeat(auto-fit,340px);gap:18px}article{background:white;padding:10px}img{image-rendering:pixelated}h2{font-size:16px}p,a{font-size:13px}p{min-height:34px}</style><h1>NeoTalkie 全量 UI 回归</h1><p>Android ''' + html.escape(manifest['androidBaseline']) + ''' · 本地模拟 · <a href="all-states.png">完整拼图</a> · <a href="states.json">全部状态参数</a></p><main>''' + ''.join(cards) + '</main></html>')
print(f"Exported {len(cards)} screens + parameters; atlas {data['board']['width']} × {data['board']['height']}")
