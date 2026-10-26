#!/usr/bin/env bash
# Build HolographicDisplays from extracted/HolographicDisplays-master.
# Runs on a GitHub runner (the Arena sandbox has no JDK/Maven and no egress to
# Maven Central / hub.spigotmc.org / repo.codemc.io).
set -uo pipefail

PROJ_DIR="${PROJ_DIR:-extracted/HolographicDisplays-master}"
GOALS="${MAVEN_GOALS:-clean package}"
EXTRA_ARGS="${MAVEN_ARGS:--Dmaven.test.skip=true -Dcheckstyle.skip=true}"
LOG=/tmp/mvn.log
CONSOLE=/tmp/hd-console.log
OUT=/tmp/hd-out
exec > >(tee -a "$CONSOLE") 2>&1
mkdir -p "$OUT"
: > "$OUT/status"

echo "=== environment ==="
java -version 2>&1 || echo "!! java missing"
if ! command -v mvn >/dev/null 2>&1; then
  echo "-- installing maven via apt --"
  sudo apt-get update -qq >/dev/null 2>&1 || true
  sudo apt-get install -y -qq maven >/dev/null 2>&1 || true
fi
mvn -version 2>&1 | sed -n '1,3p' || echo "!! mvn missing"

echo
echo "=== repository probe (which upstreams answer) ==="
for u in \
  "https://repo1.maven.org/maven2/org/apache/maven/maven-core/3.9.9/maven-core-3.9.9.pom" \
  "https://hub.spigotmc.org/nexus/content/repositories/snapshots/org/spigotmc/spigot-api/1.8-R0.1-SNAPSHOT/maven-metadata.xml" \
  "https://repo.codemc.io/repository/maven-public/me/filoghost/fcommons/fcommons/1.2.8-SNAPSHOT/maven-metadata.xml" \
  "https://repo.codemc.io/repository/nms/org/spigotmc/spigot/1.20.4-R0.1-SNAPSHOT/maven-metadata.xml" \
  "https://repo.extendedclip.com/content/repositories/placeholderapi/me/clip/placeholderapi/2.8.1/placeholderapi-2.8.1.pom"
do
  code=$(curl -s -m 25 -o /dev/null -w '%{http_code}' "$u" 2>/dev/null || echo ERR)
  printf '%s  %s\n' "$code" "$u"
done

echo
echo "=== project layout ==="
if [ -d "$PROJ_DIR" ]; then
  ( cd "$PROJ_DIR" && ls -1 && echo "-- modules --" && grep -h "<module>" pom.xml | tr -d ' \t' )
else
  echo "!! $PROJ_DIR not found (cwd: $(pwd))"; ls -1
fi

echo
echo "=== resolving a downloadable me.clip:placeholderapi version ==="
PAPI_PICK=""
PAPI_REPO=""
for base in \
  "https://repo1.maven.org/maven2" \
  "https://repo.extendedclip.com/content/repositories/placeholderapi" \
  "https://repo.helpch.at/releases" \
  "https://repo.opencollab.dev/maven-releases" \
  "https://repo.codemc.io/repository/maven-public"
do
  meta=$(curl -sfL -m 30 "$base/me/clip/placeholderapi/maven-metadata.xml" 2>/dev/null || true)
  if [ -z "$meta" ]; then
    echo "  no metadata: $base"
    continue
  fi
  vers=$(printf '%s' "$meta" | grep -oE '<version>[^<]+</version>' | sed -e 's|</\?version>||g')
  echo "  [$(printf '%s' "$vers" | tr '\n' ' ')] <= $base"
  cand=$(printf '%s\n' "$vers" | grep -E '^2\.1[0-9]\.[0-9]+$' | sort -V | tail -n 1)
  [ -z "$cand" ] && cand=$(printf '%s\n' "$vers" | grep -E '^2\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1)
  if [ -n "$cand" ] && curl -sfL -m 40 -o /dev/null "$base/me/clip/placeholderapi/$cand/placeholderapi-$cand.jar"; then
    PAPI_PICK="$cand"
    PAPI_REPO="$base"
    break
  fi
done
if [ -n "$PAPI_PICK" ]; then
  echo "  using placeholderapi $PAPI_PICK from $PAPI_REPO"
  EXTRA_ARGS="$EXTRA_ARGS -Dplaceholderapi.version=$PAPI_PICK"
  mkdir -p "$HOME/.m2"
  cat > "$HOME/.m2/settings.xml" <<XML
<settings>
  <profiles>
    <profile>
      <id>hd-ci</id>
      <activation><activeByDefault>true</activeByDefault></activation>
      <repositories>
        <repository>
          <id>hd-papi</id>
          <url>$PAPI_REPO</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
</settings>
XML
else
  echo "  !! no downloadable placeholderapi found; relying on the pom default"
fi

echo
echo "=== mvn $GOALS $EXTRA_ARGS ==="
cd "$PROJ_DIR" || { echo "cannot enter $PROJ_DIR" >| "$OUT/status"; exit 1; }
# shellcheck disable=SC2086
mvn -B -ntp $GOALS $EXTRA_ARGS > "$LOG" 2>&1
rc=$?
echo "maven exit code: $rc"
echo "$rc" >| "$OUT/status"

echo "--- reactor summary ---"
awk '/Reactor Summary/,/BUILD (SUCCESS|FAILURE)/' "$LOG" | sed 's/^\[INFO\] //' | tail -n 45
echo "--- tail ---"
tail -n 25 "$LOG"

if true; then
  # The plugin module is shaded into plugin/target/<artifactId>-<version>.jar
  # (collected even on a non-zero exit, so a partial build is still inspectable)
  found=$(ls -1 plugin/target/*.jar 2>/dev/null | grep -v 'original-\|-sources\|-javadoc' | head -n 1)
  if [ -z "$found" ]; then
    found=$(find . -path '*/target/*.jar' ! -name 'original-*' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | head -n 1)
  fi
  if [ -n "$found" ]; then
    cp "$found" "$OUT/"
    echo "built: $found"
    ls -l "$OUT"
  else
    echo "!! build succeeded but no HolographicDisplays*.jar found"
    find . -path '*/target/*.jar' | head -n 20
  fi
fi
exit "$rc"
