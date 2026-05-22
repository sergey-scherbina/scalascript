// v1.27 Phase 2 — sql.js provider end-to-end tests on Node.
//
// Run via `node --test --test-force-exit`.  --test-force-exit is required
// because some SQLite/Worker resources keep handles after close on
// older Node versions; in production code we want graceful close, but
// the test harness should not hang waiting on bookkeeping.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ConnectionRegistry, execute, isResultSetProducer, Providers,
  SqlJsProvider, makeRow, MissingFs, SqlRuntimeError,
} from './sql-runtime.mjs'

const reg = () => new ConnectionRegistry({ default: { url: 'sqlite::memory:' } })

test('sql.js: CREATE / INSERT / SELECT round-trip', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    const r1 = await execute(conn, 'CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)', [])
    assert.equal(r1.kind, 'update')
    const r2 = await execute(conn, 'INSERT INTO t(id, name) VALUES (?, ?)', [1, 'a'])
    assert.equal(r2.kind, 'update')
    assert.equal(r2.count, 1)
    const r3 = await execute(conn, 'SELECT * FROM t WHERE id = ?', [1])
    assert.equal(r3.kind, 'rows')
    assert.equal(r3.rows.length, 1)
    assert.equal(r3.rows[0](0), 1)
    assert.equal(r3.rows[0]('NAME'), 'a')  // case-insensitive name lookup
  } finally { await r.close() }
})

test('sql.js: multi-row order preserved', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(n INTEGER)', [])
    await execute(conn, 'INSERT INTO t VALUES (?), (?), (?)', [3, 1, 2])
    const r3 = await execute(conn, 'SELECT n FROM t ORDER BY n', [])
    assert.deepEqual(r3.rows.map(row => row(0)), [1, 2, 3])
  } finally { await r.close() }
})

test('sql.js: null binds → NULL', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(a TEXT, b TEXT)', [])
    await execute(conn, 'INSERT INTO t(a, b) VALUES (?, ?)', [null, undefined])
    const r3 = await execute(conn, 'SELECT a, b FROM t', [])
    assert.equal(r3.rows.length, 1)
    assert.equal(r3.rows[0](0), null)
    assert.equal(r3.rows[0](1), null)
  } finally { await r.close() }
})

test('sql.js: BLOB round-trip (Uint8Array)', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(b BLOB)', [])
    const data = new Uint8Array([1, 2, 3, 250, 99])
    await execute(conn, 'INSERT INTO t VALUES (?)', [data])
    const r3 = await execute(conn, 'SELECT b FROM t', [])
    assert.equal(r3.rows.length, 1)
    const got = r3.rows[0](0)
    assert.ok(got instanceof Uint8Array, `expected Uint8Array, got ${got?.constructor?.name}`)
    assert.deepEqual([...got], [...data])
  } finally { await r.close() }
})

test('sql.js: boolean binds → 0/1 INTEGER', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(b INTEGER)', [])
    await execute(conn, 'INSERT INTO t VALUES (?), (?)', [true, false])
    const r3 = await execute(conn, 'SELECT b FROM t ORDER BY b', [])
    assert.deepEqual(r3.rows.map(r => r(0)), [0, 1])
  } finally { await r.close() }
})

test('sql.js: Date bind → ISO string TEXT', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(d TEXT)', [])
    const d = new Date('2026-05-20T12:34:56Z')
    await execute(conn, 'INSERT INTO t VALUES (?)', [d])
    const r3 = await execute(conn, 'SELECT d FROM t', [])
    assert.equal(r3.rows[0](0), d.toISOString())
  } finally { await r.close() }
})

test('sql.js: UPDATE returns affected-row count', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(id INTEGER, v TEXT)', [])
    await execute(conn, 'INSERT INTO t VALUES (?, ?), (?, ?), (?, ?)', [1, 'a', 2, 'b', 3, 'c'])
    const upd = await execute(conn, 'UPDATE t SET v = ? WHERE id < ?', ['x', 3])
    assert.equal(upd.kind, 'update')
    assert.equal(upd.count, 2)
  } finally { await r.close() }
})

test('sql.js: PRAGMA is a result-set producer', async () => {
  assert.ok(isResultSetProducer('PRAGMA table_info(t)'))
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(a INTEGER, b TEXT)', [])
    const r3 = await execute(conn, 'PRAGMA table_info(t)', [])
    assert.equal(r3.kind, 'rows')
    assert.ok(r3.rows.length >= 2)
  } finally { await r.close() }
})

test('sql.js: row.toMap() / row.columns() / row.columnCount', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(a INTEGER, b TEXT)', [])
    await execute(conn, 'INSERT INTO t VALUES (?, ?)', [42, 'x'])
    const r3 = await execute(conn, 'SELECT a, b FROM t', [])
    const row = r3.rows[0]
    assert.deepEqual(row.toMap(), { a: 42, b: 'x' })
    assert.deepEqual(row.columns(), ['a', 'b'])
    assert.equal(row.columnCount, 2)
    assert.throws(() => row(99), /out of range/)
    assert.throws(() => row('nope'), /no column/)
  } finally { await r.close() }
})

test('sql.js: ConnectionRegistry caches + reopens after close', async () => {
  const r = reg()
  const c1 = await r.connect('default')
  const c2 = await r.connect('default')
  assert.equal(c1, c2, 'connect(name) cached')
  await r.close()
  // Reopen after close — should work (cache resets).
  const c3 = await r.connect('default')
  assert.notEqual(c1, c3, 'fresh connection after close')
  await r.close()
})
