#!/usr/bin/env python3
"""Checks that the APK and the code in this repository carry no copyleft licence (GPL, LGPL, AGPL and their kind).

Latent is under Apache 2.0 and is meant for Play: what ships has to allow that. A GPL library would oblige the whole
app to be GPL, and an LGPL one asks for relinking that an APK makes hard, so neither comes in without the owner's
decision. Three things are checked:

1. The libraries of the release build (releaseRuntimeClasspath, which is what ships): the licences their POMs declare,
   or their parent POM's, are read from Google's Maven and Maven Central. Every one has to be on the permissive list
   below. A library whose POM says nothing, or something not recognised, needs a line in app/license-exceptions.txt
   with the licence a person read on its project page; that licence is held to the same list.
2. The vendored code under third_party/: each folder has a licence file whose text is a permissive one.
3. The repository's own files: no source file carries a GPL header or SPDX tag. The licence texts that ship in
   app/src/main/assets/licenses/ are left out, since LiteRT's notices quote the GPL for a part dual-licensed under it.

Run it from anywhere; it runs Gradle itself. `--dependencies FILE` reads the output of
`./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath` from a file instead.
CI runs it in .github/workflows/licenses.yml. Changing what counts as allowed is a change of what CI checks, and is
reviewed (AGENTS.md).
"""

import argparse
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXCEPTIONS = ROOT / "app" / "license-exceptions.txt"
REPOSITORIES = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo.maven.apache.org/maven2",
]

# The licences Latent may ship with, as SPDX identifiers.
ALLOWED = {"Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Unicode-3.0", "Unicode-DFS-2016", "CC0-1.0",
           "Zlib", "0BSD", "Public-Domain"}

# A licence's name or URL, as POMs write them, to its SPDX identifier. The first match wins, so the copyleft ones,
# whose names hold words of the others ("GNU Lesser General Public License" has "General Public License"), come first.
PATTERNS = [
    (r"affero|\bagpl", "AGPL"),
    (r"lesser general public|library general public|\blgpl", "LGPL"),
    (r"general public licen|\bgpl|gnu\.org/licenses", "GPL"),
    (r"server side public|\bsspl", "SSPL"),
    (r"european union public|\beupl", "EUPL"),
    (r"mozilla public|\bmpl\b|mozilla\.org/mpl", "MPL"),
    (r"common development and distribution|\bcddl", "CDDL"),
    (r"eclipse public|\bepl\b|eclipse\.org/legal/epl", "EPL"),
    (r"non-?commercial|creativecommons\.org/licenses/by-nc|\bcc[- ]by-nc", "NonCommercial"),
    (r"apache.*2|apache\.org/licenses/license-2\.0|\basl\s*2|\bapache-2\.0", "Apache-2.0"),
    (r"\bmit\b|opensource\.org/licenses/mit", "MIT"),
    (r"bsd.*2[- ]clause|simplified bsd|freebsd|opensource\.org/licenses/bsd-2", "BSD-2-Clause"),
    (r"bsd.*3[- ]clause|new bsd|revised bsd|modified bsd|opensource\.org/licenses/bsd-3|^bsd licen[cs]e$|^bsd$",
     "BSD-3-Clause"),
    (r"\bisc\b", "ISC"),
    (r"unicode", "Unicode-3.0"),
    (r"\bcc0\b|creativecommons\.org/publicdomain/zero", "CC0-1.0"),
    (r"zlib", "Zlib"),
    (r"public domain", "Public-Domain"),
]

# A licence file's text to its licence, for the vendored code. Again copyleft first.
TEXTS = [
    (r"GNU AFFERO GENERAL PUBLIC LICENSE", "AGPL"),
    (r"GNU LESSER GENERAL PUBLIC LICENSE|GNU LIBRARY GENERAL PUBLIC LICENSE", "LGPL"),
    (r"GNU GENERAL PUBLIC LICENSE", "GPL"),
    (r"Mozilla Public License", "MPL"),
    (r"Apache License,?\s+Version 2\.0", "Apache-2.0"),
    (r"Permission is hereby granted, free of charge, to any person obtaining a copy", "MIT"),
    (r"Redistribution and use in source and binary forms", "BSD-3-Clause"),
    (r"Permission to use, copy, modify, and/or distribute this software for any", "ISC"),
]

