#!/usr/bin/env python3
"""Publish independently owned local GitOps charts; default to applications only."""
import argparse
import re
import shutil
from pathlib import Path

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('target')
parser.add_argument('prefix')
parser.add_argument('revision')
parser.add_argument('--scope', choices=['application', 'infrastructure', 'platform', 'bootstrap'], default='application')
args = parser.parse_args()
if not re.fullmatch(r'[a-z0-9][a-z0-9.:/_-]*', args.prefix):
    raise SystemExit('Invalid registry prefix')
if not re.fullmatch(r'[0-9a-f]{40}', args.revision):
    raise SystemExit('Expected a full commit SHA')
target = Path(args.target).resolve()
application_templates = {'app.yaml', 'order-query.yaml', 'hpa.yaml'}
scopes = ['application', 'infrastructure', 'platform'] if args.scope == 'bootstrap' else [args.scope]
for scope in scopes:
    source = root / ('local-ci/platform' if scope == 'platform' else 'performance/helm/hyperscale-commerce')
    dest = target / 'charts' / scope
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(source, dest)
    if scope != 'platform':
        for path in (dest / 'templates').glob('*.yaml'):
            if (path.name in application_templates) != (scope == 'application'):
                path.unlink()
        chart = dest / 'Chart.yaml'
        chart.write_text(chart.read_text().replace('name: hyperscale-commerce\n', f'name: hyperscale-{scope}\n'))
        values = (root / f'local-ci/gitops/environments/local/{scope}-values.yaml').read_text()
        values = values.replace('IMAGE_PREFIX', args.prefix).replace('COMMIT_SHA', args.revision)
        path = target / f'environments/local/{scope}-values.yaml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(values)
