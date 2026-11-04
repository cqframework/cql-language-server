#!/usr/bin/env python3
"""update_project_version.py — bump the cql-language-server project version.

When ``<version>`` in ``pom.xml`` changes, every submodule's ``<parent><version>``
reference must follow, or Maven refuses to resolve the build.

Files this script edits:

- ``pom.xml`` — top-level ``<version>`` (the cql-ls artifactId)
- ``ls/server/pom.xml`` — ``<parent><version>`` reference (literal, not a
  property, so it must be rewritten by hand)

The submodule regex scopes to ``<parent>...</version>`` so we never
accidentally hit a ``<dependency><version>`` entry.

Usage
-----
    update_project_version.py <new-version>                 # apply bump
    update_project_version.py <new-version> --dry-run       # print diff, no writes
    update_project_version.py --check                       # CI drift check
    update_project_version.py <v> --snapshot                # append -SNAPSHOT if absent
    update_project_version.py --old <v>                     # escape hatch

Stdlib only. Coexists with ``setversion.sh``; either can be used to bump.
"""

from __future__ import annotations

import argparse
import difflib
import re
import sys
from pathlib import Path
from typing import Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_REPO_ROOT = SCRIPT_DIR.parent  # cql-language-server/

# Maven coordinate pattern: N.N.N with optional -SNAPSHOT, -RCx, +metadata, etc.
VERSION_REGEX = re.compile(r"^\d+\.\d+\.\d+([\-+].*)?$")


def _spec(
    rel: str,
    search_tmpl: str,
    replace_tmpl: str,
    check_re: re.Pattern[str],
) -> dict:
    return {
        "rel": rel,
        "search_tmpl": search_tmpl,
        "replace_tmpl": replace_tmpl,
        "check_re": check_re,
    }


# Per-file replacement spec.
#
# The parent pom.xml uses a plain <version>{old}</version> match (the project
# version precedes any <dependencyManagement><dependencies>...<version> entries,
# so it's the first match by Maven convention). The submodule scopes its regex
# to <parent>...</version> so the search anchors inside the <parent> block —
# dependency <version> entries that happen to coincide with the old artifact
# version are never touched.
#
# Both files share the same check regex: <project>...</version> matches the
# first <version> element after <project>, which is the project version by
# Maven convention.
FILE_SPECS = [
    _spec(
        "pom.xml",
        r'<version>{old}</version>',
        '<version>{new}</version>',
        # Capture group 0 is the optional attribute list, group 1 is the
        # version text. (\s[^>]*)? allows <project> with no attrs and
        # <project xmlns=...>, but does NOT match <project.build.sourceEncoding>
        # because the character after <project must be whitespace (or >).
        re.compile(r'<project(\s[^>]*)?>[\s\S]*?<version>([^<]+)</version>'),
    ),
    _spec(
        "ls/server/pom.xml",
        r'(<parent>[\s\S]*?)<version>{old}</version>',
        r'\1<version>{new}</version>',
        re.compile(r'<project(\s[^>]*)?>[\s\S]*?<version>([^<]+)</version>'),
    ),
]


# ---------------------------------------------------------------------------
# File helpers
# ---------------------------------------------------------------------------


def read_text(path: Path) -> str:
    # newline="" preserves CRLF vs LF on read so we can write it back byte-for-byte.
    with path.open("r", encoding="utf-8", newline="") as fh:
        return fh.read()


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # newline="" prevents Python re-translating "\n" back to os.linesep on write.
    with path.open("w", encoding="utf-8", newline="") as fh:
        fh.write(content)


# ---------------------------------------------------------------------------
# Detection, replacement, helpers
# ---------------------------------------------------------------------------


def detect_old_version(repo_root: Path) -> str:
    """Detect the project version from the first <version> element in pom.xml.

    Maven convention places the project <version> as the first such element
    in any pom, so a simple regex suffices. The submodule pom is not consulted
    here because Maven always treats the parent as authoritative — if the
    parent and submodule disagree, the parent's value should win.
    """
    pom = repo_root / "pom.xml"
    if not pom.is_file():
        raise FileNotFoundError(f"pom.xml not found at {pom}")

    text = read_text(pom)
    matches = FILE_SPECS[0]["check_re"].findall(text)
    if not matches:
        raise RuntimeError(f"<version> not found in {pom}")
    if len(matches) > 1:
        # Should never happen: <project>...<version> only matches once because
        # findall doesn't backtrack to overlapping matches.
        raise RuntimeError(
            f"Multiple <version> entries inside <project> in {pom} — review by hand"
        )
    # findall returns tuples because the regex has two capture groups;
    # the version text is the second element.
    return matches[0][1]


