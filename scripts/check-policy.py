#!/usr/bin/env python3
"""Keep feature work off stable/main; CI foundation PRs are the explicit bootstrap exception."""
import os
import subprocess


def bootstrap_path(path):
    return path.startswith(('.github/', 'scripts/', 'docs/', 'app/src/androidTest/')) or path in {'README.md', 'app/build.gradle.kts', 'app/lint-baseline.xml'}


def allowed(base, head):
    if base == 'main':
        return head == 'nightly' or head.startswith('ci/')
    if base == 'nightly':
        return head == 'preview' or head.startswith('integration/')
    return base == 'preview'


if __name__ == '__main__':
    base = os.environ.get('PR_BASE', '')
    head = os.environ.get('PR_HEAD', '')
    if base and not allowed(base, head):
        raise SystemExit('Branch policy: feature → preview → nightly (full integration) → main. CI bootstrap may target main.')
    if base == 'main' and head.startswith('ci/'):
        revision = os.environ.get('PR_BASE_SHA')
        if not revision:
            raise SystemExit('CI bootstrap requires the exact PR base SHA')
        paths = subprocess.check_output(['git', 'diff', '--name-only', f'{revision}...HEAD'], text=True).splitlines()
        forbidden = [path for path in paths if not bootstrap_path(path)]
        if forbidden:
            raise SystemExit('CI bootstrap must not carry product feature code: ' + ', '.join(forbidden))
