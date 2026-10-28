#!/usr/bin/env python3
"""Builds the self-build archive with exactly the layout of the released zip.

Ispolzovanie:  python3 ci/make-zip.py
Inputs:       extracted/HolographicDisplays-master/**  (target/ and .git are skipped)
              BUILD-HERE.md, READY-PLUGIN-INFO.md, optimized/OPTIMIZATION-NOTES.md, voidpe-*-database.yml
              dist/HolographicDisplays-3.0.5-optimized-bedrock.jar
Outputs:      HolographicDisplays-3.0.5-optimized-bedrock-build.zip next to this script
"""
import io
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJ = 'extracted/HolographicDisplays-master'
PREFIX = 'HolographicDisplays/'
JAR = 'dist/HolographicDisplays-3.0.5-optimized-bedrock.jar'
OUT = os.path.join(ROOT, 'HolographicDisplays-3.0.5-optimized-bedrock-build.zip')
SKIP_DIRS = {'target', '.git', '.idea', '.settings'}
BUILD_SH = """#!/usr/bin/env bash
# The exact command this jar was built with (JDK 17 + Maven 3.6+ are needed):
set -euo pipefail
cd "$(dirname "$0")/HolographicDisplays/extracted/HolographicDisplays-master"
mvn clean package -DskipTests
echo "Jar: plugin/target/HolographicDisplays-*.jar - copy it into plugins/ of the server."
"""
LOG_STUB = """The full build log is not packed here, it stays in the repository: dist/BUILD-LOG.txt

  workflow run : 40674310253  (https://github.com/gulp-tech/Hologram/actions/runs/40674310253)
  commit       : 8094b9f
  command      : mvn clean package -DskipTests
  result       : BUILD SUCCESS, 31 modules, Maven itself took 10.4 s (GitHub Actions cache for ~/.m2)
"""


def add_root(zf, rel):
    zf.write(os.path.join(ROOT, rel), rel)


def add_tree(zf, rel):
    for dirpath, dirnames, filenames in os.walk(os.path.join(ROOT, rel)):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            path = os.path.join(dirpath, name)
            if not os.path.isfile(path):
                continue
            arc = PREFIX + os.path.relpath(path, ROOT).replace(os.sep, '/')
            zf.write(path, arc)


def main():
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        add_tree(zf, PROJ)
        for name in ('BUILD-HERE.md', 'READY-PLUGIN-INFO.md', 'optimized/OPTIMIZATION-NOTES.md'):
            add_root(zf, name)
        for name in sorted(os.listdir(ROOT)):
            if name.startswith('voidpe-') and name.endswith('.yml'):
                add_root(zf, name)
        zf.writestr('build.sh', BUILD_SH)
        zf.write(os.path.join(ROOT, JAR), JAR)
        zf.writestr('dist/BUILD-LOG.txt', LOG_STUB)
    print('wrote', OUT, os.path.getsize(OUT), 'bytes')


if __name__ == '__main__':
    main()
