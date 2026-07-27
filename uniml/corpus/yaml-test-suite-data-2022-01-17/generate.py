#!/usr/bin/env python3
"""Generate deterministic JVM/Scala.js YAML corpus data and its file manifest."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


HERE = Path(__file__).resolve().parent
CASES = HERE / "cases"
CATEGORIES = HERE / "categories.tsv"
OUTPUT = (
    HERE.parent.parent
    / "yaml"
    / "src"
    / "test"
    / "scala"
    / "scalascript"
    / "uniml"
    / "dialect"
    / "yaml"
    / "YamlOfficialCorpusData.scala"
)
EXPECTED_CASES = 402
EXPECTED_ERRORS = 94
SHARD_SIZE = 48
PINNED_LOGICAL_SHA256 = "97e131ad015f478c85318061d7e1b3c12ab517f8b922a5c005fa25ab4be5b7b5"
PINNED_CATEGORIES_SHA256 = "cfbc81ae960db00e42822576d96ddeab2a6511da31b7e9a5e7e9f65b5474b755"
PINNED_LICENSE_SHA256 = "c9562189164244554a69ab3f29d2d93ed9492c165723aaaa5fffc932cdbbfc85"
PINNED_MANIFEST_SHA256 = "51c3212589a9c51ecb5de32ea9037be2c5a5aa38e2a6e710afa60f427403bf45"
PINNED_GENERATED_SHA256 = "7bdf4a0e751d63c133d4ef3f9f51eab4d52c416a63c99f8d6a239c88612a5717"

DATA_REVISION = "6e6c296ae9c9d2d5c4134b4b64d01b29ac19ff6f"
DATA_TAG_OBJECT = "5f49729577242103ae23838ac2ad4d9145aec126"
DATA_ARCHIVE_SHA256 = "cc4a08f9ccc1cb2e66f32b3f1192bf1f07b3175682bbb715bf709bafa70322d4"
SOURCE_TAG = "v2022-01-17"
SOURCE_TAG_OBJECT = "6d48918a2320e767f6e2e57304f5ab42c19d71db"
SOURCE_REVISION = "45db50aecf9b1520f8258938c88f396e96f30831"
SOURCE_TREE = "1b1a150cd127094828f120e3d4c1cbefef42f02a"
TAGS_TREE = "a971feec6d8c46ba38db47c4751c3366270157e1"
LICENSE_BLOB = "5059e95ab21ff74438dd5af5a3f50a1a62c4b05f"


def selected_case_dirs() -> list[Path]:
    return sorted(path.parent for path in CASES.rglob("in.yaml"))


def exclusions() -> set[str]:
    result: set[str] = set()
    manifest = HERE / "encoding-exclusions.txt"
    if manifest.is_symlink() or not manifest.is_file():
        raise SystemExit("encoding-exclusions.txt must be a regular non-symlink file")
    for raw in manifest.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            result.add(line)
    return result


def canonical_line(
    case_id: str,
    title: bytes,
    source: bytes,
    events: bytes,
    should_fail: bool,
) -> str:
    fields = (
        case_id,
        title.hex(),
        source.hex(),
        events.hex(),
        "1" if should_fail else "0",
    )
    return "\t".join(fields) + "\n"


def read_categories(case_ids: list[str]) -> dict[str, tuple[str, ...]]:
    if CATEGORIES.is_symlink() or not CATEGORIES.is_file():
        raise SystemExit("categories.tsv must be a regular non-symlink file")
    payload = CATEGORIES.read_bytes()
    actual_sha256 = hashlib.sha256(payload).hexdigest()
    if actual_sha256 != PINNED_CATEGORIES_SHA256:
        raise SystemExit(
            f"pinned categories SHA-256 mismatch: expected={PINNED_CATEGORIES_SHA256} "
            f"actual={actual_sha256}"
        )
    try:
        text = payload.decode("ascii", errors="strict")
    except UnicodeDecodeError as error:
        raise SystemExit(f"categories.tsv must be ASCII: {error}") from error
    if not text.endswith("\n"):
        raise SystemExit("categories.tsv must end in LF")

    rows: list[tuple[str, str]] = []
    for number, line in enumerate(text.splitlines(), start=1):
        fields = line.split("\t")
        if len(fields) != 2 or not all(fields):
            raise SystemExit(f"categories.tsv:{number}: expected '<case-id>\\t<tag>'")
        case_id, tag = fields
        if any(char.isspace() for char in case_id + tag):
            raise SystemExit(f"categories.tsv:{number}: whitespace in case id or tag")
        rows.append((case_id, tag))
    if rows != sorted(set(rows)):
        raise SystemExit("categories.tsv rows must be unique and sorted by case id then tag")

    expected_ids = set(case_ids)
    actual_ids = {case_id for case_id, _ in rows}
    if actual_ids != expected_ids:
        raise SystemExit(
            "categories.tsv case roster mismatch: "
            f"missing={sorted(expected_ids - actual_ids)} extra={sorted(actual_ids - expected_ids)}"
        )
    grouped: dict[str, list[str]] = {case_id: [] for case_id in case_ids}
    for case_id, tag in rows:
        grouped[case_id].append(tag)
    return {case_id: tuple(tags) for case_id, tags in grouped.items()}


def scala_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--check",
        action="store_true",
        help="verify the pinned tree, manifest, and generated Scala without writing",
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="rewrite deterministic outputs, but only when the raw tree matches the pin",
    )
    return parser.parse_args()


def verify_exact_root(write: bool) -> None:
    expected = {
        ".gitattributes",
        "LICENSE",
        "PROVENANCE.md",
        "SHA256SUMS",
        "cases",
        "categories.tsv",
        "encoding-exclusions.txt",
        "generate.py",
    }
    entries = {path.name: path for path in HERE.iterdir()}
    required = expected - ({"SHA256SUMS"} if write else set())
    missing = sorted(required - entries.keys())
    extra = sorted(entries.keys() - expected)
    if missing or extra:
        raise SystemExit(f"pinned corpus root mismatch: missing={missing} extra={extra}")
    for name, path in entries.items():
        if path.is_symlink():
            raise SystemExit(f"symlinks are forbidden in the pinned corpus root: {name}")
        if name == "cases":
            if not path.is_dir():
                raise SystemExit("cases must be a directory")
        elif not path.is_file():
            raise SystemExit(f"pinned corpus root entry must be a regular file: {name}")


def verify_exact_tree(case_dirs: list[Path]) -> list[Path]:
    entries = list(CASES.rglob("*"))
    symlinks = sorted(path.relative_to(HERE).as_posix() for path in entries if path.is_symlink())
    if symlinks:
        raise SystemExit(f"symlinks are forbidden in the pinned corpus: {symlinks}")

    expected_files: set[Path] = set()
    for case_dir in case_dirs:
        expected_files.update(case_dir / name for name in ("===", "in.yaml", "test.event"))
        error = case_dir / "error"
        if error.is_file():
            expected_files.add(error)
    actual_files = {path for path in entries if path.is_file()}
    missing = sorted(path.relative_to(HERE).as_posix() for path in expected_files - actual_files)
    extra = sorted(path.relative_to(HERE).as_posix() for path in actual_files - expected_files)
    if missing or extra:
        raise SystemExit(f"pinned corpus file tree mismatch: missing={missing} extra={extra}")

    expected_dirs: set[Path] = {CASES}
    for path in expected_files:
        parent = path.parent
        while True:
            expected_dirs.add(parent)
            if parent == CASES:
                break
            parent = parent.parent
    actual_dirs = {CASES} | {path for path in entries if path.is_dir()}
    missing_dirs = sorted(path.relative_to(HERE).as_posix() for path in expected_dirs - actual_dirs)
    extra_dirs = sorted(path.relative_to(HERE).as_posix() for path in actual_dirs - expected_dirs)
    if missing_dirs or extra_dirs:
        raise SystemExit(
            f"pinned corpus directory tree mismatch: missing={missing_dirs} extra={extra_dirs}"
        )
    special = sorted(
        path.relative_to(HERE).as_posix()
        for path in entries
        if not path.is_file() and not path.is_dir()
    )
    if special:
        raise SystemExit(f"special files are forbidden in the pinned corpus: {special}")
    return sorted(expected_files)


def verify_generated_source_roster(write: bool) -> None:
    candidates = sorted(OUTPUT.parent.glob("YamlOfficialCorpusData*.scala"))
    missing = [] if OUTPUT in candidates else [OUTPUT.name]
    extra = [path.name for path in candidates if path != OUTPUT]
    if extra or (missing and not write):
        raise SystemExit(
            f"generated source roster mismatch: missing={missing} extra={extra}"
        )
    if OUTPUT in candidates and (OUTPUT.is_symlink() or not OUTPUT.is_file()):
        raise SystemExit("generated output must be a regular non-symlink file")


def verify_or_write(path: Path, expected: bytes, write: bool) -> None:
    if path.is_symlink():
        raise SystemExit(f"generated output must not be a symlink: {path}")
    if write:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(expected)
    else:
        if not path.is_file():
            raise SystemExit(f"missing generated file: {path}")
        actual = path.read_bytes()
        if actual != expected:
            raise SystemExit(
                f"generated file is stale or modified: {path}; "
                "review the pinned input, then run --write"
            )


def main() -> None:
    args = arguments()
    verify_exact_root(args.write)
    verify_generated_source_roster(args.write)
    case_dirs = selected_case_dirs()
    if len(case_dirs) != EXPECTED_CASES:
        raise SystemExit(f"expected {EXPECTED_CASES} in.yaml files, found {len(case_dirs)}")
    expected_case_files = verify_exact_tree(case_dirs)

    excluded = exclusions()
    discovered_unrepresentable: set[str] = set()
    raw_records: list[tuple[str, bytes, bytes, bytes, bool]] = []
    for case_dir in case_dirs:
        case_id = case_dir.relative_to(CASES).as_posix()
        required = tuple(case_dir / name for name in ("===", "in.yaml", "test.event"))
        missing = [path.name for path in required if not path.is_file()]
        if missing:
            raise SystemExit(f"{case_id}: missing required files: {', '.join(missing)}")
        title, source, events = (path.read_bytes() for path in required)
        for label, payload in zip(("===", "in.yaml", "test.event"), (title, source, events)):
            try:
                payload.decode("utf-8", errors="strict")
            except UnicodeDecodeError:
                discovered_unrepresentable.add(case_id)
                print(f"{case_id}: {label} is not UTF-8 representable")
        raw_records.append((case_id, title, source, events, (case_dir / "error").is_file()))

    if discovered_unrepresentable != excluded:
        raise SystemExit(
            "encoding exclusion manifest mismatch: "
            f"manifest={sorted(excluded)} discovered={sorted(discovered_unrepresentable)}"
        )
    if excluded:
        raise SystemExit(
            "this generated String corpus currently supports no exclusions; "
            f"found {sorted(excluded)}"
        )

    error_count = sum(1 for record in raw_records if record[4])
    if error_count != EXPECTED_ERRORS:
        raise SystemExit(f"expected {EXPECTED_ERRORS} error markers, found {error_count}")

    canonical = "".join(canonical_line(*record) for record in raw_records)
    logical_sha256 = hashlib.sha256(canonical.encode("ascii")).hexdigest()
    if logical_sha256 != PINNED_LOGICAL_SHA256:
        raise SystemExit(
            f"pinned logical SHA-256 mismatch: expected={PINNED_LOGICAL_SHA256} "
            f"actual={logical_sha256}"
        )

    categories = read_categories([record[0] for record in raw_records])
    records = [
        (case_id, title, source, events, should_fail, categories[case_id])
        for case_id, title, source, events, should_fail in raw_records
    ]

    license_file = HERE / "LICENSE"
    if license_file.is_symlink() or not license_file.is_file():
        raise SystemExit("LICENSE must be a regular non-symlink file")
    license_sha256 = hashlib.sha256(license_file.read_bytes()).hexdigest()
    if license_sha256 != PINNED_LICENSE_SHA256:
        raise SystemExit(
            f"pinned LICENSE SHA-256 mismatch: expected={PINNED_LICENSE_SHA256} "
            f"actual={license_sha256}"
        )

    selected_files = [license_file, CATEGORIES] + expected_case_files
    manifest_lines = []
    for path in sorted(selected_files):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest_lines.append(f"{digest}  {path.relative_to(HERE).as_posix()}")
    manifest_bytes = ("\n".join(manifest_lines) + "\n").encode("ascii")
    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
    if manifest_sha256 != PINNED_MANIFEST_SHA256:
        raise SystemExit(
            f"pinned tree-manifest SHA-256 mismatch: expected={PINNED_MANIFEST_SHA256} "
            f"actual={manifest_sha256}"
        )

    lines = [
        "// Generated by uniml/corpus/yaml-test-suite-data-2022-01-17/generate.py.",
        "// Do not edit by hand.",
        "package scalascript.uniml.dialect.yaml",
        "",
        "private[yaml] final case class YamlOfficialCaseData(",
        "    id: String,",
        "    titleUtf8Hex: String,",
        "    inputUtf8Hex: String,",
        "    eventsUtf8Hex: String,",
        "    shouldFail: Boolean,",
        "    categories: Vector[String],",
        ")",
        "",
    ]
    shard_names = []
    for shard_index, start in enumerate(range(0, len(records), SHARD_SIZE)):
        shard_name = f"YamlOfficialCorpusDataShard{shard_index:02d}"
        shard_names.append(shard_name)
        lines.extend(
            (
                f"private object {shard_name}:",
                "  val cases: Vector[YamlOfficialCaseData] = Vector(",
            )
        )
        for case_id, title, source, events, should_fail, case_categories in records[
            start : start + SHARD_SIZE
        ]:
            lines.extend(
                (
                    "    YamlOfficialCaseData(",
                    f"      id = {scala_string(case_id)},",
                    f"      titleUtf8Hex = {scala_string(title.hex())},",
                    f"      inputUtf8Hex = {scala_string(source.hex())},",
                    f"      eventsUtf8Hex = {scala_string(events.hex())},",
                    f"      shouldFail = {str(should_fail).lower()},",
                    "      categories = Vector(" +
                    ", ".join(scala_string(tag) for tag in case_categories) +
                    "),",
                    "    ),",
                )
            )
        lines.extend(("  )", ""))
    lines.extend(
        (
            "private[yaml] object YamlOfficialCorpusData:",
            '  val version: String = "data-2022-01-17"',
            f'  val revision: String = "{DATA_REVISION}"',
            f'  val dataTagObject: String = "{DATA_TAG_OBJECT}"',
            f'  val archiveSha256: String = "{DATA_ARCHIVE_SHA256}"',
            f'  val sourceTag: String = "{SOURCE_TAG}"',
            f'  val sourceTagObject: String = "{SOURCE_TAG_OBJECT}"',
            f'  val sourceRevision: String = "{SOURCE_REVISION}"',
            f'  val sourceTree: String = "{SOURCE_TREE}"',
            f'  val tagsTree: String = "{TAGS_TREE}"',
            f'  val licenseBlob: String = "{LICENSE_BLOB}"',
            f'  val licenseSha256: String = "{license_sha256}"',
            f'  val categoriesSha256: String = "{PINNED_CATEGORIES_SHA256}"',
            f'  val treeManifestSha256: String = "{manifest_sha256}"',
            f'  val logicalSha256: String = "{logical_sha256}"',
            f"  val expectedCaseCount: Int = {EXPECTED_CASES}",
            f"  val expectedErrorCount: Int = {EXPECTED_ERRORS}",
            "  val encodingExclusions: Vector[String] = Vector.empty",
            "",
            "  val cases: Vector[YamlOfficialCaseData] =",
            "    " + " ++\n      ".join(f"{name}.cases" for name in shard_names),
            "",
        )
    )
    generated_bytes = "\n".join(lines).encode("utf-8")
    generated_sha256 = hashlib.sha256(generated_bytes).hexdigest()
    if generated_sha256 != PINNED_GENERATED_SHA256:
        raise SystemExit(
            f"pinned generated SHA-256 mismatch: expected={PINNED_GENERATED_SHA256} "
            f"actual={generated_sha256}"
        )

    verify_or_write(HERE / "SHA256SUMS", manifest_bytes, args.write)
    verify_or_write(OUTPUT, generated_bytes, args.write)

    print(
        f"{'wrote' if args.write else 'checked'} {len(records)} cases "
        f"({error_count} errors), logical SHA-256 {logical_sha256}, "
        f"manifest SHA-256 {manifest_sha256}"
    )


if __name__ == "__main__":
    main()
