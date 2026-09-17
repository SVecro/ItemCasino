#!/bin/bash
# Compiles all of src/main/java against the real NeoForge classpath (see setup.sh).
# A clean tree prints six [removal] warnings (makeMockServerPlayerInLevel x3, Items.registerItem x3).
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/.offline/classes"
rm -rf "$OUT" && mkdir -p "$OUT"
cd "$ROOT"
# -sourcepath "" and -implicit:none: the merged jar also carries .java sources, and without them javac
# tries to compile NeoForge itself.
javac -Xlint:removal -proc:none -implicit:none -sourcepath "" -encoding UTF-8 --release 21 -Xmaxerrs 200 \
      -cp "$(cat .offline/cp.txt)" -d "$OUT" $(find src/main/java -name '*.java') 2>&1 | grep -v "Picked up"
status=${PIPESTATUS[0]}
echo "classes: $(find "$OUT" -name '*.class' | wc -l)"
exit $status
