#!/usr/bin/env python3
"""Generate the portable UniML CommonMark/GFM test corpus.

The checked-in upstream inputs are immutable snapshots.  This generator refuses
to consume them when their SHA-256 differs from the pinned value, extracts the
enabled GFM extension examples (including the two official task-list examples
marked ``disabled`` by cmark-gfm's C runner), and emits deterministic Scala test
data split into small shards for JVM and Scala.js.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from typing import Iterable


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SCALA_OUT = (
    REPO
    / "uniml"
    / "markdown"
    / "src"
    / "test"
    / "scala"
    / "scalascript"
    / "uniml"
    / "dialect"
    / "markdown"
    / "corpus"
    / "generated"
)

COMMONMARK_INPUT = HERE / "commonmark-0.31.2-spec.json"
GFM_INPUT = HERE / "gfm-0.29-spec.txt"
GFM_FILTERED = HERE / "gfm-0.29-enabled-extensions.json"
BASELINE_TSV = HERE / "BASELINE.tsv"
MANIFEST = HERE / "MANIFEST.json"

COMMONMARK_RAW_SHA256 = "d431b29d97b6f73e69d547109cf5081578fac931e72afe95639ebe766c1b2a20"
GFM_RAW_SHA256 = "7d8e5814befec287ac116786d81ff14e0adc9b13295b4494649e995408fd871c"
BASELINE_TSV_SHA256 = "9d13012e61d5c38cbc690841b4891824da1534c7bf2fa9d417c9bd4c7491fe47"

COMMONMARK_REVISION = "9103e341a973013013bb1a80e13567007c5cef6f"
GFM_REVISION = "587a12bb54d95ac37241377e6ddc93ea0e45439b"

GFM_SECTIONS = {
    "Tables (extension)": "table",
    "Task list items (extension)": "tasklist",
    "Strikethrough (extension)": "strikethrough",
    "Autolinks (extension)": "autolink",
}

SHARD_SIZE = 32
BASELINE_SHARD_SIZE = 128
GENERATED_PREFIX = "MarkdownCorpusGenerated"

CORPUS_FILE_ROLES = {
    ".gitattributes": "repository-policy",
    "BASELINE.json": "baseline-summary",
    "BASELINE.tsv": "complete-axis-baseline",
    "LICENSE.commonmark.txt": "upstream-license",
    "LICENSE.gfm.txt": "upstream-license",
    "MANIFEST.json": "integrity-manifest",
    "README.md": "corpus-documentation",
    "commonmark-0.31.2-spec.json": "upstream-snapshot",
    "generate.py": "generator",
    "gfm-0.29-enabled-extensions.json": "generated-selection",
    "gfm-0.29-spec.txt": "upstream-snapshot",
    "run-strict.sh": "release-gate-wrapper",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def require_regular_file(path: Path) -> None:
    if path.is_symlink():
        raise SystemExit(f"{path.relative_to(REPO)} must not be a symlink")
    if not path.is_file():
        raise SystemExit(f"missing regular file {path.relative_to(REPO)}")


def require_digest(path: Path, expected: str) -> None:
    require_regular_file(path)
    actual = sha256_file(path)
    if actual != expected:
        raise SystemExit(
            f"{path.relative_to(REPO)} SHA-256 mismatch:\n"
            f"expected={expected}\n"
            f"actual={actual}"
        )


def commonmark_cases() -> list[dict[str, object]]:
    require_digest(COMMONMARK_INPUT, COMMONMARK_RAW_SHA256)
    raw = json.loads(COMMONMARK_INPUT.read_text(encoding="utf-8"))
    if len(raw) != 652:
        raise SystemExit(f"CommonMark 0.31.2 case count changed: expected=652 actual={len(raw)}")
    cases = [
        {
            "corpus": "commonmark",
            "version": "0.31.2",
            "profile": "commonmark",
            "example": int(case["example"]),
            "section": str(case["section"]),
            "markdown": str(case["markdown"]),
            "html": str(case["html"]),
            "extension": "",
        }
        for case in raw
    ]
    expected_ids = list(range(1, 653))
    actual_ids = [int(case["example"]) for case in cases]
    if actual_ids != expected_ids:
        raise SystemExit("CommonMark 0.31.2 example ids are not exactly 1..652")
    return cases


def all_gfm_examples() -> list[dict[str, object]]:
    """Mirror cmark-gfm test/spec_tests.py extraction, retaining disabled cases."""

    require_digest(GFM_INPUT, GFM_RAW_SHA256)
    lines = GFM_INPUT.read_text(encoding="utf-8", newline="").splitlines(keepends=True)
    fence = "`" * 32
    state = 0
    section = ""
    flags: list[str] = []
    markdown_lines: list[str] = []
    html_lines: list[str] = []
    example = 0
    result: list[dict[str, object]] = []

    for line in lines:
        stripped = line.strip()
        if stripped.startswith(fence + " example"):
            state = 1
            flags = stripped[len(fence + " example") :].split()
        elif stripped == fence:
            state = 0
            example += 1
            result.append(
                {
                    "example": example,
                    "section": section,
                    "markdown": "".join(markdown_lines).replace("→", "\t"),
                    "html": "".join(html_lines).replace("→", "\t"),
                    "flags": flags,
                }
            )
            markdown_lines = []
            html_lines = []
        elif stripped == "." and state == 1:
            state = 2
        elif state == 1:
            markdown_lines.append(line)
        elif state == 2:
            html_lines.append(line)
        elif state == 0 and line.startswith("#"):
            heading = line.lstrip("#")
            if heading.startswith(" "):
                section = heading.strip()

    if state != 0:
        raise SystemExit("unterminated GFM example fence")
    if len(result) != 672:
        raise SystemExit(f"GFM 0.29 example count changed: expected=672 actual={len(result)}")
    return result


def gfm_cases() -> list[dict[str, object]]:
    selected = [
        {
            "corpus": "gfm",
            "version": "0.29",
            "profile": "gfm",
            "example": int(case["example"]),
            "section": str(case["section"]),
            "markdown": str(case["markdown"]),
            "html": str(case["html"]),
            "extension": GFM_SECTIONS[str(case["section"])],
        }
        for case in all_gfm_examples()
        if str(case["section"]) in GFM_SECTIONS
    ]
    expected_by_extension = {
        "table": 8,
        "tasklist": 2,
        "strikethrough": 2,
        "autolink": 11,
    }
    actual_by_extension = {
        extension: sum(1 for case in selected if case["extension"] == extension)
        for extension in expected_by_extension
    }
    if actual_by_extension != expected_by_extension:
        raise SystemExit(
            "GFM 0.29 enabled-extension census changed: "
            f"expected={expected_by_extension} actual={actual_by_extension}"
        )
    return selected


def canonical_digest(cases: Iterable[dict[str, object]]) -> str:
    digest = hashlib.sha256()
    for case in cases:
        fields = (
            str(case["corpus"]),
            str(case["version"]),
            str(case["profile"]),
            str(case["example"]),
            str(case["section"]),
            str(case["markdown"]),
            str(case["html"]),
            str(case["extension"]),
        )
        for field in fields:
            encoded = field.encode("utf-8")
            digest.update(len(encoded).to_bytes(8, "big"))
            digest.update(encoded)
    return digest.hexdigest()


def scala_string(value: str) -> str:
    # JSON's quoted-string syntax is accepted by Scala for this corpus.  Keep
    # non-ASCII text literal so the generated source remains auditable.
    return json.dumps(value, ensure_ascii=False)


def scala_case(case: dict[str, object]) -> str:
    fields = (
        scala_string(str(case["corpus"])),
        scala_string(str(case["version"])),
        scala_string(str(case["profile"])),
        str(case["example"]),
        scala_string(str(case["section"])),
        scala_string(str(case["markdown"])),
        scala_string(str(case["html"])),
        scala_string(str(case["extension"])),
    )
    return "    MarkdownCorpusCase(" + ", ".join(fields) + ")"


def baseline_data() -> tuple[list[dict[str, object]], dict[str, str]]:
    """Read the deterministic dump format without interpreting observables."""

    require_digest(BASELINE_TSV, BASELINE_TSV_SHA256)
    headers: dict[str, str] = {}
    rows: list[dict[str, object]] = []
    for raw_line in BASELINE_TSV.read_text(encoding="utf-8").splitlines():
        fields = raw_line.split("\t")
        if not fields:
            continue
        tag = fields[0]
        if tag in {
            "BASELINE-FULL-DIGEST",
            "BASELINE-NONPASS-DIGEST",
            "BASELINE-SECTION-DIGEST",
        }:
            if len(fields) != 2:
                raise SystemExit(f"malformed {tag} line in BASELINE.tsv")
            headers[tag] = fields[1]
        elif tag == "BASELINE-DIGEST":
            # Schema-v2 bootstrap input.  It deliberately yields no generated
            # complete rows; MarkdownCorpusBaselineDump replaces it with v3.
            if len(fields) != 2:
                raise SystemExit("malformed legacy BASELINE-DIGEST line")
            headers["BASELINE-NONPASS-DIGEST"] = fields[1]
        elif tag == "BASELINE-AXIS":
            if len(fields) != 13:
                raise SystemExit(
                    "malformed BASELINE-AXIS row: "
                    f"expected=13 tab fields actual={len(fields)}"
                )
            rows.append(
                {
                    "corpus": fields[1],
                    "version": fields[2],
                    "profile": fields[3],
                    "example": int(fields[4]),
                    "section": fields[5],
                    "extension": fields[6],
                    "axis": fields[7],
                    "status": fields[8],
                    "expected": fields[9],
                    "actual": fields[10],
                    "diagnostics": fields[11],
                    "tree": fields[12],
                }
            )
        elif tag in {"BASELINE-ROW", "BASELINE-SECTION"}:
            # BASELINE-ROW is accepted only for the one-time v2 -> v3
            # bootstrap.  Section rows are regenerated from the Scala runner.
            continue
        else:
            raise SystemExit(f"unknown BASELINE.tsv record: {tag}")

    if rows:
        required = {
            "BASELINE-FULL-DIGEST",
            "BASELINE-NONPASS-DIGEST",
            "BASELINE-SECTION-DIGEST",
        }
        missing = sorted(required - headers.keys())
        if missing:
            raise SystemExit(f"BASELINE.tsv is missing headers: {missing}")
        if len(rows) != 3375:
            raise SystemExit(
                f"complete baseline row count changed: expected=3375 actual={len(rows)}"
            )
    return rows, headers


def scala_baseline_row(row: dict[str, object]) -> str:
    fields = (
        scala_string(str(row["corpus"])),
        scala_string(str(row["version"])),
        scala_string(str(row["profile"])),
        str(row["example"]),
        scala_string(str(row["section"])),
        scala_string(str(row["extension"])),
        scala_string(str(row["axis"])),
        scala_string(str(row["status"])),
        scala_string(str(row["expected"])),
        scala_string(str(row["actual"])),
        scala_string(str(row["diagnostics"])),
        scala_string(str(row["tree"])),
    )
    return "    MarkdownBaselineRow(" + ", ".join(fields) + ")"


def shard_source(name: str, cases: list[dict[str, object]]) -> str:
    values = ",\n".join(scala_case(case) for case in cases)
    return f"""// Generated by uniml/corpus/markdown/generate.py. DO NOT EDIT.
