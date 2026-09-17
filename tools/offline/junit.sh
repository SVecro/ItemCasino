#!/bin/bash
# Runs the JUnit suite (pure core + src/test/java) without Gradle. Prints PASS/FAIL per test.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/.offline/junit"
JU="$(cat "$ROOT/.offline/junit-cp.txt")"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -d "$OUT" -cp "$JU" $(find "$ROOT/src/main/java/com/itemcasino/core" -name '*.java') \
      $(find "$ROOT/src/test/java" -name '*.java') "$ROOT/tools/offline/RunJUnit.java" 2>&1 | grep -v "Picked up"
java -cp "$OUT:$JU" RunJUnit 2>&1 | grep -v "Picked up"
exit ${PIPESTATUS[0]}
