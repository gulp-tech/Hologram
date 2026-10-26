#!/usr/bin/env bash
# One-command build of the HolographicDisplays sources in ./project
# Needs either Maven 3.6+ with a JDK 17, or just Docker (then nothing to install).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$ROOT/project"
OUT="$ROOT/dist"
mkdir -p "$OUT"

cd "$PROJ"

if command -v mvn >/dev/null 2>&1; then
  echo "==> mvn -B clean package -DskipTests"
  mvn -B clean package -DskipTests "$@"
elif command -v docker >/dev/null 2>&1; then
  echo "==> mvn -B clean package -DskipTests (в контейнере maven:3.9-eclipse-temurin-17)"
  docker run --rm -i \
    -v "$PROJ":/src:delegated \
    -v hd-optimized-m2:/root/.m2 \
    -w /src \
    maven:3.9-eclipse-temurin-17 \
    mvn -B clean package -DskipTests "$@"
else
  echo "Не найдены ни mvn, ни docker." >&2
  echo "Установите JDK 17 + Maven 3.6+  (или Docker) и запустите скрипт снова." >&2
  exit 1
fi

built="$(ls -1 plugin/target/*.jar 2>/dev/null | grep -v 'original-' | head -1 || true)"
if [ -z "$built" ]; then
  echo "Сборка завершилась, но plugin/target/*.jar не найден." >&2
  exit 1
fi
cp -f "$built" "$OUT/HolographicDisplays-3.0.5-optimized-bedrock.jar"
echo
echo "Готово:"
ls -l "$OUT/HolographicDisplays-3.0.5-optimized-bedrock.jar"
sha256sum "$OUT/HolographicDisplays-3.0.5-optimized-bedrock.jar" 2>/dev/null || true
echo "-> кладите этот файл в /plugins/ сервера."