package scalascript.uniml.dialect.markdown.corpus.generated

import scalascript.uniml.dialect.markdown.corpus.MarkdownCorpusCase

private[corpus] object {name}:
  val cases: Vector[MarkdownCorpusCase] = Vector(
{values},
  )
"""


def baseline_shard_source(name: str, rows: list[dict[str, object]]) -> str:
    values = ",\n".join(scala_baseline_row(row) for row in rows)
    return f"""// Generated by uniml/corpus/markdown/generate.py. DO NOT EDIT.
package scalascript.uniml.dialect.markdown.corpus.generated

import scalascript.uniml.dialect.markdown.corpus.MarkdownBaselineRow

private[corpus] object {name}:
  val rows: Vector[MarkdownBaselineRow] = Vector(
{values},
  )
"""


def index_source(
    commonmark_names: list[str],
    gfm_names: list[str],
    baseline_names: list[str],
    commonmark_digest: str,
    gfm_digest: str,
    baseline_headers: dict[str, str],
) -> str:
    commonmark_expr = " ++\n    ".join(f"{name}.cases" for name in commonmark_names)
    gfm_expr = " ++\n    ".join(f"{name}.cases" for name in gfm_names)
    baseline_expr = (
        " ++\n    ".join(f"{name}.rows" for name in baseline_names)
        if baseline_names
        else "Vector.empty"
    )
    full_digest = baseline_headers.get("BASELINE-FULL-DIGEST", "")
    nonpass_digest = baseline_headers.get("BASELINE-NONPASS-DIGEST", "")
    section_digest = baseline_headers.get("BASELINE-SECTION-DIGEST", "")
    return f"""// Generated by uniml/corpus/markdown/generate.py. DO NOT EDIT.
