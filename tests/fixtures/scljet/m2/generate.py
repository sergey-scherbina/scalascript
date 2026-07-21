#!/usr/bin/env python3
"""Reproduce the committed SclJet M2 SQLite 3.53.3 corpus.

Ordinary tests consume committed bytes and never invoke this script.  The
Python sqlite3 module must be built against the exact oracle version below.
"""

from __future__ import annotations

import hashlib
import os
import sqlite3
import struct
import subprocess
from pathlib import Path


ORACLE_VERSION = "3.53.3"
ORACLE_SOURCE_ID = (
    "2026-06-26 20:14:12 "
    "d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62"
)
HISTORICAL_VERSION = "3.2.0"
HISTORICAL_MANIFEST_UUID = "debf40e8ffa35406685ec027ced1f147ef0487df"
ROOT = Path(__file__).resolve().parent
VALID = ROOT / "valid"
CORRUPT = ROOT / "corrupt"


def qident(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def configure(con: sqlite3.Connection, page_size: int, encoding: str, auto: str) -> None:
    con.execute(f"PRAGMA page_size={page_size}")
    con.execute(f"PRAGMA encoding='{encoding}'")
    con.execute(f"PRAGMA auto_vacuum={auto}")
    con.execute("VACUUM")


def create_simple(path: Path, page_size: int, encoding: str = "UTF-8") -> None:
    con = sqlite3.connect(path)
    configure(con, page_size, encoding, "NONE")
    con.execute("CREATE TABLE t(a INTEGER, b TEXT, c BLOB)")
    con.execute("INSERT INTO t VALUES(-2, 'Hi', x'00ff')")
    con.commit()
    con.close()


def create_comprehensive(path: Path) -> None:
    con = sqlite3.connect(path)
    configure(con, 512, "UTF-8", "NONE")
    con.execute("CREATE TABLE t(a INTEGER, b TEXT, c BLOB)")
    con.execute("CREATE INDEX idx_t_a ON t(a)")
    con.executemany(
        "INSERT INTO t VALUES(?, ?, ?)",
        [(i - 90, f"v{i:03d}", bytes((i & 255, (i * 3) & 255))) for i in range(180)],
    )
    con.execute("INSERT INTO t VALUES(999, ?, ?)", ("Omega:\u03a9\x00tail", bytes(range(256)) * 8))
    con.execute("CREATE TABLE wr(k TEXT PRIMARY KEY, v INTEGER) WITHOUT ROWID")
    con.executemany("INSERT INTO wr VALUES(?, ?)", [(f"k{i:03d}", i * 7) for i in range(60)])
    con.execute("CREATE VIEW v_t AS SELECT a,b FROM t WHERE a >= 0")
    con.execute("CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN SELECT new.a; END")
    con.commit()
    con.close()


def create_freelist(path: Path) -> None:
    con = sqlite3.connect(path)
    configure(con, 512, "UTF-8", "NONE")
    con.execute("CREATE TABLE t(a INTEGER, b TEXT, c BLOB)")
    con.execute("INSERT INTO t VALUES(1, 'kept', x'01')")
    con.execute("CREATE TABLE discarded(n INTEGER, payload BLOB)")
    con.executemany("INSERT INTO discarded VALUES(?, zeroblob(900))", [(i,) for i in range(90)])
    con.execute("DROP TABLE discarded")
    con.commit()
    con.close()


def create_autovacuum(path: Path, mode: str, keep_big: bool) -> None:
    con = sqlite3.connect(path)
    configure(con, 512, "UTF-8", mode)
    con.execute("CREATE TABLE t(a INTEGER, b TEXT, c BLOB)")
    con.execute("INSERT INTO t VALUES(1, ?, x'0102')", (mode.lower(),))
    con.execute("CREATE TABLE big(n INTEGER, payload BLOB)")
    con.executemany("INSERT INTO big VALUES(?, zeroblob(700))", [(i,) for i in range(100)])
    if not keep_big:
        con.execute("DROP TABLE big")
    con.commit()
    con.close()


def create_empty(path: Path) -> None:
    con = sqlite3.connect(path)
    con.execute("PRAGMA page_size=512")
    con.execute("VACUUM")
    con.close()


def create_serial_and_rowid_edges(path: Path) -> None:
    con = sqlite3.connect(path)
    configure(con, 1024, "UTF-8", "NONE")
    con.execute(
        "CREATE TABLE serials(nul, i1a, i1b, i2, i3, i4, i5, i6, r, z, o, txt, blob)"
    )
    con.execute(
        "INSERT INTO serials VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (
            None, -128, 127, -32768, -8388608, -2147483648,
            -140737488355328, -9223372036854775808, 1.5, 0, 1,
            "A\x00\u03a9", bytes((0, 127, 128, 255)),
        ),
    )
    con.execute("CREATE TABLE rowids(v TEXT)")
    con.execute("INSERT INTO rowids(rowid,v) VALUES(?,?)", (-9223372036854775808, "min"))
    con.execute("INSERT INTO rowids(rowid,v) VALUES(?,?)", (9223372036854775807, "max"))
    con.execute("PRAGMA user_version=20260712")
    con.execute("PRAGMA application_id=305419896")
    con.commit()
    con.close()


