#!/usr/bin/env python3
"""Fail-closed source/artifact identity shared by CI and the phone deployment gate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

PACKAGES = {"lab": "app.detent.launcher.lab", "kvn": "app.detent.launcher.kvn"}
JOURNEYS = {"collection_scan", "image_import", "offline_restart", "duplicate_import", "swipe_up", "swipe_down", "distant_tap", "no_crash"}


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def identity(path):
    with zipfile.ZipFile(path) as apk:
        value = json.loads(apk.read('assets/detent-build.json'))
    if not re.fullmatch('[0-9a-f]{40}', value.get('source_sha', '')):
        raise ValueError('APK has no immutable source identity')
    if value.get('channel') not in {'candidate', 'preview', 'nightly', 'stable'}:
        raise ValueError('APK has no recognized channel')
    if not str(value.get('run_id', '')).isdigit():
        raise ValueError('APK has no CI run identity')
    return value


def verify(directory, sha, channel, run):
    if not re.fullmatch('[0-9a-f]{40}', sha):
        raise ValueError('expected full source SHA')
    root = Path(directory)
    manifest = json.loads((root / 'provenance.json').read_text())
    expected = {'source_sha': sha, 'channel': channel, 'run_id': str(run)}
    for key, value in expected.items():
        if manifest.get(key) != value:
            raise ValueError(f'provenance {key} mismatch')
    for label, package in PACKAGES.items():
        entry = manifest['apks'][label]
        name = f'detent-{label}.apk'
        if entry['file'] != name or entry['package'] != package:
            raise ValueError('unexpected APK filename/package')
        path = root / name
        if entry['sha256'] != sha256(path) or identity(path) != expected:
            raise ValueError('APK bytes or embedded identity mismatch')
    return manifest


def qualification(value, manifest):
    if value.get('schema') != 1 or value.get('device_serial') != 'RFCTA1RZAQY':
        raise ValueError('S25 requires an S22 hardware receipt')
    for key in ('source_sha', 'run_id'):
        if value.get(key) != manifest.get(key):
            raise ValueError('S22 tested another source/run')
    if value.get('channel') != 'preview' or manifest.get('channel') != 'preview':
        raise ValueError('phones require the preview channel')
    for label in PACKAGES:
        if value.get('artifact_hashes', {}).get(label) != manifest['apks'][label]['sha256']:
            raise ValueError('S22 receipt belongs to other artifact bytes')
    tests = value.get('journeys', {})
    if set(tests) != JOURNEYS or any(result != 'passed' for result in tests.values()):
        raise ValueError('every named S22 journey must pass')
    if not value.get('evidence_dir'):
        raise ValueError('missing S22 evidence directory')


def create(args):
    root = Path(args.dir)
    expected = {'source_sha': args.sha, 'channel': args.channel, 'run_id': str(args.run)}
    apks = {}
    for label, package in PACKAGES.items():
        path = root / f'detent-{label}.apk'
        if identity(path) != expected:
            raise ValueError('build identity differs from the requested source')
        apks[label] = {'file': path.name, 'package': package, 'sha256': sha256(path)}
    manifest = {**expected, 'schema': 1, 'version_code': args.version_code, 'apks': apks}
    (root / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n')
    verify(root, args.sha, args.channel, args.run)
    print(json.dumps(manifest, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['create', 'verify'])
    parser.add_argument('--dir', required=True)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--channel', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--version-code', type=int)
    args = parser.parse_args()
    if args.action == 'create':
        create(args)
    else:
        print(json.dumps(verify(args.dir, args.sha, args.channel, args.run), indent=2))


if __name__ == '__main__':
    main()