// CommonMark source: https://spec.commonmark.org/0.31.2/spec.json
// CommonMark revision: {COMMONMARK_REVISION}
// CommonMark raw SHA-256: {COMMONMARK_RAW_SHA256}
// GFM source: https://raw.githubusercontent.com/github/cmark-gfm/{GFM_REVISION}/test/spec.txt
// GFM revision: {GFM_REVISION}
// GFM raw SHA-256: {GFM_RAW_SHA256}
package scalascript.uniml.dialect.markdown.corpus.generated

import scalascript.uniml.dialect.markdown.corpus.{{MarkdownBaselineRow, MarkdownCorpusCase}}

private[corpus] object MarkdownCorpusGenerated:
  val commonMarkCanonicalSha256: String = "{commonmark_digest}"
  val gfmCanonicalSha256: String = "{gfm_digest}"
  val baselineFullRowsSha256: String = "{full_digest}"
  val baselineNonPassRowsSha256: String = "{nonpass_digest}"
  val baselineSectionSha256: String = "{section_digest}"

  val commonMark: Vector[MarkdownCorpusCase] =
    {commonmark_expr}

  val gfmEnabledExtensions: Vector[MarkdownCorpusCase] =
    {gfm_expr}

  val baselineRows: Vector[MarkdownBaselineRow] =
    {baseline_expr}