def create_overflow_thresholds(path: Path) -> None:
    """Table-leaf cells straddling SQLite's exact overflow threshold.

    Page size 512, reserved 0 -> usable u = 512, so a table-leaf cell keeps its
    whole payload local while p <= X = u - 35 = 477 and otherwise stores
    K = m + ((p - m) % (u - 4)) bytes locally when K <= X, else m = 39.  A single
    implicit-rowid BLOB column has a 3-byte record header, so a blob of length L
    produces total payload p = L + 3.  The chosen lengths hit p = X-1/X/X+1/X+2
    (the inclusive local boundary and the sharp fall to the m-byte residue when
    K > X), a mid-range K <= X case, and a genuine multi-page overflow chain.
    """
    con = sqlite3.connect(path)
    configure(con, 512, "UTF-8", "NONE")
    con.execute("CREATE TABLE t(payload BLOB)")
    for p in (476, 477, 478, 479, 500, 900, 1100):
        length = p - 3
        blob = bytes((i * 37 + p) & 255 for i in range(length))
        con.execute("INSERT INTO t(payload) VALUES(?)", (blob,))
    con.commit()
    con.close()


def create_index_overflow_thresholds(path: Path) -> None:
    """Index-btree cells straddling SQLite's exact index overflow threshold.

    Page size 512, reserved 0 -> usable u = 512, so an index-leaf cell keeps its
    whole key record local while p <= X = ((u-12)*64/255) - 23 = 102 (the
    index formula, distinct from the table-leaf u - 35 = 477), and otherwise
    stores K = m + ((p - m) % (u - 4)) locally when K <= X, else m = 39.  An
    index key record over a rowid table is (key columns..., rowid); a single
    BLOB key with a small (1-byte) rowid has a 4-byte record header, so a blob
    of length L produces p = L + 5.  The lengths hit p = X-1/X/X+1 (the
    inclusive local boundary and the sharp fall to the m-byte residue when
    K > X), a further overflow, and a K <= X multi-page overflow chain.
    """
    con = sqlite3.connect(path)
    configure(con, 512, "UTF-8", "NONE")
    con.execute("CREATE TABLE t(k)")
    con.execute("CREATE INDEX ix ON t(k)")
    for p in (101, 102, 103, 200, 1100):
        length = p - 5
        blob = bytes((i * 29 + p) & 255 for i in range(length))
        con.execute("INSERT INTO t(k) VALUES(?)", (blob,))
    con.commit()
    con.close()


def create_clean_wal_header(path: Path) -> None:
    con = sqlite3.connect(path)
    configure(con, 4096, "UTF-8", "NONE")
    mode = con.execute("PRAGMA journal_mode=WAL").fetchone()[0]
    if mode.lower() != "wal":
        raise RuntimeError(f"cannot enable WAL for {path}: {mode}")
    con.execute("CREATE TABLE t(a INTEGER, b TEXT, c BLOB)")
    con.execute("INSERT INTO t VALUES(2, 'checkpointed', x'0203')")
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
    con.close()
    for suffix in ("-wal", "-shm"):
        sidecar = Path(str(path) + suffix)
        if sidecar.exists():
            raise RuntimeError(f"clean WAL fixture retained sidecar: {sidecar}")


