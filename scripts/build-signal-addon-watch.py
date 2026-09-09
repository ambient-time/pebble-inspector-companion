#!/usr/bin/env python3
"""Build the optional messaging-only watch package from a reviewed watch checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('watch_source', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
source = args.watch_source.resolve()
metadata = json.loads((source / 'package.json').read_text())
assert metadata['pebble']['uuid'] == 'e2fd86ec-dfb8-460c-afc1-ebe4d071657a'
metadata['version'] = '1.4.0'
metadata['pebble']['companionApp'] = {'android': {'required': True, 'apps': [{'package': 'com.lukesteuber.signalstation'}]}}
metadata['pebble'].pop('enableMultiJS', None)
output = root / 'signalApp/build/watch-preview'
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='signal-addon-watch-') as temporary:
    work = Path(temporary)
    shutil.copytree(source / 'src/c', work / 'src/c')
    shutil.copytree(source / 'resources', work / 'resources')
    (work / 'package.json').write_text(json.dumps(metadata, indent=2))
    script = (source / 'wscript').read_text()
    script = script[:script.index('    ctx.pbl_bundle(')] + '    ctx.pbl_bundle(binaries=binaries)\n'
    (work / 'wscript').write_text(script)
    subprocess.run(['pebble', 'build'], cwd=work, check=True)
    pbw = next((work / 'build').glob('*.pbw'))
    target = output / 'signal-station-addon-1.4.0.pbw'
    shutil.copy2(pbw, target)
    receipt = {
        'watch_source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip(),
        'watch_source_dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=source, text=True).strip()),
        'pbw_sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
        'distribution': 'isolated development preview; installation hold remains',
        'embedded_pkjs': False,
    }
    (output / 'provenance.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(target)