# A copyleft header or tag in a source file of the repository.
COPYLEFT_MARK = re.compile(
    r"SPDX-License-Identifier:[^\n]*\b(?:A|L)?GPL|GNU (?:Affero |Lesser |Library )?General Public License"
    r"|under the terms of the GNU", re.IGNORECASE)
SOURCE_SUFFIXES = {".kt", ".kts", ".java", ".c", ".cc", ".cpp", ".h", ".hpp", ".glsl", ".comp", ".frag", ".vert",
                   ".py", ".sh", ".js", ".gradle", ".pro", ".xml", ".patch"}
NOT_SCANNED = ("app/src/main/assets/licenses/", "scripts/check-licenses.py")

DEPENDENCY = re.compile(r"^[| +\\-]*--- (?P<coordinate>[^\s]+)(?: -> (?P<resolved>[^\s]+))?(?P<marks>(?: \([a-z*]\))*)$")


def error(message):
    print(f"::error::{message}")


def classify(text):
    lowered = text.strip().lower()
    for pattern, spdx in PATTERNS:
        if re.search(pattern, lowered):
            return spdx
    return None


def modules_of(tree):
    """The group:name:version of each library in a Gradle dependency tree, with the version Gradle chose."""
    modules = set()
    for line in tree.splitlines():
        match = DEPENDENCY.match(line.rstrip())
        if not match:
            continue
        coordinate, resolved, marks = match["coordinate"], match["resolved"], match["marks"]
        # A constraint is not a library, and "(n)" is one Gradle did not resolve.
        if "(c)" in marks or "(n)" in marks or coordinate == "project":
            continue
        parts = coordinate.split(":")
        if len(parts) < 2:
            continue
        group, name = parts[0], parts[1]
        if resolved and ":" not in resolved:
            version = resolved
        elif resolved:
            # "a:b:1 -> c:d:2", a library put in another's place.
            group, name, version = resolved.split(":")[:3]
        elif len(parts) >= 3:
            version = parts[2]
        else:
            continue
        modules.add((group, name, version))
    return modules


_poms = {}


def download(url):
    """A POM, parsed, or None if the repository does not have it. A repository that asks to slow down is asked again."""
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return ET.fromstring(response.read())
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code != 429 and e.code < 500 or attempt == 4:
                raise
        time.sleep(2 ** attempt)


def fetch_pom(group, name, version):
    key = (group, name, version)
    if key in _poms:
        return _poms[key]
    path = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}.pom"
    pom = None
    for repository in REPOSITORIES:
        pom = download(f"{repository}/{path}")
        if pom is not None:
            break
    _poms[key] = pom
    return pom


def local(tag):
    return tag.rsplit("}", 1)[-1]


def child(element, name):
    for c in element:
        if local(c.tag) == name:
            return c
    return None


def licenses_of(group, name, version, depth=0):
    """The licences a POM declares, or the nearest parent's: [(name, url)], or None if its POM was not found."""
    pom = fetch_pom(group, name, version)
    if pom is None:
        return None
    declared = child(pom, "licenses")
    found = []
    if declared is not None:
        for licence in declared:
            if local(licence.tag) != "license":
                continue
            n, u = child(licence, "name"), child(licence, "url")
            found.append(((n.text or "").strip() if n is not None else "", (u.text or "").strip() if u is not None else ""))
    if found or depth >= 10:
        return found
    parent = child(pom, "parent")
    if parent is None:
        return found
    g, a, v = (child(parent, k) for k in ("groupId", "artifactId", "version"))
    if g is None or a is None or v is None:
        return found
    return licenses_of(g.text.strip(), a.text.strip(), v.text.strip(), depth + 1) or []


