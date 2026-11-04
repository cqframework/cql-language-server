#!/usr/bin/env python3
"""update_cqf_fhir_cr_version.py — bump ``<cqf-fhir-cr.version>``.

When ``cqf-fhir-cr.version`` in ``pom.xml`` changes, the script updates the
property value in the parent pom.

Usage
-----
    update_cqf_fhir_cr_version.py <new-version>                # apply bump
    update_cqf_fhir_cr_version.py <new-version> --dry-run      # print diff, no writes
    update_cqf_fhir_cr_version.py --check                       # CI drift check

The script discovers the repository root from its own location
(``scripts/update_cqf_fhir_cr_version.py`` -> ``cql-language-server/``) but accepts
``--repo-root <path>`` for synthetic fixtures used by the test suite.

Stdlib only.
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

# New version must look like a Maven coordinate: N.N.N, optionally with -SNAPSHOT,
# -RCx, +metadata, etc.
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
FILE_SPECS = [
    _spec(
        "pom.xml",
        r'<cqf-fhir-cr\.version>{old}</cqf-fhir-cr\.version>',
        '<cqf-fhir-cr.version>{new}</cqf-fhir-cr.version>',
        re.compile(r'<cqf-fhir-cr\.version>([^<]+)</cqf-fhir-cr\.version>'),
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
    # newline="" prevents Python from re-translating "\n" to os.linesep on write.
    with path.open("w", encoding="utf-8", newline="") as fh:
        fh.write(content)


# ---------------------------------------------------------------------------
# Version detection & replacement
# ---------------------------------------------------------------------------


def detect_old_version(repo_root: Path) -> str:
    pom = repo_root / "pom.xml"
    if not pom.is_file():
        raise FileNotFoundError(f"pom.xml not found at {pom}")

    text = read_text(pom)
    matches = FILE_SPECS[0]["check_re"].findall(text)
    if not matches:
        raise RuntimeError(f"<cqf-fhir-cr.version> not found in {pom}")
    if len(matches) > 1:
        raise RuntimeError(
            f"Multiple <cqf-fhir-cr.version> entries in {pom} — review by hand"
        )
    return matches[0]


def search_re(spec: dict, old: str) -> re.Pattern[str]:
    return re.compile(spec["search_tmpl"].format(old=re.escape(old)))


def new_already_re(spec: dict, new: str) -> re.Pattern[str]:
    """Pattern that matches if the file already contains the new version."""

    return re.compile(spec["search_tmpl"].format(old=re.escape(new)))


def replace_in_file(
    spec: dict, repo_root: Path, old: str, new: str, *, write: bool
) -> tuple[bool, str, str]:
    """Replace ``old`` with ``new`` in one file.

    Returns ``(modified, old_text, new_text)``. Caller decides whether to write.
    """

    path = repo_root / spec["rel"]
    if not path.is_file():
        raise FileNotFoundError(f"{spec['rel']} not found at {path}")

    old_text = read_text(path)

    search_pat = search_re(spec, old)
    new_pat = new_already_re(spec, new)

    # Already up to date
    if new_pat.search(old_text):
        return False, old_text, old_text

    hits = search_pat.findall(old_text)
    if not hits:
        raise RuntimeError(
            f"{spec['rel']}: contains neither old version ({old!r}) nor new ({new!r}) — "
            f"manual review required (possible drift or unanticipated format change)"
        )
    if len(hits) > 1:
        raise RuntimeError(
            f"{spec['rel']}: contains {len(hits)} occurrences of {old!r}; expected exactly 1"
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


# ---------------------------------------------------------------------------
# Modes
# ---------------------------------------------------------------------------


def mode_check(repo_root: Path) -> int:
    """Verify pom.xml reports the cqf-fhir-cr.version property."""

    pom = repo_root / "pom.xml"
    if not pom.is_file():
        print(f"error: pom.xml not found at {pom}", file=sys.stderr)
        return 2

    try:
        version = detect_old_version(repo_root)
        print(f"OK: cqf-fhir-cr.version = {version}")
        return 0
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


def mode_bump(
    repo_root: Path,
    new_version: str,
    *,
    dry_run: bool,
    old_override: Optional[str],
) -> int:
    """Apply the bump across pom files."""

    if not VERSION_REGEX.match(new_version):
        print(
            f"error: {new_version!r} does not match N.N.N[ -+suffix] "
            f"(e.g. 4.14.0, 4.14.0-SNAPSHOT)",
            file=sys.stderr,
        )
        return 2

    old_version = old_override or detect_old_version(repo_root)
    if old_version == new_version:
        print(f"info: new version equals current ({old_version}); nothing to do")
        return 0

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
            spec, repo_root, old_version, new_version, write=not dry_run
        )
        if not modified:
            skipped.append(spec["rel"])
            continue
        modified_count += 1
        diffs.append(unified_diff(spec, old_text, new_text))

    if dry_run:
        print(f"--- DRY RUN: would bump cqf-fhir-cr {old_version} -> {new_version} ---")
        for spec in FILE_SPECS:
            tag = "(skip)" if spec["rel"] in skipped else "(diff)"
            print(f"  {tag} {spec['rel']}")
        print()
        for d in diffs:
            sys.stdout.write(d)
        if not diffs:
            print("(no diffs — all files already target new version)")
        return 0

    print(f"Bumped cqf-fhir-cr {old_version} -> {new_version} in {modified_count} file(s).")
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
        prog="update_cqf_fhir_cr_version.py",
        description=(
            "Bump <cqf-fhir-cr.version> in pom.xml."
        ),
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "new_version",
        nargs="?",
        help="New cqf-fhir-cr version, e.g. 4.14.0 or 4.14.0-SNAPSHOT",
    )
    group.add_argument(
        "--check",
        action="store_true",
        help="Verify pom.xml reports the cqf-fhir-cr.version property; exit 1 on error",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print unified diff but do not write any files",
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

    try:
        if args.check:
            return mode_check(repo_root)
        return mode_bump(
            repo_root,
            args.new_version,
            dry_run=args.dry_run,
            old_override=args.old,
        )
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
