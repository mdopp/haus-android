#!/usr/bin/env python3
"""Dead public code: a declaration nobody outside its own file names.

Kotlin has no dead-code tool for public declarations (detekt sees only
private ones), so this script looks by name. It takes every non-private
declaration of the app's production code at the top level of a file, and
the members of a top-level `object` (the app's utility functions), and
searches every other file — Kotlin and XML under app/src, the manifest,
e2e/ and the workflows — for the name as a word.

A declaration no other file names is reported as one of:
  dead     its own file does not use it either: delete it;
  private  only its own file uses it: make it private (then detekt's
           unused-private rules see it from there on).
A name is a word, so a second declaration of the same name elsewhere hides
a dead one: the script misses some, and never reports a used one as dead.

The ratchet (CLAUDE.md "Code gates"): scripts/deadcode-baseline.txt lists
the findings from before the gate. A finding not in it fails; an entry that
is no longer found fails too, until it is deleted from the baseline in the
same change. `--write-baseline` rewrites the file from today's findings.
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
PROD = 'app/src/main/java'
SEARCH = ['app/src', 'e2e', '.github']
BASELINE = 'scripts/deadcode-baseline.txt'

MODS = r'(?:(?:public|internal|data|sealed|enum|abstract|open|inline|value|annotation|const|lateinit|operator|infix|suspend|tailrec|external)\s+)*'
DECL = re.compile(
    r'^(?P<ind>(?: {4})?)' + MODS +
    r'(?P<kind>class|object|interface|fun interface|fun|val|var|typealias)\s+'
    r'(?:<[^>]*>\s*)?'                       # generic parameters
    r'(?:[\w.]+(?:<[^>]*>)?\??\.)?'          # an extension's receiver
    r'(?P<name>[A-Za-z_]\w*)')
TOP_OBJECT = re.compile(r'^' + MODS + r'object\s+(\w+)')


def files(dirs, exts):
    for d in dirs:
        for base, subdirs, names in os.walk(os.path.join(ROOT, d)):
            subdirs[:] = [s for s in subdirs if s != 'build']
            for n in names:
                if n.endswith(exts):
                    yield os.path.relpath(os.path.join(base, n), ROOT)


def declarations(path, lines):
    """(name, line) of the declarations the gate looks at."""
    in_object = False
    for i, line in enumerate(lines, 1):
        if line and not line[0].isspace() and not line.startswith('}'):
            in_object = bool(TOP_OBJECT.match(line))
        m = DECL.match(line)
        if not m or line.lstrip().startswith(('private', 'override', 'protected')):
            continue
        if m.group('ind') and not in_object:
            continue
        if m.group('name') == 'companion':
            continue
        yield m.group('name'), i


def findings():
    texts = {p: open(os.path.join(ROOT, p), encoding='utf-8', errors='replace').read()
             for p in files(SEARCH, ('.kt', '.xml', '.sh', '.py', '.yml'))}
    out = []
    for path in sorted(p for p in texts if p.startswith(PROD) and p.endswith('.kt')):
        own = texts[path]
        lines = own.split('\n')
        for name, line in declarations(path, lines):
            word = re.compile(r'(?<![\w])' + re.escape(name) + r'(?![\w])')
            if any(word.search(t) for p, t in texts.items() if p != path):
                continue
            uses = len(word.findall(own)) - 1
            out.append(('private' if uses > 0 else 'dead', path[len(PROD) + 1:], name, line))
    return out


def main():
    os.chdir(ROOT)
    found = findings()
    keys = {f'{kind} {path} {name}' for kind, path, name, _ in found}
    if '--write-baseline' in sys.argv:
        with open(BASELINE, 'w') as f:
            f.write('# scripts/deadcode.py: findings from before the gate; shrink it, never grow it\n')
            f.writelines(k + '\n' for k in sorted(keys))
        print(f'{BASELINE}: {len(keys)} entries')
        return 0
    if '--list' in sys.argv:
        for kind, path, name, line in found:
            print(f'{kind:8} {path}:{line} {name}')
        return 0
    base = set()
    if os.path.exists(BASELINE):
        base = {l.strip() for l in open(BASELINE) if l.strip() and not l.startswith('#')}
    new = sorted(keys - base)
    gone = sorted(base - keys)
    for k in new:
        kind, path, name = k.split(' ')
        hint = 'nothing names it: delete it' if kind == 'dead' else 'only its own file names it: make it private'
        print(f'new finding: {PROD}/{path}: {name}: {hint}')
    for k in gone:
        print(f'fixed, delete this line from {BASELINE}: {k}')
    print(f'dead code: {len(keys)} findings, baseline {len(base)}, new {len(new)}, fixed {len(gone)}')
    return 1 if new or gone else 0


if __name__ == '__main__':
    sys.exit(main())
