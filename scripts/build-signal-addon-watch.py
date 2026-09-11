#!/usr/bin/env python3
"""Build a pinned messaging-only Pebble draft. This never installs or publishes."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def release_metadata(metadata: dict, descriptor: dict, package: str) -> dict:
    if metadata['pebble']['uuid'] != descriptor['uuid'] or descriptor['uuid'] != 'e2fd86ec-dfb8-460c-afc1-ebe4d071657a':
        raise ValueError('Watch UUID differs from the existing application')
    if set(metadata['pebble']['targetPlatforms']) != set(descriptor['targets']):
        raise ValueError('Declared target platforms differ')
    if descriptor['androidPackage'] != package or package != 'com.lukesteuber.signalstation':
        raise ValueError('Companion registration differs from the standalone Android package')
    if descriptor['embeddedPkjs'] or not descriptor['installationHold'] or descriptor['distribution'] != 'unlisted-draft':
        raise ValueError('This builder requires messaging-only, held draft distribution')
    result = copy.deepcopy(metadata)
    result['version'] = descriptor['version']
    result['pebble']['companionApp'] = {'android': {'required': True, 'apps': [{'package': package}]}}
    result['pebble'].pop('enableMultiJS', None)
    return result


def immutable_copy(source: Path, target: Path):
    if target.exists() and hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(source.read_bytes()).digest():
        raise ValueError(f'Immutable release file already exists with different bytes: {target.name}')
    if not target.exists():
        shutil.copy2(source, target)


def build(source: Path, output: Path, descriptor_path: Path):
    source = source.resolve()
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=source, text=True).strip():
        raise ValueError('Commit the reviewed watch source before building')
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
    descriptor = json.loads(descriptor_path.read_text())
    if revision != descriptor['sourceCommit']:
        raise ValueError('Watch source revision differs from the release descriptor')
    props = dict(line.split('=', 1) for line in (ROOT / 'signalApp/release.properties').read_text().splitlines() if '=' in line)
    metadata = release_metadata(json.loads((source / 'package.json').read_text()), descriptor, props['packageName'])
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='signal-addon-watch-') as temporary:
        work = Path(temporary)
        shutil.copytree(source / 'src/c', work / 'src/c')
        shutil.copytree(source / 'resources', work / 'resources')
        (work / 'package.json').write_text(json.dumps(metadata, indent=2))
        script = (source / 'wscript').read_text()
        if script.count('    ctx.pbl_bundle(') != 1:
            raise ValueError('Watch bundle recipe changed; review the messaging-only transformation')
        (work / 'wscript').write_text(script[:script.index('    ctx.pbl_bundle(')] + '    ctx.pbl_bundle(binaries=binaries)\n')
        (work / 'SOURCE_REVISION.txt').write_text(revision + '\n')
        shutil.copy2(descriptor_path, work / 'watch-release.json')
        for license_file in source.glob('LICENSE*'):
            if license_file.is_file(): shutil.copy2(license_file, work / license_file.name)
        version = descriptor['version']
        archive = output / f'signal-station-addon-{version}-source.zip'
        staged_archive = work / 'source.zip'
        with zipfile.ZipFile(staged_archive, 'w', zipfile.ZIP_DEFLATED) as bundle:
            for path in sorted(work.rglob('*')):
                if path.is_file() and path != staged_archive: bundle.write(path, path.relative_to(work))
        subprocess.run(['pebble', 'build'], cwd=work, check=True)
        pbw = next((work / 'build').glob('*.pbw'))
        with zipfile.ZipFile(pbw) as bundle:
            info = json.loads(bundle.read('appinfo.json'))
            if info['uuid'] != descriptor['uuid'] or info['versionLabel'] != version or info['companionApp'] != metadata['pebble']['companionApp']:
                raise ValueError('Built package metadata differs from the descriptor')
            if any(name.endswith('.js') for name in bundle.namelist()):
                raise ValueError('Messaging addon unexpectedly embeds JavaScript')
            for platform in descriptor['targets']:
                if len(bundle.read(platform + '/pebble-app.bin')) < 100: raise ValueError('Missing native target')
        target = output / f'signal-station-addon-{version}.pbw'
        immutable_copy(pbw, target)
        immutable_copy(staged_archive, archive)
        receipt = dict(descriptor, watch_source_commit=revision, watch_source_dirty=False,
            pbw_sha256=hashlib.sha256(target.read_bytes()).hexdigest(), source_sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
            embedded_pkjs=False, physical_validation='not performed; installation hold remains')
        (output / 'provenance.json').write_text(json.dumps(receipt, indent=2) + '\n')
        (output / 'SHA256SUMS.txt').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in [target, archive]))
    return target


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('watch_source', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'signalApp/build/watch-preview')
    parser.add_argument('--descriptor', type=Path, default=ROOT / 'signalApp/watch-release.json')
    args = parser.parse_args()
    print(build(args.watch_source, args.output, args.descriptor))