"""


def json_source(cases: list[dict[str, object]]) -> str:
    data = [
        {
            "example": case["example"],
            "section": case["section"],
            "extension": case["extension"],
            "markdown": case["markdown"],
            "html": case["html"],
        }
        for case in cases
    ]
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


def generated_files() -> dict[Path, str]:
    commonmark = commonmark_cases()
    gfm = gfm_cases()
    baseline_rows, baseline_headers = baseline_data()
    result: dict[Path, str] = {
        GFM_FILTERED: json_source(gfm),
        SCALA_OUT / ".gitattributes": (
            "# Generated files are byte-authenticated by the corpus manifest.\n"
            "* -text\n"
        ),
    }
    names: dict[str, list[str]] = {"CommonMark": [], "Gfm": [], "Baseline": []}

    for label, cases in (("CommonMark", commonmark), ("Gfm", gfm)):
        for index, start in enumerate(range(0, len(cases), SHARD_SIZE)):
            name = f"{GENERATED_PREFIX}{label}{index:02d}"
            names[label].append(name)
            result[SCALA_OUT / f"{name}.scala"] = shard_source(
                name, cases[start : start + SHARD_SIZE]
            )

    for index, start in enumerate(range(0, len(baseline_rows), BASELINE_SHARD_SIZE)):
        name = f"{GENERATED_PREFIX}Baseline{index:02d}"
        names["Baseline"].append(name)
        result[SCALA_OUT / f"{name}.scala"] = baseline_shard_source(
            name, baseline_rows[start : start + BASELINE_SHARD_SIZE]
        )

    result[SCALA_OUT / f"{GENERATED_PREFIX}.scala"] = index_source(
        names["CommonMark"],
        names["Gfm"],
        names["Baseline"],
        canonical_digest(commonmark),
        canonical_digest(gfm),
        baseline_headers,
    )
    return result


def manifest_source(
    corpus_contents: dict[str, bytes],
    scala_contents: dict[str, bytes],
) -> str:
    file_entries: list[dict[str, object]] = []
    for name in sorted(corpus_contents):
        relative = f"uniml/corpus/markdown/{name}"
        content = corpus_contents[name]
        file_entries.append(
            {
                "path": relative,
                "role": CORPUS_FILE_ROLES[name],
                "size": len(content),
                "sha256": sha256_bytes(content),
            }
        )
    for name in sorted(scala_contents):
        relative = (
            "uniml/markdown/src/test/scala/scalascript/uniml/dialect/"
            f"markdown/corpus/generated/{name}"
        )
        role = (
            "generated-complete-baseline"
            if "Baseline" in name
            else "generated-corpus"
        )
        file_entries.append(
            {
                "path": relative,
                "role": role,
                "size": len(scala_contents[name]),
                "sha256": sha256_bytes(scala_contents[name]),
            }
        )

    self_entry: dict[str, object] = {
        "path": "uniml/corpus/markdown/MANIFEST.json",
        "role": CORPUS_FILE_ROLES["MANIFEST.json"],
        "size": 0,
        # Hash of the exact manifest with this field zeroed.  A raw self hash
        # is mathematically recursive; this normalization pins every other
        # byte while the generator also checks the final file byte-for-byte.
        "normalizedSha256": "0" * 64,
    }
    file_entries.append(self_entry)
    file_entries.sort(key=lambda entry: str(entry["path"]))

    data: dict[str, object] = {
        "schemaVersion": 2,
        "generator": {
            "path": "uniml/corpus/markdown/generate.py",
            "checkCommand": "python3 uniml/corpus/markdown/generate.py --check",
            "writeCommand": "python3 uniml/corpus/markdown/generate.py --write",
            "baselineRows": "uniml/corpus/markdown/BASELINE.tsv",
            "baselineRowsSha256": sha256_bytes(corpus_contents["BASELINE.tsv"]),
        },
        "corpora": [
            {
                "id": "commonmark",
                "version": "0.31.2",
                "upstreamUrl": "https://spec.commonmark.org/0.31.2/spec.json",
                "upstreamRepository": "https://github.com/commonmark/commonmark-spec",
                "immutableRevision": COMMONMARK_REVISION,
                "localInput": "commonmark-0.31.2-spec.json",
                "rawSha256": COMMONMARK_RAW_SHA256,
                "canonicalCaseSha256": (
                    "f636418b09346809aa605ee4d52c3e600bf0f057251b77c386e49fae67a184a3"
                ),
                "caseCount": 652,
                "license": "CC-BY-SA-4.0",
                "licenseNotice": "LICENSE.commonmark.txt",
            },
            {
                "id": "gfm",
                "version": "0.29",
                "upstreamUrl": (
                    "https://raw.githubusercontent.com/github/cmark-gfm/"
                    f"{GFM_REVISION}/test/spec.txt"
                ),
                "upstreamRepository": "https://github.com/github/cmark-gfm",
                "immutableRevision": GFM_REVISION,
                "localInput": "gfm-0.29-spec.txt",
                "rawSha256": GFM_RAW_SHA256,
                "generatedSelection": "gfm-0.29-enabled-extensions.json",
                "canonicalCaseSha256": (
                    "56ec730753789fa2a39db08f0dbfe7b63c9eec3b612494ff3fb0f75fef1facdd"
                ),
                "upstreamExampleCount": 672,
                "selectedCaseCount": 23,
                "selectedExtensions": {
                    "table": 8,
                    "tasklist": 2,
                    "strikethrough": 2,
                    "autolink": 11,
                },
                "license": "CC-BY-SA-4.0",
                "licenseNotice": "LICENSE.gfm.txt",
            },
        ],
        "integrity": {
            "policy": (
                "exact closed file roster; regular files only; no symlinks, "
                "unmanifested files, directories, or caches"
            ),
            "files": file_entries,
        },
    }

    def encode() -> bytes:
        return (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    # The digest placeholder is already the final width, so only the decimal
    # self-size can require a short fixed-point iteration.
    while True:
        encoded = encode()
        if self_entry["size"] == len(encoded):
            break
        self_entry["size"] = len(encoded)
    normalized = encode()
    self_entry["normalizedSha256"] = sha256_bytes(normalized)
    final = encode()
    if len(final) != self_entry["size"]:
        raise SystemExit("manifest self-size failed to stabilize")
    return final.decode("utf-8")


def expected_contents(
    files: dict[Path, str],
) -> tuple[dict[str, bytes], dict[str, bytes], str]:
    corpus_contents: dict[str, bytes] = {}
    scala_contents: dict[str, bytes] = {}
    for path, content in files.items():
        encoded = content.encode("utf-8")
        if path.parent == HERE:
            corpus_contents[path.name] = encoded
        elif path.parent == SCALA_OUT:
            scala_contents[path.name] = encoded
        else:
            raise SystemExit(f"generated output escaped controlled roots: {path}")

    for name in sorted(CORPUS_FILE_ROLES):
        if name == "MANIFEST.json" or name in corpus_contents:
            continue
        path = HERE / name
        require_regular_file(path)
        corpus_contents[name] = path.read_bytes()

    manifest = manifest_source(corpus_contents, scala_contents)
    return corpus_contents, scala_contents, manifest


def write(files: dict[Path, str]) -> None:
    for path, content in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.is_symlink():
            raise SystemExit(f"refusing to overwrite symlink {path.relative_to(REPO)}")
        if not path.exists() or path.read_text(encoding="utf-8") != content:
            path.write_text(content, encoding="utf-8", newline="\n")
            print(f"wrote {path.relative_to(REPO)}")
    corpus_contents, scala_contents, manifest = expected_contents(files)
    if MANIFEST.is_symlink():
        raise SystemExit(f"refusing to overwrite symlink {MANIFEST.relative_to(REPO)}")
    if not MANIFEST.exists() or MANIFEST.read_text(encoding="utf-8") != manifest:
        MANIFEST.write_text(manifest, encoding="utf-8", newline="\n")
        print(f"wrote {MANIFEST.relative_to(REPO)}")
    corpus_contents["MANIFEST.json"] = manifest.encode("utf-8")
    errors = integrity_errors(HERE, SCALA_OUT, corpus_contents, scala_contents)
    if errors:
        raise SystemExit("generated Markdown corpus write failed:\n" + "\n".join(errors))


def integrity_errors(
    corpus_root: Path,
    scala_root: Path,
    expected_corpus: dict[str, bytes],
    expected_scala: dict[str, bytes],
) -> list[str]:
    errors: list[str] = []

    def inspect(
        label: str,
        root: Path,
        expected: dict[str, bytes],
    ) -> None:
        if root.is_symlink():
            errors.append(f"symlink {label} root")
            return
        if not root.is_dir():
            errors.append(f"missing {label} root")
            return
        actual_names = {path.name for path in root.iterdir()}
        expected_names = set(expected)
        for name in sorted(expected_names - actual_names):
            errors.append(f"missing {label}/{name}")
        for name in sorted(actual_names - expected_names):
            errors.append(f"unexpected {label}/{name}")
        for name in sorted(actual_names & expected_names):
            path = root / name
            if path.is_symlink():
                errors.append(f"symlink {label}/{name}")
            elif not path.is_file():
                errors.append(f"non-regular {label}/{name}")
            elif path.read_bytes() != expected[name]:
                errors.append(f"stale {label}/{name}")

    inspect("corpus", corpus_root, expected_corpus)
    inspect("generated", scala_root, expected_scala)
    return errors


def negative_integrity_probes(
    expected_corpus: dict[str, bytes],
    expected_scala: dict[str, bytes],
) -> None:
    def probe(name: str, mutation, expected_fragment: str) -> None:
        with tempfile.TemporaryDirectory(prefix="markdown-corpus-probe-") as temp:
            root = Path(temp)
            corpus = root / "corpus"
            generated = root / "generated"
            shutil.copytree(HERE, corpus, symlinks=True)
            shutil.copytree(SCALA_OUT, generated, symlinks=True)
            mutation(corpus, generated)
            errors = integrity_errors(
                corpus, generated, expected_corpus, expected_scala
            )
            joined = "\n".join(errors)
            if expected_fragment not in joined:
                raise SystemExit(
                    f"negative integrity probe '{name}' failed green:\n{joined}"
                )

    def tamper_raw(corpus: Path, generated: Path) -> None:
        del generated
        path = corpus / "commonmark-0.31.2-spec.json"
        path.write_bytes(path.read_bytes() + b"\n")

    def symlink_shard(corpus: Path, generated: Path) -> None:
        del corpus
        target = generated / f"{GENERATED_PREFIX}CommonMark00.scala"
        replacement = generated / f"{GENERATED_PREFIX}CommonMark01.scala"
        target.unlink()
        os.symlink(replacement.name, target)

    def extra_generated(corpus: Path, generated: Path) -> None:
        del corpus
        (generated / "Arbitrary.scala").write_text(
            "object Arbitrary\n", encoding="utf-8"
        )

    def extra_corpus(corpus: Path, generated: Path) -> None:
        del generated
        (corpus / "arbitrary.bin").write_bytes(b"unmanifested")

    def cache_directory(corpus: Path, generated: Path) -> None:
        del generated
        cache = corpus / "__pycache__"
        cache.mkdir()
        (cache / "generate.pyc").write_bytes(b"cache")

    probe(
        "raw tamper",
        tamper_raw,
        "stale corpus/commonmark-0.31.2-spec.json",
    )
    probe(
        "generated shard symlink",
        symlink_shard,
        f"symlink generated/{GENERATED_PREFIX}CommonMark00.scala",
    )
    probe(
        "extra generated Scala",
        extra_generated,
        "unexpected generated/Arbitrary.scala",
    )
    probe(
        "extra corpus file",
        extra_corpus,
        "unexpected corpus/arbitrary.bin",
    )
    probe(
        "cache directory",
        cache_directory,
        "unexpected corpus/__pycache__",
    )


def check(files: dict[Path, str]) -> None:
    corpus_contents, scala_contents, manifest = expected_contents(files)
    corpus_contents["MANIFEST.json"] = manifest.encode("utf-8")
    errors = integrity_errors(HERE, SCALA_OUT, corpus_contents, scala_contents)
    if errors:
        raise SystemExit("generated Markdown corpus check failed:\n" + "\n".join(errors))
    negative_integrity_probes(corpus_contents, scala_contents)
    print(
        "Markdown corpus generated data and closed manifest are current: "
        "CommonMark=652, GFM enabled extensions=23, integrity-probes=5"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="regenerate checked-in data")
    mode.add_argument("--check", action="store_true", help="fail when checked-in data is stale")
    args = parser.parse_args()
    files = generated_files()
    if args.write:
        write(files)
    else:
        check(files)


if __name__ == "__main__":
    main()