def create_reserved(path: Path, page_size: int, reserved: int, helper: Path) -> None:
    subprocess.run(
        [str(helper), str(path), str(page_size), str(reserved)],
        check=True,
    )
    actual = path.read_bytes()[20]
    if actual != reserved:
        raise RuntimeError(f"{path}: requested {reserved} reserved bytes, header has {actual}")


def create_historical(
    path: Path,
    schema_format: int,
    sql: str,
    helper: Path,
) -> None:
    subprocess.run([str(helper), str(path), sql], check=True)
    actual = struct.unpack(">I", path.read_bytes()[44:48])[0]
    if actual != schema_format:
        raise RuntimeError(f"{path}: requested schema format {schema_format}, header has {actual}")


def cps(text: str) -> str:
    return "List(" + ", ".join(str(ord(ch)) for ch in text) + ")"


def byte_list(value: bytes) -> str:
    return "List(" + ", ".join(str(b) for b in value) + ")"


def value(value: object) -> str:
    if value is None:
        return "null"
    if isinstance(value, int):
        return f"int:{value}"
    if isinstance(value, float):
        return f"real:{value}"
    if isinstance(value, str):
        return f"text:{cps(value)}"
    if isinstance(value, bytes):
        return f"blob:{byte_list(value)}"
    raise TypeError(value)


def scala_list(values: list[str]) -> str:
    return "List(" + ", ".join(values) + ")"


def storage(kind: str, sql: str | None, root: int) -> str:
    if root == 0:
        return "none"
    if kind == "index":
        return "index"
    if kind == "table" and sql and "WITHOUT ROWID" in sql.upper():
        return "without-rowid"
    return "rowid"


def header_meta(data: bytes) -> tuple[int, int, int, int, int, int]:
    raw_page = struct.unpack(">H", data[16:18])[0]
    page_size = 65536 if raw_page == 1 else raw_page
    reserved = data[20]
    header_page_count = struct.unpack(">I", data[28:32])[0]
    change_counter = struct.unpack(">I", data[24:28])[0]
    version_valid_for = struct.unpack(">I", data[92:96])[0]
    file_page_count = len(data) // page_size
    page_count = (
        header_page_count
        if header_page_count > 0 and change_counter == version_valid_for
        else file_page_count
    )
    schema_format = struct.unpack(">I", data[44:48])[0]
    encoding = struct.unpack(">I", data[56:60])[0]
    freelist = struct.unpack(">I", data[36:40])[0]
    return page_size, reserved, page_count, schema_format, encoding, freelist


def oracle_dump(rel_path: str, path: Path) -> list[str]:
    data = path.read_bytes()
    page_size, reserved, page_count, schema_format, encoding, freelist = header_meta(data)
    lines = [f"FILE:{rel_path}", f"header:{page_size}:{reserved}:{page_count}:{schema_format}:{encoding}:{freelist}"]
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    schema = list(con.execute(
        "SELECT rowid,type,name,tbl_name,rootpage,sql FROM sqlite_schema ORDER BY rowid"
    ))
    for rowid, kind, name, table_name, root, sql in schema:
        root = int(root or 0)
        role = storage(kind, sql, root)
        internal = name.startswith("sqlite_")
        lines.append(
            f"schema:{rowid}:{kind}:{cps(name)}:{cps(table_name)}:{root}:{role}:{str(internal).lower()}"
        )
        if role == "rowid":
            rows = con.execute(f"SELECT rowid,* FROM {qident(name)} ORDER BY rowid")
            for physical in rows:
                lines.append(f"root:{root}:rowid:{physical[0]}:{scala_list([value(v) for v in physical[1:]])}")
        elif role == "without-rowid":
            rows = con.execute(f"SELECT * FROM {qident(name)} ORDER BY 1")
            for physical in rows:
                lines.append(f"root:{root}:key:{scala_list([value(v) for v in physical])}")
        elif role == "index":
            # Physical index-btree order = (indexed columns..., rowid) under the
            # default BINARY collation, which for these fixtures equals a SQL
            # ORDER BY on the same columns.  Rowid-table indexes only (there is no
            # WITHOUT ROWID index in the corpus); the trailing key column is the
            # table rowid, exactly as the SclJet reader decodes the index record.
            info = con.execute(f"PRAGMA index_info({qident(name)})").fetchall()
            cols = [qident(entry[2]) for entry in info]
            projection = ", ".join(cols + ["rowid"])
            rows = con.execute(
                f"SELECT {projection} FROM {qident(table_name)} ORDER BY {projection}"
            )
            for physical in rows:
                lines.append(f"root:{root}:key:{scala_list([value(v) for v in physical])}")
    integrity = con.execute("PRAGMA integrity_check").fetchone()[0]
    con.close()
    if integrity != "ok":
        raise RuntimeError(f"{path}: integrity_check={integrity}")
    return lines


