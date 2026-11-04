#!/usr/bin/env python3
"""test_update_project_version.py — stdlib unittest suite for update_project_version.py.

Run from cql-language-server/ as::

    python3 -m unittest scripts._tests.test_update_project_version

or::

    python3 -m unittest discover -s scripts/_tests -p test_update_project_version.py

Each test synthesizes a parent + submodule pom pair in a tempdir and passes
``--repo-root <tmpdir>`` to the script.
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parent.parent / "update_project_version.py"


# ---------------------------------------------------------------------------
# Fixture templates
# ---------------------------------------------------------------------------

# Parent pom.xml — mirrors the real cql-language-server/pom.xml structure.
# The <version> line is the project version (first such element). The
# dependencyManagement block demonstrates that the regex must NOT pick up
# dependency <version> entries even if their values coincide.
PARENT_POM_TEMPLATE = textwrap.dedent(
    """\
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>cql-ls</artifactId>
        <packaging>pom</packaging>
        <version>{version}</version>
        <name>CQL Language Server (test fixture)</name>
        <modules>
            <module>ls/server</module>
        </modules>
        <dependencyManagement>
            <dependencies>
                <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>some-dep</artifactId>
                    <version>9.9.9</version>
                </dependency>
            </dependencies>
        </dependencyManagement>
    </project>
    """
)

# Child pom.xml — mirrors the real ls/server/pom.xml. The <parent><version>
# element is the version we're targeting.
CHILD_POM_TEMPLATE = textwrap.dedent(
    """\
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
        <modelVersion>4.0.0</modelVersion>
        <artifactId>cql-ls-server</artifactId>
        <packaging>jar</packaging>
        <name>CQL Language Server - Server Component</name>
        <parent>
            <groupId>com.example</groupId>
            <artifactId>cql-ls</artifactId>
            <version>{version}</version>
            <relativePath>../../pom.xml</relativePath>
        </parent>
        <dependencies>
            <dependency>
                <groupId>ca.uhn.hapi.fhir</groupId>
                <artifactId>hapi-fhir-caching-caffeine</artifactId>
                <version>8.10.0</version>
            </dependency>
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>a-real-dep-that-happens-to-collide</artifactId>
                <version>{collide_version}</version>
            </dependency>
        </dependencies>
    </project>
    """
)


# ---------------------------------------------------------------------------
# TestCase
# ---------------------------------------------------------------------------


class UpdateProjectVersionTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo_root = Path(self._tmp.name)
        self._write_repo(version="4.11.0-SNAPSHOT", collide_version="2.5.0")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    # ----- helpers -------------------------------------------------------

    def _write_repo(
        self, version: str, *, collide_version: str = "2.5.0"
    ) -> None:
        (self.repo_root / "pom.xml").write_text(
            PARENT_POM_TEMPLATE.format(version=version), encoding="utf-8"
        )
        child_dir = self.repo_root / "ls/server"
        child_dir.mkdir(parents=True, exist_ok=True)
        (child_dir / "pom.xml").write_text(
            CHILD_POM_TEMPLATE.format(
                version=version, collide_version=collide_version
            ),
            encoding="utf-8",
        )

    def _read_files(self) -> dict[str, str]:
        return {
            "parent": (self.repo_root / "pom.xml").read_text(encoding="utf-8"),
            "child": (
                self.repo_root / "ls/server/pom.xml"
            ).read_text(encoding="utf-8"),
        }

    def _run(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT_PATH),
                "--repo-root",
                str(self.repo_root),
                *args,
            ],
            capture_output=True,
            text=True,
        )

    # ----- tests ---------------------------------------------------------

    def test_check_clean(self) -> None:
        """Both poms agree on 4.11.0-SNAPSHOT -> exit 0."""

        result = self._run("--check")
        self.assertEqual(
            result.returncode,
            0,
            msg=f"stdout={result.stdout!r}\nstderr={result.stderr!r}",
        )
        self.assertIn("4.11.0-SNAPSHOT", result.stdout)

    def test_check_drift(self) -> None:
        """Submodule <parent><version> disagrees with parent -> exit 1."""

        (self.repo_root / "ls/server/pom.xml").write_text(
            CHILD_POM_TEMPLATE.format(
                version="4.10.0-SNAPSHOT", collide_version="2.5.0"
            ),
            encoding="utf-8",
        )
        result = self._run("--check")
        self.assertEqual(
            result.returncode,
            1,
            msg=f"stdout={result.stdout!r}\nstderr={result.stderr!r}",
        )
        self.assertIn("Drift detected", result.stderr)

    def test_dry_run(self) -> None:
        """--dry-run prints a unified diff but does not touch any file."""

        before = self._read_files()
        result = self._run("4.12.0", "--dry-run")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("DRY RUN", result.stdout)
        after = self._read_files()
        self.assertEqual(before, after, msg="dry_run must not modify disk")

    def test_bump_success(self) -> None:
        """4.11.0-SNAPSHOT -> 4.12.0 updates both poms."""

        result = self._run("4.12.0")
        self.assertEqual(
            result.returncode,
            0,
            msg=f"stdout={result.stdout!r}\nstderr={result.stderr!r}",
        )
        files = self._read_files()
        # Parent has the new project version
        self.assertRegex(files["parent"], r"<version>4\.12\.0</version>")
        # Child has the new <parent><version>
        self.assertRegex(
            files["child"],
            r"(?s)<parent>[\s\S]*?<version>4\.12\.0</version>",
        )
        # Old version is gone from both files
        self.assertNotIn("4.11.0-SNAPSHOT", files["parent"])
        self.assertNotIn("4.11.0-SNAPSHOT", files["child"])
        # Dependency <version> entries are untouched
        self.assertIn("<version>8.10.0</version>", files["child"])
        self.assertIn("<version>2.5.0</version>", files["child"])

    def test_snapshot_appends_suffix(self) -> None:
        """--snapshot 4.12.0 produces 4.12.0-SNAPSHOT."""

        result = self._run("4.12.0", "--snapshot")
        self.assertEqual(
            result.returncode,
            0,
            msg=f"stdout={result.stdout!r}\nstderr={result.stderr!r}",
        )
        files = self._read_files()
        self.assertRegex(files["parent"], r"<version>4\.12\.0-SNAPSHOT</version>")
        self.assertRegex(
            files["child"],
            r"(?s)<parent>[\s\S]*?<version>4\.12\.0-SNAPSHOT</version>",
        )

    def test_snapshot_idempotent_for_snapshot_input(self) -> None:
        """--snapshot 4.12.0-SNAPSHOT does not double-append."""

        result = self._run("4.12.0-SNAPSHOT", "--snapshot")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        files = self._read_files()
        # No `-SNAPSHOT-SNAPSHOT` artifact anywhere
        self.assertNotIn("4.12.0-SNAPSHOT-SNAPSHOT", files["parent"])
        self.assertNotIn("4.12.0-SNAPSHOT-SNAPSHOT", files["child"])
        # And the version did get rewritten
        self.assertRegex(files["parent"], r"<version>4\.12\.0-SNAPSHOT</version>")

    def test_invalid_version_fails(self) -> None:
        """Garbage version string is rejected (exit 2)."""

        result = self._run("not-a-version")
        self.assertEqual(result.returncode, 2)
        self.assertIn("does not match", result.stderr)

    def test_old_version_override(self) -> None:
        """--old lets the script apply the bump when auto-detect would fail.

        Setup: parent pom.xml has NO <version> element (matches a corner case
        where the project lives downstream of inheritance only). Without --old,
        detect_old_version aborts. With --old 4.11.0-SNAPSHOT, replacement
        proceeds leniently — parent pom is gracefully skipped (no version line
        to update), child pom is bumped.
        """

        # Wipe the <version> line in the parent pom
        parent_text = PARENT_POM_TEMPLATE.format(version="4.11.0-SNAPSHOT")
        parent_no_version = parent_text.replace(
            "<version>4.11.0-SNAPSHOT</version>", ""
        )
        self.assertNotIn("4.11.0-SNAPSHOT", parent_no_version)
        (self.repo_root / "pom.xml").write_text(parent_no_version, encoding="utf-8")

        # Without --old, auto-detect fails -> exit 2
        no_override = self._run("4.12.0")
        self.assertEqual(no_override.returncode, 2)

        # With --old, script succeeds (lenient mode). Child pom updated, parent
        # reported as skipped.
        with_override = self._run("--old", "4.11.0-SNAPSHOT", "4.12.0")
        self.assertEqual(
            with_override.returncode,
            0,
            msg=f"stdout={with_override.stdout!r}\nstderr={with_override.stderr!r}",
        )
        files = self._read_files()
        self.assertRegex(
            files["child"],
            r"(?s)<parent>[\s\S]*?<version>4\.12\.0</version>",
        )
        # skip reported
        self.assertIn("skipped", with_override.stdout)

    def test_idempotent_bump(self) -> None:
        """Running the same bump twice: first applies, second is a no-op."""

        first = self._run("4.12.0")
        self.assertEqual(first.returncode, 0, msg=first.stderr)
        second = self._run("4.12.0")
        self.assertEqual(second.returncode, 0, msg=second.stderr)
        self.assertNotIn("Bumped", second.stdout)

    def test_submodule_does_not_touch_dependency_versions(self) -> None:
        """A <dependency><version> that collides with the project version is
        not rewritten by the submodule anchor regex."""

        # The dependency's version is identical to the project version
        self._write_repo(version="4.11.0-SNAPSHOT", collide_version="4.11.0-SNAPSHOT")
        result = self._run("4.12.0")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        files = self._read_files()
        # The <parent><version> in the child pom is updated
        self.assertRegex(
            files["child"],
            r"(?s)<parent>[\s\S]*?<version>4\.12\.0</version>",
        )
        # The colliding dependency <version> retains 4.11.0-SNAPSHOT — exactly
        # one occurrence remains (the dependency), proving the sweep hit only
        # the parent ref.
        self.assertEqual(
            files["child"].count("4.11.0-SNAPSHOT"),
            1,
            msg=(
                f"expected exactly one remaining 4.11.0-SNAPSHOT (colliding "
                f"dependency) but found {files['child'].count('4.11.0-SNAPSHOT')}: "
                f"{files['child']!r}"
            ),
        )


if __name__ == "__main__":
    unittest.main()
