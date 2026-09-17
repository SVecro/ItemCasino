#!/bin/bash
# Everything that can be verified without Gradle, in one go. Run before every build request.
#   bash tools/offline/check.sh
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
fail=0

echo "== compile"
bash tools/offline/compile.sh > .offline/compile.log 2>&1 || fail=1
grep -E "error|classes:" .offline/compile.log | tail -30
echo "   $(grep -c 'warning: \[removal\]' .offline/compile.log) [removal] warnings (0 expected)"

echo "== junit"
bash tools/offline/junit.sh > .offline/junit.log 2>&1 || fail=1
grep -v "^PASS" .offline/junit.log

echo "== core self-test"
rm -rf .offline/core && mkdir -p .offline/core
javac -nowarn -d .offline/core $(find src/main/java/com/itemcasino/core -name '*.java') tools/CoreSelfTest.java 2>&1 | grep -v "Picked up"
java -cp .offline/core CoreSelfTest > .offline/core.log 2>&1 || fail=1
grep -E "passed|FAIL" .offline/core.log | grep -v "Picked up"

echo "== vanilla overrides"
python3 tools/audit_overrides.py "$(cat .offline/merged-jar.txt)" || fail=1

echo "== static"
python3 tools/offline/static_checks.py || fail=1

if [ $fail = 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOMETHING FAILED (logs in .offline/)"; fi
exit $fail