def read_exceptions():
    exceptions = {}
    if not EXCEPTIONS.is_file():
        return exceptions
    for number, line in enumerate(EXCEPTIONS.read_text().splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2 or parts[0].count(":") != 1:
            error(f"{EXCEPTIONS.relative_to(ROOT)}:{number}: expected 'group:name SPDX-id', found '{line}'.")
            sys.exit(1)
        exceptions[parts[0]] = parts[1]
    return exceptions


def check_libraries(tree, summary):
    modules = sorted(modules_of(tree))
    if not modules:
        error("No library was found in the release build's dependency list, so none was checked.")
        return False
    exceptions = read_exceptions()
    ok = True
    used_exceptions = set()
    # The POMs are fetched side by side first; a hundred one after the other take a minute.
    with ThreadPoolExecutor(max_workers=4) as pool:
        declarations = list(pool.map(lambda m: licenses_of(*m), modules))
    summary.append("| Library | Declared | Licence | |\n|---|---|---|---|")
    for (group, name, version), declared in zip(modules, declarations):
        module = f"{group}:{name}"
        if declared is None:
            error(f"{module}:{version}: its POM was found in neither Google's Maven nor Maven Central.")
            summary.append(f"| `{module}:{version}` | POM not found | | ❌ |")
            ok = False
            continue
        if module in exceptions:
            used_exceptions.add(module)
            spdx = [exceptions[module]]
            source = "app/license-exceptions.txt"
        else:
            spdx = [classify(n) or classify(u) for n, u in declared]
            source = "; ".join(n or u for n, u in declared) or "nothing"
        if not spdx or None in spdx:
            error(f"{module}:{version}: its POM declares {source!r}, which is not a licence this check knows. Read the "
                  f"licence on the project's page and, if it is permissive, add '{module} <SPDX-id>' to "
                  f"app/license-exceptions.txt.")
            verdict = "❓"
            ok = False
        elif any(s not in ALLOWED for s in spdx):
            bad = ", ".join(s for s in spdx if s not in ALLOWED)
            error(f"{module}:{version} is under {bad}, which Latent may not ship without the owner's decision.")
            verdict = "❌"
            ok = False
        else:
            verdict = "✅"
        summary.append(f"| `{module}:{version}` | {source} | {', '.join(s or '?' for s in spdx)} | {verdict} |")
    for module in sorted(set(exceptions) - used_exceptions):
        print(f"::warning::app/license-exceptions.txt names {module}, which the release build no longer has.")
    print(f"{len(modules)} libraries of the release build checked.")
    return ok


def check_vendored(summary):
    ok = True
    third_party = ROOT / "third_party"
    folders = sorted(p for p in third_party.iterdir() if p.is_dir()) if third_party.is_dir() else []
    summary.append("\n| Vendored | Licence | |\n|---|---|---|")
    for folder in folders:
        files = [p for p in folder.iterdir() if p.is_file() and re.match(r"(LICEN[CS]E|COPYING)", p.name, re.I)]
        found = set()
        for f in files:
            text = f.read_text(errors="replace")
            for pattern, spdx in TEXTS:
                if re.search(pattern, text, re.IGNORECASE):
                    found.add(spdx)
                    break
        name = folder.relative_to(ROOT)
        if not files:
            error(f"{name} has no LICENSE file, so its licence is not known.")
            ok = False
        elif not found or not found <= ALLOWED:
            error(f"{name} is under {', '.join(sorted(found)) or 'a licence this check does not know'}, which Latent "
                  f"may not ship without the owner's decision.")
            ok = False
        summary.append(f"| `{name}` | {', '.join(sorted(found)) or '?'} | {'✅' if found and found <= ALLOWED else '❌'} |")
    return ok


def check_sources():
    files = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True).stdout
    ok = True
    for path in files.decode().split("\0"):
        if not path or path.startswith(NOT_SCANNED):
            continue
        p = ROOT / path
        if p.suffix.lower() not in SOURCE_SUFFIXES and not re.match(r"(LICEN[CS]E|COPYING)", p.name, re.I):
            continue
        if not p.is_file():
            continue
        for number, line in enumerate(p.read_text(errors="replace").splitlines(), 1):
            if COPYLEFT_MARK.search(line):
                print(f"::error file={path},line={number}::A copyleft licence is named here: {line.strip()[:120]}")
                ok = False
                break
    return ok


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--dependencies", type=Path, help="the release build's dependency tree, read from this file")
    args = parser.parse_args()

    if args.dependencies:
        tree = args.dependencies.read_text()
    else:
        listing = subprocess.run(
            ["./gradlew", "-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath"],
            cwd=ROOT, capture_output=True, text=True)
        if listing.returncode != 0:
            sys.stderr.write(listing.stderr)
            error("Could not list the release build's libraries, so they were not checked.")
            return 1
        tree = listing.stdout

    summary = ["## Licences\n"]
    ok = check_libraries(tree, summary)
    ok = check_vendored(summary) and ok
    ok = check_sources() and ok

    report = "\n".join(summary) + "\n"
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(report)
    else:
        print(report)
    if ok:
        print("Licence checks passed: no copyleft or unknown licence among the release build's libraries, the "
              "vendored code or the repository's sources.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
