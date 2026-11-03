#!/usr/bin/env python3
"""Install a checked preview artifact, refusing S25 until matching S22 journeys have passed.

Signing is deliberately separate: never give repository/PR build code the phone key.
This command never changes the HOME role, clears app data, uninstalls, or drives the screen.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import zipfile

from provenance import identity, qualification, sha256, verify

HARDWARE = {'s22': 'RFCTA1RZAQY', 's25': 'R5CY93G2WNH'}


def run(*args, binary=False):
    return subprocess.check_output(args, timeout=90, text=not binary)


def adb(serial, *args, binary=False):
    return run('adb', '-s', serial, *args, binary=binary)


def same_payload(original, signed):
    def contents(path):
        with zipfile.ZipFile(path) as apk:
            return {i.filename: apk.read(i) for i in apk.infolist() if not i.filename.startswith('META-INF/')}
    if contents(original) != contents(signed):
        raise ValueError('signed APK payload differs from the checked CI artifact')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=['s22', 's25'], required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--signed-apk', type=Path, required=True)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--qualification', type=Path)
    parser.add_argument('--aapt2', required=True)
    parser.add_argument('--apksigner', required=True)
    args = parser.parse_args()
    manifest = verify(args.artifacts, args.sha, 'preview', args.run)
    remote = json.loads(run('gh', 'api', f'repos/kvnloo/detent/actions/runs/{args.run}'))
    if (remote.get('head_sha') != args.sha or remote.get('head_branch') != 'preview' or
            remote.get('event') != 'push' or remote.get('conclusion') != 'success' or
            remote.get('path') != '.github/workflows/checks.yml'):
        raise ValueError('only successful preview push runs from checks.yml may reach phones')
    ref = json.loads(run('gh', 'api', 'repos/kvnloo/detent/git/ref/heads/preview'))
    if ref['object']['sha'] != args.sha:
        raise ValueError('preview advanced; qualify its new artifact rather than installing a stale build')
    if adb(args.serial, 'shell', 'getprop', 'ro.serialno').strip() != HARDWARE[args.target]:
        raise ValueError('wrong physical phone for this deployment target')
    label = 'lab' if args.target == 's22' else 'kvn'
    package = manifest['apks'][label]['package']
    if args.target == 's25':
        if args.qualification is None:
            raise ValueError('S25 requires --qualification from the S22 run')
        receipt = json.loads(args.qualification.read_text())
        qualification(receipt, manifest)
        proof = Path(receipt['evidence_dir'])
        if not proof.is_dir() or not list(proof.rglob('*.png')):
            raise ValueError('S22 screenshots are missing')
    same_payload(args.artifacts / manifest['apks'][label]['file'], args.signed_apk)
    if identity(args.signed_apk) != {'source_sha': args.sha, 'channel': 'preview', 'run_id': args.run}:
        raise ValueError('signed APK identity mismatch')
    badging = run(args.aapt2, 'dump', 'badging', str(args.signed_apk))
    match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)'", badging)
    if not match or match.group(1) != package or int(match.group(2)) != manifest['version_code']:
        raise ValueError('actual Android package/version differs from provenance')
    args.evidence.mkdir(parents=True, exist_ok=False)
    package_info = adb(args.serial, 'shell', 'dumpsys', 'package', package)
    installed_version = re.search(r'versionCode=(\d+)', package_info)
    if installed_version and int(installed_version.group(1)) > manifest['version_code']:
        raise ValueError('refusing a downgrade')
    installed_path = adb(args.serial, 'shell', 'pm', 'path', package).strip().removeprefix('package:')
    if not installed_path.startswith('/') or '\n' in installed_path:
        raise ValueError('expected one existing APK; do not replace an unknown installation')
    prior = args.evidence / 'before.apk'
    prior.write_bytes(adb(args.serial, 'exec-out', 'cat', installed_path, binary=True))
    before_prefs = adb(args.serial, 'exec-out', 'run-as', package, 'cat', 'shared_prefs/detent.xml', binary=True)
    (args.evidence / 'prefs-before.xml').write_bytes(before_prefs)
    def certificate(path):
        text = run(args.apksigner, 'verify', '--print-certs', str(path))
        match = re.search(r'Signer #1 certificate SHA-256 digest: (\S+)', text)
        if match is None:
            raise ValueError('could not read APK signing certificate')
        return match.group(1)
    if certificate(prior) != certificate(args.signed_apk):
        raise ValueError('signing certificate differs; never uninstall to bypass this')
    adb(args.serial, 'install', '-r', str(args.signed_apk))
    actual_path = adb(args.serial, 'shell', 'pm', 'path', package).strip().removeprefix('package:')
    after = args.evidence / 'after.apk'
    after.write_bytes(adb(args.serial, 'exec-out', 'cat', actual_path, binary=True))
    if sha256(after) != sha256(args.signed_apk) or identity(after) != identity(args.signed_apk):
        raise ValueError('installed APK readback differs from the candidate')
    after_prefs = adb(args.serial, 'exec-out', 'run-as', package, 'cat', 'shared_prefs/detent.xml', binary=True)
    (args.evidence / 'prefs-after.xml').write_bytes(after_prefs)
    receipt = {'schema': 1, 'target': args.target, 'device_serial': HARDWARE[args.target], 'package': package,
               'source_sha': args.sha, 'channel': 'preview', 'run_id': args.run,
               'installed_sha256': sha256(after), 'certificate_sha256': certificate(after),
               'preferences_identical': before_prefs == after_prefs, 'before_sha256': sha256(prior)}
    (args.evidence / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n')
    if before_prefs != after_prefs:
        raise ValueError('APK installed, but preferences changed; inspect the saved before/after XML before proceeding')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