def write_manifest(paths: list[tuple[str, Path]], source_id: str, compile_options: str) -> None:
    columns = [
        "id", "path", "sha256", "sqlite_version", "source_id", "page_size",
        "reserved", "page_count", "schema_format", "encoding", "write_version",
        "read_version", "auto_vacuum", "freelist_pages", "schema_entries",
        "generator_version", "generator_source_id", "generator", "integrity_check",
    ]
    rows = ["\t".join(columns)]
    dump: list[str] = []
    for fixture_id, path in paths:
        data = path.read_bytes()
        page_size, reserved, page_count, schema_format, encoding, freelist = header_meta(data)
        rel = path.relative_to(ROOT.parent.parent.parent.parent).as_posix()
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        auto_vacuum = con.execute("PRAGMA auto_vacuum").fetchone()[0]
        schema_entries = con.execute("SELECT count(*) FROM sqlite_schema").fetchone()[0]
        con.close()
        historical = fixture_id.startswith("schema-format-")
        generator_version = HISTORICAL_VERSION if historical else ORACLE_VERSION
        generator_source = HISTORICAL_MANIFEST_UUID if historical else ORACLE_SOURCE_ID
        rows.append("\t".join([
            fixture_id, rel, hashlib.sha256(data).hexdigest(), ORACLE_VERSION,
            source_id, str(page_size), str(reserved), str(page_count), str(schema_format),
            str(encoding), str(data[18]), str(data[19]), str(auto_vacuum), str(freelist),
            str(schema_entries), generator_version, generator_source,
            f"generate.py:{fixture_id}", "ok",
        ]))
        dump.extend(oracle_dump(rel, path))
    (ROOT / "manifest.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    (ROOT / "oracle-storage.txt").write_text("\n".join(dump) + "\n", encoding="utf-8")
    (ROOT / "oracle-compile-options.txt").write_text(compile_options + "\n", encoding="utf-8")


def put_u16(data: bytearray, offset: int, value: int) -> None:
    data[offset:offset + 2] = struct.pack(">H", value)


def put_u32(data: bytearray, offset: int, value: int) -> None:
    data[offset:offset + 4] = struct.pack(">I", value)


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    """Decode a SQLite base-128 varint; return (value, next_offset)."""
    result = 0
    for i in range(9):
        byte = data[offset + i]
        if i == 8:
            return (result << 8) | byte, offset + 9
        result = (result << 7) | (byte & 0x7F)
        if byte < 0x80:
            return result, offset + i + 1
    return result, offset + 9


def serial_body_bytes(serial: int) -> int:
    if serial == 0 or serial in (8, 9):
        return 0
    if 1 <= serial <= 4:
        return serial
    if serial == 5:
        return 6
    if serial in (6, 7):
        return 8
    return (serial - 12) // 2  # blob (even) or text (odd)


def first_schema_record(data: bytes) -> tuple[int, list[int], list[int], int]:
    """Locate page 1's first sqlite_schema record.

    Returns (header_start, serial_offsets, serial_values, body_start): the byte
    offset of the record header, each column serial varint's byte offset, its
    decoded value, and the offset where the record body begins.  Page 1's
    b-tree header is at offset 100; a single-row schema has its first (and only)
    cell pointer at offset 108.
    """
    cell_offset = struct.unpack(">H", data[108:110])[0]
    _, after_payload = read_varint(data, cell_offset)
    _, header_start = read_varint(data, after_payload)  # skip the rowid varint
    header_len, cursor = read_varint(data, header_start)
    serial_offsets: list[int] = []
    serial_values: list[int] = []
    while cursor < header_start + header_len:
        serial_offsets.append(cursor)
        value, cursor = read_varint(data, cursor)
        serial_values.append(value)
    return header_start, serial_offsets, serial_values, header_start + header_len


def corruptions(simple: Path, autovacuum: Path, freelist: Path, overflow: Path) -> None:
    cases: list[tuple[str, bytes, str]] = []
    base = simple.read_bytes()
    bad_magic = bytearray(base); bad_magic[0] = 0
    bad_kind = bytearray(base); bad_kind[100] = 1
    bad_page_size = bytearray(base); put_u16(bad_page_size, 16, 513)
    bad_read_version = bytearray(base); bad_read_version[19] = 3
    bad_write_version = bytearray(base); bad_write_version[18] = 0
    bad_reserved_size = bytearray(base); bad_reserved_size[20] = 33
    bad_payload_fractions = bytearray(base); bad_payload_fractions[21] = 63
    bad_schema_format = bytearray(base); put_u32(bad_schema_format, 44, 5)
    bad_text_encoding = bytearray(base); put_u32(bad_text_encoding, 56, 4)
    bad_header_reserved = bytearray(base); bad_header_reserved[72] = 1
    bad_incremental = bytearray(base); put_u32(bad_incremental, 64, 1)
    bad_freelist_pair = bytearray(base); put_u32(bad_freelist_pair, 36, 1)
    bad_page_count = bytearray(base); put_u32(bad_page_count, 28, 3)
    bad_fragments = bytearray(base); bad_fragments[107] = 61
    zero_content = bytearray(base); put_u16(zero_content, 105, 0)
    pointer_overlap = bytearray(base); put_u16(pointer_overlap, 103, 65535)
    bad_cell_pointer = bytearray(base); put_u16(bad_cell_pointer, 108, 0)
    cases.extend([
        ("bad-magic", bytes(bad_magic), "header.magic"),
        ("truncated-header", base[:99], "header"),
        ("partial-trailing-page", base[:-1], "trailing-page"),
        ("bad-page-kind", bytes(bad_kind), "page.kind"),
        ("bad-page-size", bytes(bad_page_size), "header.pageSize"),
        ("bad-read-version", bytes(bad_read_version), "header.readVersion"),
        ("bad-write-version", bytes(bad_write_version), "header.writeVersion"),
        ("bad-reserved-size", bytes(bad_reserved_size), "header.reservedBytes"),
        ("bad-payload-fractions", bytes(bad_payload_fractions), "header.payloadFractions"),
        ("bad-schema-format", bytes(bad_schema_format), "header.schemaFormat"),
        ("bad-text-encoding", bytes(bad_text_encoding), "header.textEncoding"),
        ("bad-header-reserved", bytes(bad_header_reserved), "header.reserved"),
        ("bad-incremental-without-ptrmap", bytes(bad_incremental), "header.incrementalVacuum"),
        ("bad-freelist-head-count-pair", bytes(bad_freelist_pair), "header.freelistHead"),
        ("bad-header-page-count", bytes(bad_page_count), "page count"),
        ("bad-fragmented-bytes", bytes(bad_fragments), "page.fragmentedBytes"),
        ("bad-zero-content-offset", bytes(zero_content), "page.cellContentStart"),
        ("bad-pointer-array-overlap", bytes(pointer_overlap), "page.cellCount"),
        ("bad-cell-pointer", bytes(bad_cell_pointer), "page.cellPointer"),
    ])
    auto = bytearray(autovacuum.read_bytes()); auto[512] = 0
    cases.append(("bad-pointer-map-kind", bytes(auto), "pointerMap.kind"))
    auto_parent = bytearray(autovacuum.read_bytes()); put_u32(auto_parent, 513, 99)
    cases.append(("bad-pointer-map-parent", bytes(auto_parent), "pointer-map relation"))

    free = freelist.read_bytes()
    free_page_size, _, free_pages, _, _, _ = header_meta(free)
    free_head = struct.unpack(">I", free[32:36])[0]
    free_offset = (free_head - 1) * free_page_size
    free_out_of_range = bytearray(free); put_u32(free_out_of_range, 32, free_pages + 1)
    free_cycle = bytearray(free); put_u32(free_cycle, free_offset, free_head)
    free_too_many = bytearray(free); put_u32(free_too_many, free_offset + 4, free_page_size // 4 - 1)
    free_duplicate = bytearray(free)
    first_leaf = struct.unpack(">I", free_duplicate[free_offset + 8:free_offset + 12])[0]
    put_u32(free_duplicate, free_offset + 12, first_leaf)
    cases.extend([
        ("bad-freelist-head", bytes(free_out_of_range), "freelist page is outside"),
        ("freelist-trunk-cycle", bytes(free_cycle), "freelist trunk chain contains a cycle"),
        ("freelist-too-many-leaves", bytes(free_too_many), "freelist.trunk.leafCount"),
        ("freelist-duplicate-leaf", bytes(free_duplicate), "freelist contains a duplicate page"),
    ])

    # Deep record/freeblock/schema mutations of the page-1 sqlite_schema record,
    # which the reader decodes eagerly at open (unlike user-table pages).  Each
    # damages one on-disk invariant the header/page-header checks above do not.
    _, serial_offsets, serial_values, body_start = first_schema_record(base)
    # sqlite_schema columns: type, name, tbl_name, rootpage, sql.
    rootpage_body = body_start + sum(serial_body_bytes(s) for s in serial_values[:3])

    bad_serial_type = bytearray(base)
    bad_serial_type[serial_offsets[3]] = 10  # rootpage int serial -> reserved 10
    bad_schema_type = bytearray(base)
    bad_schema_type[body_start] = ord("x")  # "table" -> "xable"
    bad_schema_rootpage_oob = bytearray(base)
    bad_schema_rootpage_oob[rootpage_body] = 0x7F  # rootpage 127, far past the page count
    bad_schema_rootpage_negative = bytearray(base)
    bad_schema_rootpage_negative[rootpage_body] = 0xFF  # int8 -1 rootpage
    bad_schema_freeblock = bytearray(base)
    put_u16(bad_schema_freeblock, 101, 3)  # page-1 first-freeblock below the header
    cases.extend([
        ("bad-record-serial-type", bytes(bad_serial_type), "serial types 10 and 11"),
        ("bad-schema-type", bytes(bad_schema_type), "type is unknown"),
        ("bad-schema-rootpage", bytes(bad_schema_rootpage_oob), "outside the logical database"),
        ("bad-schema-rootpage-negative", bytes(bad_schema_rootpage_negative), "rootpage must be non-negative"),
        ("bad-page-freeblock", bytes(bad_schema_freeblock), "freeblock"),
    ])

    # User-table overflow-chain traversal corruptions.  Unlike every case above,
    # these damage a `next` pointer INSIDE a user table's overflow page, so the
    # open-time openReadonly path (header + pager + page-1 schema) accepts the
    # file; the corruption only surfaces when the row is actually traversed.
    # overflow-thresholds.db pins a p=1100 row (rowid 7) whose payload spills a
    # two-page overflow chain, page 11 -> page 12; bytes 0..3 of page 11 hold its
    # `next` pointer.  These mutations are the only ones aimed at a non-schema
    # page, and are pinned LAST so a full regen stays byte-identical.
    ov = overflow.read_bytes()
    ov_page_size, _, ov_pages, _, _, _ = header_meta(ov)
    ov_chain_first = 11
    ov_next_offset = (ov_chain_first - 1) * ov_page_size
    assert ov_pages == 12, f"overflow-thresholds page count changed: {ov_pages}"
    assert struct.unpack(">I", ov[ov_next_offset:ov_next_offset + 4])[0] == 12, \
        "overflow-thresholds page-11 next pointer is no longer 12"
    ov_truncated = bytearray(ov); put_u32(ov_truncated, ov_next_offset, 0)
    ov_out_of_range = bytearray(ov); put_u32(ov_out_of_range, ov_next_offset, 99)  # 99 > 12-page file
    ov_cycle = bytearray(ov); put_u32(ov_cycle, ov_next_offset, ov_chain_first)  # self-loop
    cases.extend([
        ("overflow-chain-truncated", bytes(ov_truncated), "overflow chain ended early or points out of range"),
        ("overflow-chain-out-of-range", bytes(ov_out_of_range), "overflow chain ended early or points out of range"),
        ("overflow-chain-cycle", bytes(ov_cycle), "overflow chain contains a cycle"),
    ])
    rows = ["id\tpath\tsha256\texpected"]
    for fixture_id, data, expected in cases:
        path = CORRUPT / f"{fixture_id}.db"
        path.write_bytes(data)
        rel = path.relative_to(ROOT.parent.parent.parent.parent).as_posix()
        rows.append(f"{fixture_id}\t{rel}\t{hashlib.sha256(data).hexdigest()}\t{expected}")
    (ROOT / "corrupt-manifest.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")


def main() -> None:
    if sqlite3.sqlite_version != ORACLE_VERSION:
        raise SystemExit(f"requires sqlite {ORACLE_VERSION}, got {sqlite3.sqlite_version}")
    probe = sqlite3.connect(":memory:")
    source_id = probe.execute("SELECT sqlite_source_id()").fetchone()[0]
    compile_options = "\n".join(row[0] for row in probe.execute("PRAGMA compile_options"))
    probe.close()
    if source_id != ORACLE_SOURCE_ID:
        raise SystemExit(f"requires source id {ORACLE_SOURCE_ID}, got {source_id}")

    helper_value = os.environ.get("SCLJET_RESERVED_GENERATOR")
    if not helper_value:
        raise SystemExit(
            "set SCLJET_RESERVED_GENERATOR to generate-reserved.c compiled with "
            "the official SQLite 3.53.3 amalgamation"
        )
    reserved_helper = Path(helper_value).resolve()
    if not reserved_helper.is_file() or not os.access(reserved_helper, os.X_OK):
        raise SystemExit(f"reserved-byte generator is not executable: {reserved_helper}")

    historical_value = os.environ.get("SCLJET_HISTORICAL_SQLITE")
    manifest_value = os.environ.get("SCLJET_HISTORICAL_MANIFEST_UUID")
    if not historical_value or not manifest_value:
        raise SystemExit(
            "set SCLJET_HISTORICAL_SQLITE and SCLJET_HISTORICAL_MANIFEST_UUID "
            "to an official canonical SQLite 3.2.0 build and its manifest.uuid"
        )
    historical_helper = Path(historical_value).resolve()
    historical_manifest = Path(manifest_value).resolve()
    if not historical_helper.is_file() or not os.access(historical_helper, os.X_OK):
        raise SystemExit(f"historical sqlite is not executable: {historical_helper}")
    if not historical_manifest.is_file():
        raise SystemExit(f"historical manifest is not a file: {historical_manifest}")
    actual_manifest = historical_manifest.read_text(encoding="ascii").strip()
    if actual_manifest != HISTORICAL_MANIFEST_UUID:
        raise SystemExit(
            f"requires historical manifest {HISTORICAL_MANIFEST_UUID}, got {actual_manifest}"
        )
    historical_probe = subprocess.run(
        [str(historical_helper), "-version"],
        check=False,
        capture_output=True,
        text=True,
    )
    historical_version = historical_probe.stdout.strip()
    if historical_version != HISTORICAL_VERSION or historical_probe.stderr:
        raise SystemExit(
            f"requires historical sqlite {HISTORICAL_VERSION}, got "
            f"{historical_version!r} / {historical_probe.stderr.strip()!r}"
        )

    VALID.mkdir(exist_ok=True)
    CORRUPT.mkdir(exist_ok=True)
    for path in list(VALID.glob("*.db")) + list(CORRUPT.glob("*.db")):
        path.unlink()

    fixtures: list[tuple[str, Path]] = []
    for size in (512, 1024, 2048, 4096, 8192, 16384, 32768, 65536):
        path = VALID / f"page-{size}.db"
        create_simple(path, size)
        fixtures.append((f"page-{size}", path))
    for encoding, slug in (("UTF-16le", "utf16le"), ("UTF-16be", "utf16be")):
        path = VALID / f"{slug}.db"
        create_simple(path, 4096, encoding)
        fixtures.append((slug, path))
    comprehensive = VALID / "comprehensive.db"
    create_comprehensive(comprehensive); fixtures.append(("comprehensive", comprehensive))
    freelist = VALID / "freelist.db"
    create_freelist(freelist); fixtures.append(("freelist", freelist))
    auto_full = VALID / "auto-full.db"
    create_autovacuum(auto_full, "FULL", True); fixtures.append(("auto-full", auto_full))
    auto_incremental = VALID / "auto-incremental.db"
    create_autovacuum(auto_incremental, "INCREMENTAL", False); fixtures.append(("auto-incremental", auto_incremental))
    empty = VALID / "empty-encoding-zero.db"
    create_empty(empty); fixtures.append(("empty-encoding-zero", empty))
    serial_edges = VALID / "serial-rowid-edges.db"
    create_serial_and_rowid_edges(serial_edges); fixtures.append(("serial-rowid-edges", serial_edges))
    wal_clean = VALID / "wal-clean.db"
    create_clean_wal_header(wal_clean); fixtures.append(("wal-clean", wal_clean))
    for fixture_id, page_size, reserved in (
        ("reserved-1", 512, 1),
        ("reserved-7", 512, 7),
        ("reserved-32-usable-480", 512, 32),
    ):
        path = VALID / f"{fixture_id}.db"
        create_reserved(path, page_size, reserved, reserved_helper)
        fixtures.append((fixture_id, path))
    for fixture_id, schema_format, sql in (
        (
            "schema-format-1",
            1,
            "PRAGMA page_size=512; CREATE TABLE t(a INTEGER, b TEXT); "
            "INSERT INTO t VALUES(7, 'format1');",
        ),
        (
            "schema-format-2",
            2,
            "PRAGMA page_size=512; CREATE TABLE t(a INTEGER); "
            "ALTER TABLE t ADD COLUMN b; INSERT INTO t VALUES(7, NULL);",
        ),
        (
            "schema-format-3",
            3,
            "PRAGMA page_size=512; CREATE TABLE t(a INTEGER); "
            "ALTER TABLE t ADD COLUMN b DEFAULT 9; INSERT INTO t VALUES(7, 9);",
        ),
    ):
        path = VALID / f"{fixture_id}.db"
        create_historical(path, schema_format, sql, historical_helper)
        fixtures.append((fixture_id, path))
    overflow_thresholds = VALID / "overflow-thresholds.db"
    create_overflow_thresholds(overflow_thresholds)
    fixtures.append(("overflow-thresholds", overflow_thresholds))
    index_overflow_thresholds = VALID / "index-overflow-thresholds.db"
    create_index_overflow_thresholds(index_overflow_thresholds)
    fixtures.append(("index-overflow-thresholds", index_overflow_thresholds))

    write_manifest(fixtures, source_id, compile_options)
    corruptions(VALID / "page-512.db", auto_full, freelist, overflow_thresholds)
    for suffix in ("-wal", "-shm"):
        sidecar = Path(str(wal_clean) + suffix)
        if sidecar.exists():
            sidecar.unlink()


if __name__ == "__main__":
    main()
