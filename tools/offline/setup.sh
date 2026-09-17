#!/bin/bash
# Run IN THE CLOUD SANDBOX, from the extracted project root, once per session:
#   bash tools/offline/setup.sh "/mnt/user-data/uploads/itemcasino/Claude outputs/offline-jars.tar"
# Extracts the jars into .offline/jars and writes the two classpaths the other scripts read.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TAR="${1:?path to offline-jars.tar}"
J="$ROOT/.offline/jars"
rm -rf "$J" && mkdir -p "$J"
tar xf "$TAR" -C "$J"
MERGED="$J/build/moddev/artifacts/neoforge-21.11.42-merged.jar"
[ -f "$MERGED" ] || { echo "no merged jar in $TAR" >&2; exit 2; }
cp_main=""; cp_junit=""
while read -r jar; do
  [ -z "$jar" ] && continue
  case "$jar" in
    *junit*|*opentest4j*|*apiguardian*) cp_junit="$cp_junit:$J/$jar" ;;
    *) cp_main="$cp_main:$J/$jar" ;;
  esac
done < "$ROOT/tools/offline-classpath.txt"
echo "${cp_main#:}:$MERGED" > "$ROOT/.offline/cp.txt"
echo "${cp_junit#:}" > "$ROOT/.offline/junit-cp.txt"
echo "$MERGED" > "$ROOT/.offline/merged-jar.txt"
echo "classpath: $(tr ':' '\n' < "$ROOT/.offline/cp.txt" | wc -l) jars, junit: $(tr ':' '\n' < "$ROOT/.offline/junit-cp.txt" | wc -l) jars"
# A local git repository makes "what did I change" and "what do I commit to the device" one command.
if [ ! -d "$ROOT/.git" ]; then
  (cd "$ROOT" && git init -q && printf '.offline/\n' > .git/info/exclude && git add -A && git -c user.email=offline@local -c user.name=offline commit -qm "as staged" && echo "git: baseline committed")
fi
