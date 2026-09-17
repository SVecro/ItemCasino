#!/usr/bin/env python3
"""
Find mod methods that override a vanilla method, narrow its return type, and get that narrower
type by *casting*. That exact shape crashed the client the first time Gilded Gil was summoned.

`SessionHost` declared `ServerLevel level()`. `GamblerGoblin` implemented it as
`return (ServerLevel) super.level();`. `Entity` already had `Level level()`, so Java took this as a
legal covariant override — and every vanilla call to `level()` on the client (the renderer, then
the crash-report builder trying to describe the failure) ran that cast against a `ClientLevel`.

Covariant overrides are normally fine and the compiler says nothing. What makes this one a bug is
the cast: the narrower type is a *sibling* of the one the caller will actually hold, so whether the
method works is decided by which side of the client/server line you are on. An override that simply
returns an already-narrow value (`codec()` returning `MapCodec<? extends AbstractCasinoBlock>`) is
the honest kind and is not flagged.

Usage: python3 tools/audit_overrides.py <path-to-neoforge-merged.jar>
"""
import re, subprocess, sys, pathlib, collections

JAR = sys.argv[1]
SRC = pathlib.Path('src/main/java')

CLASS = re.compile(r'^(?:public\s+)?(?:final\s+|abstract\s+)*class\s+([\w$]+)[^{]*?\bextends\s+([\w.$]+)',
                   re.M)
DECL = re.compile(r'^[ \t]*(?:public|protected)\s+(?:final\s+)*([\w.$<>\[\],? ]+?)\s+([\w$]+)\(([^)]*)\)\s*\{',
                  re.M)
SIG = re.compile(r'^\s+(?:public|protected)\s+(?:[\w\s]*?\s)?([\w.$<>\[\],? ]+?)\s+([\w$]+)\(([^)]*)\);')

# ---------------------------------------------------------------- what each mod class extends
sources = {}
for path in SRC.rglob('*.java'):
    text = path.read_text(encoding='utf-8')
    # Blank the comments but keep every newline, so reported line numbers match the real file.
    body = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), text, flags=re.S)
    body = re.sub(r'//[^\n]*', '', body)
    m = CLASS.search(body)
    if not m:
        continue
    simple, parent = m.group(1), m.group(2).split('<')[0]
    imports = {simple_name: f'{pkg}.{simple_name}' for pkg, simple_name
               in re.findall(r'^import\s+([\w.$]+)\.([\w$]+);', body, re.M)}
    sources[simple] = (path, body, imports.get(parent, parent))

def vanilla_root(simple, seen=()):
    """Walk up through mod classes until we land on a net.minecraft.* supertype."""
    if simple in seen:
        return None
    entry = sources.get(simple)
    if entry is None:
        return None
    parent = entry[2]
    if parent.startswith('net.minecraft.'):
        return parent
    return vanilla_root(parent.rsplit('.', 1)[-1], seen + (simple,))

# ---------------------------------------------------------------- vanilla methods, per class chain
HEAD = re.compile(r'\bclass\s+[\w.$]+(?:<[^>]*>)?\s+extends\s+([\w.$]+)')

cache = {}
def methods_of(binary):
    """Every public/protected method visible on `binary`, inherited ones included.

    javap lists only what a class declares, so a check that stopped at PathfinderMob would never
    see Entity.level() — which is the method this audit exists to protect. Walk the chain."""
    if binary in cache:
        return cache[binary]
    table = collections.defaultdict(set)
    seen, current = set(), binary
    while current and current.startswith('net.') and current not in seen:
        seen.add(current)
        out = subprocess.run(['javap', '-cp', JAR, current], capture_output=True, text=True).stdout
        if not out.strip():
            break
        for line in out.splitlines():
            m = SIG.match(line)
            if m:
                args = m.group(3).strip()
                table[m.group(2)].add((0 if not args else args.count(',') + 1, m.group(1).strip()))
        head = HEAD.search(out)
        current = head.group(1) if head else None
    cache[binary] = table
    return table

def body_after(body, index):
    """The method body starting at the opening brace at `index`, by brace counting."""
    depth, out = 0, []
    for ch in body[index:]:
        out.append(ch)
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                break
    return ''.join(out)

hits = []
for simple, (path, body, _) in sorted(sources.items()):
    root = vanilla_root(simple)
    if root is None:
        continue
    table = methods_of(root)
    if not table:
        continue
    for m in DECL.finditer(body):
        ret, name, args = m.group(1).strip(), m.group(2), m.group(3).strip()
        if name not in table:
            continue
        arity = 0 if not args else args.count(',') + 1
        for v_arity, v_ret in table[name]:
            if v_arity != arity or v_ret.rsplit('.', 1)[-1] == ret.rsplit('.', 1)[-1]:
                continue
            # The tell: the body produces the narrow type by casting.
            if re.search(r'\(\s*' + re.escape(ret.split('<')[0]) + r'\s*\)\s*[\w(]',
                         body_after(body, m.end() - 1)):
                line = body.count('\n', 0, m.start()) + 1
                hits.append((path, line, name, ret, v_ret, root))

for path, line, name, ret, v_ret, root in hits:
    print(f'{path}:{line}: {name}() narrows {v_ret} to {ret} by cast, '
          f'but {root} declares it and vanilla calls it on both sides')
print(f'{len(hits)} narrowing-by-cast override(s) found.')
sys.exit(1 if hits else 0)
