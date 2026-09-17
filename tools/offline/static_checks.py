#!/usr/bin/env python3
"""Cheap checks that catch what the compiler does not, before asking Rémi for a build.

* every JSON under src/main/resources parses;
* every translation key the Java code names exists in en_us.json, and every block and item has a name;
* every registered game-test function has a test_instance JSON and the other way round (a function
  without its instance never runs, and the suite still says "all passed");
* no net.minecraft.client import outside com.itemcasino.client (a dedicated server would crash);
* no base_value entry is an object with a "value" field (NeoForge reads that as its own
  {"value", "replace"} wrapper and silently drops "fixed"; pinned entries use "points");
* no translation key in en_us.json that nothing reads (keys built at run time are listed below);
* every tag entry that names a minecraft item names one that exists in the merged jar, when the jar
  is known (a tag with a missing required entry fails to load, and takes the tag with it).

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

base_values = json.loads((RES / 'data/itemcasino/data_maps/item/base_value.json').read_text(encoding='utf-8'))
for key, entry in base_values.get('values', {}).items():
    if isinstance(entry, dict) and 'value' in entry:
        problems.append(f'base_value {key}: an object entry must say "points", not "value" '
                        f'(NeoForge takes "value" for its own wrapper and drops "fixed")')
    if isinstance(entry, dict) and set(entry) - {'points', 'fixed'}:
        problems.append(f'base_value {key}: unknown field(s) {sorted(set(entry) - {"points", "fixed"})}')

# Keys the code assembles at run time rather than naming whole.
DYNAMIC = (r'^itemcasino\.button\.(hit|stand|double|surrender)$', r'^itemcasino\.outcome\.',
           r'^itemcasino\.configuration\.', r'^(block|item|entity|container|itemgroup)\.itemcasino',
           r'^itemcasino\.tooltip\.block\.')
for key in sorted(set(lang) - used):
    if not any(re.search(p, key) for p in DYNAMIC):
        problems.append(f'unused translation: {key}')

merged = ROOT / '.offline/merged-jar.txt'
if merged.exists():
    import zipfile
    try:
        with zipfile.ZipFile(merged.read_text().strip()) as jar:
            items_class = jar.read('net/minecraft/world/item/Items.class').decode('latin-1')
    except Exception:
        items_class = None
    if items_class:
        for f in (RES / 'data/itemcasino/tags/item').glob('*.json'):
            for entry in json.loads(f.read_text(encoding='utf-8')).get('values', []):
                if isinstance(entry, dict):
                    if not entry.get('required', True):
                        continue
                    entry = entry['id']
                if entry.startswith('minecraft:') and entry.upper().split(':', 1)[1] not in items_class:
                    problems.append(f'tag {f.name}: {entry} is not a vanilla item')

for p in problems:

    print('PROBLEM', p)
print(f"static checks: {len(list(RES.rglob('*.json')))} json files, {len(used)} translation keys, "
      f"{len(functions)} game tests, {len(problems)} problem(s)")
sys.exit(min(len(problems), 100))
