#!/usr/bin/env python3
"""test_update_cql_version.py — stdlib unittest suite for update_cql_version.py.

Run from cql-language-server/ as::

    python3 -m unittest scripts._tests.test_update_cql_version

or::

    python3 -m unittest discover -s scripts/_tests -p test_update_cql_version.py

Each test synthesizes pom.xml + One.{xml,json,ast} fixtures in a tempdir
and passes --repo-root <tmpdir> to the script, so the test never touches the
real working tree.
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from typing import Iterable

SCRIPT_PATH = Path(__file__).resolve().parent.parent / "update_cql_version.py"


# ---------------------------------------------------------------------------
# Fixture templates
# ---------------------------------------------------------------------------

# Minimal pom.xml with the cql.version property the script will modify.
POM_TEMPLATE = textwrap.dedent(
    """\
    <?xml version="1.0" encoding="UTF-8"?>
    <project>
        <modelVersion>4.0.0</modelVersion>
        <properties>
            <cql.version>{version}</cql.version>
            <other.version>1.0.0</other.version>
        </properties>
    </project>
    """
)

ONE_XML_TEMPLATE = textwrap.dedent(
    """\
    <?xml version="1.0" encoding="UTF-8"?>
    <library xmlns="urn:hl7-org:elm:r1"
             xmlns:a="urn:hl7-org:cql-annotations:r1"
             xmlns:t="urn:hl7-org:elm-types:r1"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" localId="0">
        <annotation signatureLevel="All" translatorOptions="EnableAnnotations,EnableLocators,DisableListDemotion,DisableListPromotion,EnableResultTypes" translatorVersion="{version}" xsi:type="a:CqlToElmInfo"/>
        <identifier id="One"/>
        <schemaIdentifier id="urn:hl7-org:elm" version="r1"/>
    </library>
    """
)

ONE_JSON_TEMPLATE = textwrap.dedent(
    """\
    {{
      "library": {{
        "annotation": [
          {{
            "type": "CqlToElmInfo",
            "translatorVersion": "{version}",
            "translatorOptions": "EnableAnnotations",
            "signatureLevel": "All"
          }}
        ]
      }}
    }}
    """
)

ONE_AST_TEMPLATE = textwrap.dedent(
    """\
    Library: One (version unspecified) [id=0]
    ├── translator: CQL-to-ELM {version}
    ├── schema: urn:hl7-org:elm r1
    └── define: "One" returns System.Integer [id=208, loc=3:1-4:5]
    """
)


def fixture_path(repo_root: Path, rel: str) -> Path:
    return repo_root / rel


# ---------------------------------------------------------------------------
# TestCase
# ---------------------------------------------------------------------------


class UpdateCqlVersionTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo_root = Path(self._tmp.name)
        self._write_repo(version="5.5.0")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    # ----- helpers -------------------------------------------------------

    def _write_repo(self, version: str, *, pom_with_cql: bool = True) -> None:
        pom_body = (
            POM_TEMPLATE.format(version=version)
            if pom_with_cql
            else POM_TEMPLATE.replace(
                f"<cql.version>{version}</cql.version>", ""
            )
        )
        (self.repo_root / "pom.xml").write_text(pom_body, encoding="utf-8")
        one_dir = self.repo_root / "ls/server/src/test/resources/org/opencds/cqf/cql/ls/server"
        one_dir.mkdir(parents=True, exist_ok=True)
        (one_dir / "One.xml").write_text(
            ONE_XML_TEMPLATE.format(version=version), encoding="utf-8"
        )
        (one_dir / "One.json").write_text(
            ONE_JSON_TEMPLATE.format(version=version), encoding="utf-8"
        )
        (one_dir / "One.ast").write_text(
            ONE_AST_TEMPLATE.format(version=version), encoding="utf-8"
        )

    def _read_repo(self) -> dict[str, str]:
        return {
            "pom": (self.repo_root / "pom.xml").read_text(encoding="utf-8"),
            "One.xml": (
                self.repo_root
                / "ls/server/src/test/resources/org/opencds/cqf/cql/ls/server/One.xml"
            ).read_text(encoding="utf-8"),
            "One.json": (
                self.repo_root
                / "ls/server/src/test/resources/org/opencds/cqf/cql/ls/server/One.json"
            ).read_text(encoding="utf-8"),
            "One.ast": (
                self.repo_root
                / "ls/server/src/test/resources/org/opencds/cqf/cql/ls/server/One.ast"
            ).read_text(encoding="utf-8"),
        }

    def _run(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "--repo-root", str(self.repo_root), *args],
            capture_output=True,
            text=True,
        )

    # ----- tests ---------------------------------------------------------

    def test_check_clean(self) -> None:
        """All four files report 5.5.0 -> exit 0."""

        result = self._run("--check")
        self.assertEqual(
            result.returncode,
            0,
            msg=f"stdout={result.stdout!r}\nstderr={result.stderr!r}",
        )
        self.assertIn("5.5.0", result.stdout)

    def test_check_drift(self) -> None:
        """One.json carries a mismatched translator version -> exit 1."""

        bad_path = (
            self.repo_root
            / "ls/server/src/test/resources/org/opencds/cqf/cql/ls/server/One.json"
        )
        bad_path.write_text(
            ONE_JSON_TEMPLATE.format(version="5.6.0"), encoding="utf-8"
        )

        result = self._run("--check")
        self.assertEqual(
            result.returncode,
            1,
            msg=f"stdout={result.stdout!r}\nstderr={result.stderr!r}",
        )
        self.assertIn("Drift detected", result.stderr)

    def test_dry_run(self) -> None:
        """--dry-run prints diff but does not mutate any file."""

        before = self._read_repo()
        result = self._run("5.6.0", "--dry-run")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("DRY RUN", result.stdout)
        after = self._read_repo()
        self.assertEqual(before, after, "dry_run must not modify any file on disk")

    def test_bump_success(self) -> None:
        """5.5.0 -> 5.6.0 updates all four files."""

        result = self._run("5.6.0")
        self.assertEqual(
            result.returncode,
            0,
            msg=f"stdout={result.stdout!r}\nstderr={result.stderr!r}",
        )

        files = self._read_repo()
        self.assertRegex(files["pom"], r"<cql\.version>5\.6\.0</cql\.version>")
        self.assertRegex(files["One.xml"], r'translatorVersion="5\.6\.0"')
        self.assertRegex(files["One.json"], r'"translatorVersion":\s*"5\.6\.0"')
        self.assertRegex(files["One.ast"], r"CQL-to-ELM 5\.6\.0\b")

        # Idempotency hint present in output
        self.assertIn("Bumped 5.5.0 -> 5.6.0", result.stdout)

    def test_bump_preserves_json_trailing_comma(self) -> None:
        """The JSON regex must not strip the comma after translatorVersion."""

        result = self._run("5.6.0")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        files = self._read_repo()
        # Both presence of "translatorVersion": "5.6.0" followed by a comma
        # and absence of any reformatted whitespace is acceptable; the line
        # must still contain the comma to keep the document valid JSON.
        expected = '"translatorVersion": "5.6.0",'
        self.assertIn(expected, files["One.json"])

    def test_invalid_version_fails(self) -> None:
        """A non-semver new version is rejected with exit 2."""

        result = self._run("not-a-version")
        self.assertEqual(result.returncode, 2)
        self.assertIn("does not match", result.stderr)

    def test_old_version_override(self) -> None:
        """--old lets the script bypass auto-detection when pom lacks the tag."""

        # Rewrite repo with a pom that omits <cql.version>
        self._write_repo(version="5.5.0", pom_with_cql=False)

        # Without --old the script raises during detect_old_version.
        no_override = self._run("5.6.0")
        self.assertEqual(no_override.returncode, 2)

        # With --old 5.5.0 the script succeeds.
        with_override = self._run("--old", "5.5.0", "5.6.0")
        self.assertEqual(
            with_override.returncode,
            0,
            msg=f"stdout={with_override.stdout!r}\nstderr={with_override.stderr!r}",
        )
        files = self._read_repo()
        # pom.xml has no <cql.version> tag, so it stays untouched; goldens move.
        self.assertNotIn("5.5.0", files["One.xml"])
        self.assertIn("5.6.0", files["One.xml"])

    def test_idempotent_bump(self) -> None:
        """Running the same bump twice: first applies, second reports clean."""

        first = self._run("5.6.0")
        self.assertEqual(first.returncode, 0, msg=first.stderr)
        second = self._run("5.6.0")
        self.assertEqual(second.returncode, 0, msg=second.stderr)
        # Second invocation must not print the "Bumped" banner because
        # nothing actually changed.
        self.assertNotIn("Bumped", second.stdout)

    def test_neither_old_nor_new_is_a_hard_error(self) -> None:
        """Golden fixture is at an unexpected version -> exit 2 with clear msg."""

        # Set goldens to 4.0.0 while pom stays 5.5.0; bumping to 5.6.0 must
        # not silently leave One.{xml,json,ast} stale.
        one_dir = self.repo_root / "ls/server/src/test/resources/org/opencds/cqf/cql/ls/server"
        for name, tpl in (
            ("One.xml", ONE_XML_TEMPLATE),
            ("One.json", ONE_JSON_TEMPLATE),
            ("One.ast", ONE_AST_TEMPLATE),
        ):
            (one_dir / name).write_text(tpl.format(version="4.0.0"), encoding="utf-8")

        result = self._run("--old", "5.5.0", "5.6.0")
        self.assertEqual(result.returncode, 2)
        joined = result.stdout + result.stderr
        self.assertTrue(
            any(
                needle in joined
                for needle in ("contains neither", "4.0.0", "5.5.0", "5.6.0")
            ),
            msg=f"expected diagnostic message; got stdout={result.stdout!r}\nstderr={result.stderr!r}",
        )


if __name__ == "__main__":
    unittest.main()
