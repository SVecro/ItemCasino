#!/usr/bin/env python3
"""Cheap checks that catch what the compiler does not, before asking Rémi for a build.

* every JSON under src/main/resources parses;
* every translation key the Java code names exists in en_us.json, and every block and item has a name;
* every registered game-test function has a test_instance JSON and the other way round (a function
  without its instance never runs, and the suite still says "all passed");
* no net.minecraft.client import outside com.itemcasino.client (a dedicated server would crash).

Exit status is the number of problems. Run from anywhere: python3 tools/offline/static_checks.py
"""
import glob, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAVA = ROOT / 'src/main/java'
RES = ROOT / 'src/main/resources'
problems = []

for f in sorted(RES.rglob('*.json')):
    try:
        json.loads(f.read_text(encoding='utf-8'))
    except Exception as e:
        problems.append(f'bad JSON {f.relative_to(ROOT)}: {e}')

lang = json.loads((RES / 'assets/itemcasino/lang/en_us.json').read_text(encoding='utf-8'))
used = set()
for f in JAVA.rglob('*.java'):
    for m in re.finditer(r'"((?:itemcasino|container\.itemcasino)\.[a-z0-9_.]+)"', f.read_text(encoding='utf-8')):
        if not m.group(1).endswith('.'):
            used.add(m.group(1))
for key in sorted(used - set(lang)):
    problems.append(f'missing translation: {key}')

for name, prefix in (('CasinoBlocks', 'block'), ('CasinoItems', 'item')):
    text = (JAVA / f'com/itemcasino/registry/{name}.java').read_text(encoding='utf-8')
    for m in re.finditer(r'REGISTER\.register(?:Block|Item)\(\s*"([a-z0-9_]+)"', text):
        if f'{prefix}.itemcasino.{m.group(1)}' not in lang:
            problems.append(f'missing name: {prefix}.itemcasino.{m.group(1)}')

functions = set(re.findall(r'REGISTER\.register\("([a-z0-9_]+)"',
                           (JAVA / 'com/itemcasino/gametest/CasinoTestFunctions.java').read_text(encoding='utf-8')))
instances = {}
for f in (RES / 'data/itemcasino/test_instance').glob('*.json'):
    instances[f.stem] = json.loads(f.read_text(encoding='utf-8')).get('function', '')
for fn in sorted(functions):
    if f'itemcasino:{fn}' not in instances.values():
        problems.append(f'game test function without a test_instance: {fn}')
for stem, fn in sorted(instances.items()):
    if fn.split(':', 1)[-1] not in functions:
        problems.append(f'test_instance {stem} names an unregistered function {fn}')

for f in JAVA.rglob('*.java'):
    rel = f.relative_to(JAVA).as_posix()
    if rel.startswith('com/itemcasino/client/'):
        continue
    if re.search(r'^import net\.minecraft\.client\.', f.read_text(encoding='utf-8'), re.M):
        problems.append(f'client import outside the client package: {rel}')

for p in problems:
    print('PROBLEM', p)
print(f"static checks: {len(list(RES.rglob('*.json')))} json files, {len(used)} translation keys, "
      f"{len(functions)} game tests, {len(problems)} problem(s)")
sys.exit(min(len(problems), 100))
