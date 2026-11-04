#!/usr/bin/env python3
"""Split the test classes into balanced shards for parallel CI jobs.

Usage:
  test-shard.py <index 1-based> <total> <outfile>   write shard <index>'s files to <outfile>
  test-shard.py --check <total>                     assert every test file is in exactly one shard

Output lines are paths relative to src/test/java, the format Surefire's includesFile accepts.
Weight = 1 + number of test methods, +25 for a Spring context test (slow context startup).
"""
import fnmatch
import os
import re
import sys

ROOT = "src/test/java"
PATTERNS = ["Test*.java", "*Test.java", "*Tests.java", "*TestCase.java"]
TEST_RE = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest)\b")
SPRING = "@SpringBootTest"
ABSTRACT_RE = re.compile(r"^\s*(?:public\s+)?abstract\s+class\s", re.M)
EXTENDS_RE = re.compile(r"\bclass\s+\w+(?:<[^{]*?>)?\s+extends\s+(\w+)")
CLASS_RE = re.compile(r"^\s*(?:public\s+|final\s+|abstract\s+)*class\s+\w+", re.M)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def collect():
    all_files = {}
    for d, _, names in os.walk(ROOT):
        for n in names:
            if n.endswith(".java"):
                all_files[os.path.relpath(os.path.join(d, n), ROOT)] = read(os.path.join(d, n))
    by_simple = {os.path.basename(p)[:-5]: src for p, src in all_files.items()}
    result = []
    for rel, src in all_files.items():
        base = os.path.basename(rel)
        if not any(fnmatch.fnmatchcase(base, p) for p in PATTERNS):
            continue
        # top-level class abstract?
        m = CLASS_RE.search(src)
        if ABSTRACT_RE.search(src) and (m is None or ABSTRACT_RE.search(src).start() <= m.start()):
            continue
        weight = 1 + len(TEST_RE.findall(src))
        spring = SPRING in src
        if not spring:
            e = EXTENDS_RE.search(src)
            if e and SPRING in by_simple.get(e.group(1), ""):
                spring = True
        if spring:
            weight += 25
        result.append((rel.replace(os.sep, "/"), weight))
    return result


def split(files, total):
    shards = [[] for _ in range(total)]
    sums = [0] * total
    for rel, w in sorted(files, key=lambda x: (-x[1], x[0])):
        i = min(range(total), key=lambda k: (sums[k], k))
        shards[i].append(rel)
        sums[i] += w
    return shards, sums


def main(argv):
    if len(argv) == 3 and argv[1] == "--check":
        total = int(argv[2])
        files = collect()
        shards, sums = split(files, total)
        flat = [f for s in shards for f in s]
        ok = len(flat) == len(set(flat)) == len(files) and set(flat) == {f for f, _ in files}
        ok = ok and all(shards)
        print(f"check: {len(files)} files, {total} shards, ok={ok}")
        return 0 if ok else 1
    if len(argv) == 4:
        idx, total, out = int(argv[1]), int(argv[2]), argv[3]
        if not 1 <= idx <= total:
            print("index must be 1..total", file=sys.stderr)
            return 2
        shards, sums = split(collect(), total)
        with open(out, "w") as f:
            f.write("".join(p + "\n" for p in sorted(shards[idx - 1])))
        for i in range(total):
            print(f"shard {i + 1}/{total}: {len(shards[i])} files, weight {sums[i]}" + (" <- selected" if i + 1 == idx else ""))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
