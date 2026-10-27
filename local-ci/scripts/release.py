#!/usr/bin/env python3
"""Create a release from this checkout; never include credentials."""
import re
import shutil
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
target, prefix, revision = sys.argv[1:]
if not re.fullmatch(r"[a-z0-9][a-z0-9.:/_-]*", prefix):
    raise SystemExit("Invalid registry prefix")
if not re.fullmatch(r"[0-9a-f]{40}", revision):
    raise SystemExit("Expected a full commit SHA")
target = Path(target).resolve()
for name, source in [("commerce", root / "performance/helm/hyperscale-commerce"),
                     ("platform", root / "local-ci/platform")]:
    dest = target / "charts" / name
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(source, dest)
values = (root / "local-ci/gitops/environments/local/values.yaml").read_text()
values = values.replace("IMAGE_PREFIX", prefix).replace("COMMIT_SHA", revision)
path = target / "environments/local/values.yaml"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(values)