def search_re(spec: dict, old: str) -> re.Pattern[str]:
    return re.compile(spec["search_tmpl"].format(old=re.escape(old)))


def new_already_re(spec: dict, new: str) -> re.Pattern[str]:
    """Pattern that matches if the file already contains the new version."""

    return re.compile(spec["search_tmpl"].format(old=re.escape(new)))


def replace_in_file(
    spec: dict,
    repo_root: Path,
    old: str,
    new: str,
    *,
    write: bool,
    strict: bool = True,
) -> tuple[bool, str, str]:
    """Replace ``old`` with ``new`` in one file.

    Returns ``(modified, old_text, new_text)``. Caller decides whether to write.

    ``strict=True`` (default): hard error if the file contains neither old nor new.
    ``strict=False``: skip the file with a stderr warning instead — used when the
    caller has supplied ``--old`` to bypass auto-detection, signaling they accept
    reduced safety in exchange for working through nonstandard poms.
    """

    path = repo_root / spec["rel"]
    if not path.is_file():
        raise FileNotFoundError(f"{spec['rel']} not found at {path}")

    old_text = read_text(path)

    search_pat = search_re(spec, old)
    new_pat = new_already_re(spec, new)

    if new_pat.search(old_text):
        return False, old_text, old_text

    hits = search_pat.findall(old_text)
    if not hits:
        msg = (
            f"{spec['rel']}: contains neither old version ({old!r}) nor new ({new!r}) — "
            f"manual review required (possible drift or unanticipated format change)"
        )
        if strict:
            raise RuntimeError(msg)
        print(f"warning: {msg}", file=sys.stderr)
        return False, old_text, old_text

    if len(hits) > 1:
        raise RuntimeError(
            f"{spec['rel']}: contains {len(hits)} occurrences of {old!r} matching the "
            f"search pattern; expected exactly 1"
        )

    replace_sub = spec["replace_tmpl"].format(new=new)
    new_text = search_pat.sub(replace_sub, old_text, count=1)

    if write and new_text != old_text:
        write_text(path, new_text)

    return True, old_text, new_text


def unified_diff(spec: dict, old_text: str, new_text: str) -> str:
    return "".join(
        difflib.unified_diff(
            old_text.splitlines(keepends=True),
            new_text.splitlines(keepends=True),
            fromfile=f"a/{spec['rel']}",
            tofile=f"b/{spec['rel']}",
        )
    )


def _apply_snapshot(new_version: str) -> str:
    """If --snapshot was passed, ensure the version ends in -SNAPSHOT."""
    if new_version.endswith("-SNAPSHOT"):
        return new_version
    return f"{new_version}-SNAPSHOT"


# ---------------------------------------------------------------------------
# Modes
# ---------------------------------------------------------------------------


