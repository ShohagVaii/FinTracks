#!/usr/bin/env python3
"""Counts Bengali-literal lines in Android UI code that are NOT wrapped in tr().

Run from the repo root:  python3 scripts/lang_scan.py [-v]
Rule: a line counts when it has a Bengali character (U+0980-U+09FF), does not contain "tr(",
and is not a comment line (starts with //, * or /**). -v prints every counted line.
"""
import glob, os, re, sys

UI = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "java", "com", "fintracks", "app", "ui")
bn = re.compile(r"[\u0980-\u09FF]")
total, per = 0, {}
for f in sorted(glob.glob(os.path.join(UI, "*.kt"))):
    for i, line in enumerate(open(f, encoding="utf-8"), 1):
        if not bn.search(line) or "tr(" in line:
            continue
        if line.strip().startswith(("//", "*", "/**")):
            continue
        total += 1
        name = os.path.basename(f)
        per[name] = per.get(name, 0) + 1
        if "-v" in sys.argv:
            print(f"{name}:{i}: {line.strip()[:110]}")
print("TOTAL", total)
for name, c in sorted(per.items(), key=lambda x: -x[1]):
    print(f"  {c:3d} {name}")
