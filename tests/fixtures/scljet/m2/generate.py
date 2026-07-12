#!/usr/bin/env python3
"""Reproduce the committed SclJet M2 SQLite 3.53.3 corpus.

Ordinary tests consume committed bytes and never invoke this script.  The
Python sqlite3 module must be built against the exact oracle version below.
"""

from __future__ import annotations

import hashlib
import sqlite3
import struct
from pathlib import Path


ORACLE_VERSION = "3.53.3"
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
    page_count = struct.unpack(">I", data[28:32])[0]
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
        elif role == "index" and name == "idx_t_a":
            rows = con.execute("SELECT a,rowid FROM t ORDER BY a,rowid")
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
        "generator", "integrity_check",
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
        rows.append("\t".join([
            fixture_id, rel, hashlib.sha256(data).hexdigest(), ORACLE_VERSION,
            source_id, str(page_size), str(reserved), str(page_count), str(schema_format),
            str(encoding), str(data[18]), str(data[19]), str(auto_vacuum), str(freelist),
            str(schema_entries), f"generate.py:{fixture_id}", "ok",
        ]))
        dump.extend(oracle_dump(rel, path))
    (ROOT / "manifest.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    (ROOT / "oracle-storage.txt").write_text("\n".join(dump) + "\n", encoding="utf-8")
    (ROOT / "oracle-compile-options.txt").write_text(compile_options + "\n", encoding="utf-8")


def corruptions(simple: Path, autovacuum: Path) -> None:
    cases: list[tuple[str, bytes, str]] = []
    base = simple.read_bytes()
    bad_magic = bytearray(base); bad_magic[0] = 0
    bad_kind = bytearray(base); bad_kind[100] = 1
    cases.extend([
        ("bad-magic", bytes(bad_magic), "header.magic"),
        ("truncated-header", base[:99], "header"),
        ("partial-trailing-page", base[:-1], "trailing-page"),
        ("bad-page-kind", bytes(bad_kind), "page.kind"),
    ])
    auto = bytearray(autovacuum.read_bytes()); auto[512] = 0
    cases.append(("bad-pointer-map-kind", bytes(auto), "pointerMap.kind"))
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

    probe = sqlite3.connect(":memory:")
    source_id = probe.execute("SELECT sqlite_source_id()").fetchone()[0]
    compile_options = "\n".join(row[0] for row in probe.execute("PRAGMA compile_options"))
    probe.close()
    write_manifest(fixtures, source_id, compile_options)
    corruptions(VALID / "page-512.db", auto_full)
    for suffix in ("-wal", "-shm"):
        sidecar = Path(str(wal_clean) + suffix)
        if sidecar.exists():
            sidecar.unlink()


if __name__ == "__main__":
    main()
