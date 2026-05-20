// v1.27 Phase 2 — DuckDB-Wasm provider end-to-end tests on Node.
//
// Run via `node --test --test-force-exit`.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ConnectionRegistry, execute } from './sql-runtime.mjs'

const reg = () => new ConnectionRegistry({ default: { url: 'duckdb:' } })

test('duckdb: CREATE / INSERT / SELECT round-trip', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(id INTEGER, name VARCHAR)', [])
    await execute(conn, 'INSERT INTO t VALUES (?, ?)', [1, 'alice'])
    const r3 = await execute(conn, 'SELECT name FROM t WHERE id = ?', [1])
    assert.equal(r3.kind, 'rows')
    assert.equal(r3.rows.length, 1)
    assert.equal(r3.rows[0]('name'), 'alice')
  } finally { await r.close() }
})

test('duckdb: aggregation / GROUP BY', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE events(category VARCHAR, amount INTEGER)', [])
    await execute(conn,
      'INSERT INTO events VALUES (?, ?), (?, ?), (?, ?), (?, ?)',
      ['a', 10, 'a', 20, 'b', 5, 'b', 15])
    const r3 = await execute(conn,
      'SELECT category, SUM(amount) AS total FROM events GROUP BY category ORDER BY category', [])
    assert.equal(r3.rows.length, 2)
    assert.equal(r3.rows[0]('category'), 'a')
    assert.equal(Number(r3.rows[0]('total')), 30)
    assert.equal(r3.rows[1]('category'), 'b')
    assert.equal(Number(r3.rows[1]('total')), 20)
  } finally { await r.close() }
})

test('duckdb: CTE / window function', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(grp VARCHAR, val INTEGER)', [])
    await execute(conn,
      'INSERT INTO t VALUES (?, ?), (?, ?), (?, ?), (?, ?)',
      ['x', 1, 'x', 2, 'y', 3, 'y', 4])
    const r3 = await execute(conn, `
      WITH ranked AS (
        SELECT grp, val, ROW_NUMBER() OVER (PARTITION BY grp ORDER BY val DESC) AS rn
        FROM t
      )
      SELECT grp, val FROM ranked WHERE rn = 1 ORDER BY grp
    `, [])
    assert.equal(r3.rows.length, 2)
    assert.equal(r3.rows[0]('grp'), 'x')
    assert.equal(Number(r3.rows[0]('val')), 2)
    assert.equal(r3.rows[1]('grp'), 'y')
    assert.equal(Number(r3.rows[1]('val')), 4)
  } finally { await r.close() }
})

test('duckdb: null binds', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(a VARCHAR, b VARCHAR)', [])
    await execute(conn, 'INSERT INTO t VALUES (?, ?)', [null, undefined])
    const r3 = await execute(conn, 'SELECT a, b FROM t', [])
    assert.equal(r3.rows.length, 1)
    assert.equal(r3.rows[0](0), null)
    assert.equal(r3.rows[0](1), null)
  } finally { await r.close() }
})

test('duckdb: row toMap + case-insensitive name lookup', async () => {
  const r = reg()
  try {
    const conn = await r.connect('default')
    await execute(conn, 'CREATE TABLE t(a INTEGER, b VARCHAR)', [])
    await execute(conn, 'INSERT INTO t VALUES (?, ?)', [7, 'hi'])
    const r3 = await execute(conn, 'SELECT a, b FROM t', [])
    const row = r3.rows[0]
    assert.equal(Number(row('A')), 7)         // case-insensitive
    assert.equal(row('B'), 'hi')
    const m = row.toMap()
    assert.equal(Number(m.a), 7)
    assert.equal(m.b, 'hi')
  } finally { await r.close() }
})

test('duckdb: ConnectionRegistry caches + reopens after close', async () => {
  const r = reg()
  const c1 = await r.connect('default')
  const c2 = await r.connect('default')
  assert.equal(c1, c2, 'connect(name) cached')
  await r.close()
  const c3 = await r.connect('default')
  assert.notEqual(c1, c3, 'fresh connection after close')
  await r.close()
})
