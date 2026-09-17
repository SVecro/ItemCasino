#!/bin/bash
# Run ON RÉMI'S MACHINE with device_bash. Packs everything an offline check needs into two files under
# "Claude outputs", so a fresh session stages two files instead of hundreds:
#
#   Claude outputs/offline-src.tgz   the project (src, tools, docs, gradle files, .bat)
#   Claude outputs/offline-jars.tar  the compile/JUnit classpath from the Gradle cache, plus the
#                                    NeoForge merged and sources jars
#
# Needs the Gradle cache mounted: request folder access to
#   %USERPROFILE%\.gradle\caches\modules-2\files-2.1
# (it then shows up as $HOME/mnt/files-2.1). The jars do not change between sessions unless the
# NeoForge version does, so offline-jars.tar can be reused as long as it exists.
set -euo pipefail
PROJ="$HOME/mnt/itemcasino"
CACHE="$HOME/mnt/files-2.1"
OUT="$PROJ/Claude outputs"
mkdir -p "$OUT"
cd "$PROJ"
tar czf "$OUT/offline-src.tgz" src tools ./*.md ./*.gradle gradle.properties ./*.bat
echo "offline-src.tgz: $(du -h "$OUT/offline-src.tgz" | cut -f1)"
if [ "${1:-}" != "--src-only" ]; then
  if [ ! -d "$CACHE" ]; then
    echo "Gradle cache not mounted at $CACHE: request folder access to %USERPROFILE%\\.gradle\\caches\\modules-2\\files-2.1" >&2
    exit 2
  fi
  missing=0
  while read -r jar; do
    [ -z "$jar" ] && continue
    [ -f "$CACHE/$jar" ] || { echo "missing in the Gradle cache: $jar" >&2; missing=1; }
  done < tools/offline-classpath.txt
  [ $missing -eq 0 ] || exit 3
  tar cf "$OUT/offline-jars.tar" \
      -C "$CACHE" $(grep -v '^$' tools/offline-classpath.txt) \
      -C "$PROJ" build/moddev/artifacts/neoforge-21.11.42-merged.jar build/moddev/artifacts/neoforge-21.11.42-sources.jar
  echo "offline-jars.tar: $(du -h "$OUT/offline-jars.tar" | cut -f1)"
fi
