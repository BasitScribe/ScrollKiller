"""D59's monorepo rule, asserted mechanically.

The backend and the Android app share a repository so that a change to the sync
contract lands as ONE atomic PR and CI can test both sides together — 3b's
`platform` ENUM parity test is the concrete case, and a mismatch there is a
silent sync drop rather than a build error.

What must NOT follow from sharing a repo is sharing a build. The Android tree
must build on a machine with no Python, and the backend on a machine with no JDK.
Coupling belongs at the contract level, where CI can see it; never at the build
level, where it just makes both harder to run.

The reverse direction needs no test: `android.yml` runs on a runner with no
Python setup step, so the workflow itself is the proof.
"""

from __future__ import annotations

from tests.conftest import BACKEND_ROOT, REPO_ROOT


def test_backend_is_not_a_gradle_module() -> None:
    settings = (REPO_ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    assert "backend" not in settings, (
        "settings.gradle.kts mentions backend. Gradle will accept an include for a "
        "directory with no build script and then fail confusingly (D59)."
    )


def test_root_build_script_does_not_reference_the_backend() -> None:
    root_build = (REPO_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    assert "backend" not in root_build


def test_no_gradle_files_under_backend() -> None:
    strays = [
        path.relative_to(REPO_ROOT)
        for pattern in ("**/*.gradle", "**/*.gradle.kts")
        for path in BACKEND_ROOT.glob(pattern)
    ]
    assert not strays, f"Gradle build files found under backend/: {strays}"


def test_backend_declares_no_jvm_toolchain() -> None:
    """The backend must not acquire a JDK dependency by the back door — e.g. a
    tool that shells out to Java. If this ever needs to change, it is a decision
    worth an ADR, not a quiet requirements line."""
    requirements = (BACKEND_ROOT / "requirements.in").read_text(encoding="utf-8").lower()
    for jvm_marker in ("jpype", "py4j", "jaydebeapi"):
        assert jvm_marker not in requirements
