package scalascript.sql

/** Discriminated outcome of `SqlRuntime.execute`.
 *
 *  Per SPEC.md § 3.3.1, a `sql` block evaluates to either a sequence
 *  of `Row`s (SELECT family) or an update count (DML / DDL).  The
 *  discriminator is the leading SQL keyword — see
 *  `SqlRuntime.isResultSetProducer`. */
sealed trait SqlResult

object SqlResult:
  /** SELECT / WITH / VALUES / SHOW / EXPLAIN — produces tabular data.
   *  `rows` is eagerly materialised; a streaming variant is deferred
   *  (v1.26 milestone "Out of scope"). */
  final case class Rows(rows: Seq[Row]) extends SqlResult

  /** INSERT / UPDATE / DELETE / MERGE — produces an affected-row count.
   *  CREATE / DROP / ALTER / TRUNCATE / GRANT / REVOKE typically
   *  produce 0 here; the driver may report a non-zero value for some
   *  statements (e.g. `CREATE TABLE AS SELECT`). */
  final case class UpdateCount(count: Int) extends SqlResult