def mode_check(repo_root: Path) -> int:
    """Verify pom.xml's <version> matches every submodule <parent><version> ref."""

    pom = repo_root / "pom.xml"
    if not pom.is_file():
        print(f"error: pom.xml not found at {pom}", file=sys.stderr)
        return 2

    pom_version = detect_old_version(repo_root)
    failures: list[str] = []

    for spec in FILE_SPECS[1:]:
        path = repo_root / spec["rel"]
        if not path.is_file():
            failures.append(f"{spec['rel']}: MISSING")
            continue
        text = read_text(path)
        matches = spec["check_re"].findall(text)
        if not matches:
            failures.append(f"{spec['rel']}: no project <version> found")
            continue
        if len(matches) > 1:
            failures.append(
                f"{spec['rel']}: multiple <version> matches inside <project>: {matches!r}"
            )
            continue
        # matches is a list of (attrs, version_text) tuples from the regex's
        # two capture groups; the version is the second element.
        version_text = matches[0][1]
        if version_text != pom_version:
            failures.append(
                f"{spec['rel']}: <version>={version_text!r}, expected [{pom_version!r}]"
            )

    if failures:
        print("Drift detected:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print(f"OK: parent and submodule <version> entries both report {pom_version}")
    return 0


def mode_bump(
    repo_root: Path,
    new_version: str,
    *,
    dry_run: bool,
    old_override: Optional[str],
) -> int:
    """Apply the bump across parent + submodule poms."""

    if not VERSION_REGEX.match(new_version):
        print(
            f"error: {new_version!r} does not match N.N.N[ -+suffix] "
            f"(e.g. 4.12.0, 4.12.0-SNAPSHOT)",
            file=sys.stderr,
        )
        return 2

    old_version = old_override or detect_old_version(repo_root)
    if old_version == new_version:
        print(f"info: new version equals current ({old_version}); nothing to do")
        return 0

    # When --old is supplied we soften the per-file "neither old nor new" check —
    # see replace_in_file for the rationale. Strict mode (default) keeps the
    # safety net typical callers expect.
    strict = old_override is None

    # Pre-flight: all target files exist
    missing = [
        spec["rel"] for spec in FILE_SPECS if not (repo_root / spec["rel"]).is_file()
    ]
    if missing:
        print("error: missing target files:", file=sys.stderr)
        for m in missing:
            print(f"  - {m}", file=sys.stderr)
        return 2

    diffs: list[str] = []
    skipped: list[str] = []
    modified_count = 0

    for spec in FILE_SPECS:
        modified, old_text, new_text = replace_in_file(
            spec,
            repo_root,
            old_version,
            new_version,
            write=not dry_run,
            strict=strict,
        )
        if not modified:
            skipped.append(spec["rel"])
            continue
        modified_count += 1
        diffs.append(unified_diff(spec, old_text, new_text))

    if dry_run:
        print(f"--- DRY RUN: would bump {old_version} -> {new_version} ---")
        for spec in FILE_SPECS:
            tag = "(skip)" if spec["rel"] in skipped else "(diff)"
            print(f"  {tag} {spec['rel']}")
        print()
        for d in diffs:
            sys.stdout.write(d)
        if not diffs:
            print("(no diffs — all files already target new version)")
        return 0

    print(f"Bumped {old_version} -> {new_version} in {modified_count} file(s).")
    for s in skipped:
        print(f"  - skipped (already {new_version}): {s}")
    for d in diffs:
        sys.stdout.write(d)
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="update_project_version.py",
        description=(
            "Bump the cql-language-server project <version> across the "
            "parent pom and submodule <parent><version> references."
        ),
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "new_version",
        nargs="?",
        help="New project version, e.g. 4.12.0 or 4.12.0-SNAPSHOT",
    )
    group.add_argument(
        "--check",
        action="store_true",
        help="Verify parent and submodule <version> entries agree; exit 1 on drift",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print unified diff but do not write any files",
    )
    parser.add_argument(
        "--snapshot",
        action="store_true",
        help="Append -SNAPSHOT to the new version if it does not already end in -SNAPSHOT",
    )
    parser.add_argument(
        "--old",
        metavar="VERSION",
        help="Override auto-detected current version (escape hatch)",
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=DEFAULT_REPO_ROOT,
        help=f"Path to cql-language-server/ (default: {DEFAULT_REPO_ROOT})",
    )
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    if not args.check and not args.new_version:
        print(
            "error: provide <new-version> or --check (run with --help for details)",
            file=sys.stderr,
        )
        return 2

    repo_root: Path = args.repo_root.resolve()

    # Normalize the new version *before* validation so --snapshot 4.12.0 is
    # accepted as 4.12.0-SNAPSHOT instead of failing the semver regex on the
    # `-SNAPSHOT` suffix check. The regex actually allows -SNAPSHOT, but we
    # still apply --snapshot first so the user's intent is honored consistently.
    new_version = args.new_version
    if args.snapshot and new_version is not None:
        new_version = _apply_snapshot(new_version)

    try:
        if args.check:
            return mode_check(repo_root)
        return mode_bump(
            repo_root,
            new_version,
            dry_run=args.dry_run,
            old_override=args.old,
        )
    except RuntimeError as exc:
        # "manual review required" diagnostics — exit 2 (same as argparse /
        # missing-file errors) so CI fails clearly instead of dying with
        # Python's default uncaught-exception code 1.
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
