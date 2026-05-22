package scalascript.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.sql.{Connection, PreparedStatement, ResultSet}
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.{Try, Using}

import scalascript.sql.Jdbc

/** Async PostgreSQL client backed by HikariCP + JDBC.
 *  All JDBC calls run on a blocking thread pool via Future.
 */
trait PgClient:
  def query[A](sql: String, params: Any*)(using RowDecoder[A]): Future[List[A]]
  def queryOne[A](sql: String, params: Any*)(using RowDecoder[A]): Future[Option[A]]
  def execute(sql: String, params: Any*): Future[Int]
  def transaction[A](f: PgClient => Future[A]): Future[A]

  /** Cursor-based streaming: processes each row via callback without loading all into memory.
   *  Uses JDBC `setFetchSize` for server-side cursor; autoCommit is temporarily disabled
   *  (required by PostgreSQL for server-side cursors; harmless on H2/SQLite).
   */
  def stream[A](sql: String, params: Any*)(f: A => Unit)(using RowDecoder[A]): Future[Unit]

  /** Cursor-based fold over a result set without loading all rows into memory. */
  def foldLeft[A, B](sql: String, params: Any*)(init: B)(f: (B, A) => B)(using RowDecoder[A]): Future[B]

  def close(): Unit

object PgClient:
  def connect(config: PgConfig)(using ec: ExecutionContext): PgClient =
    val hc = HikariConfig()
    hc.setJdbcUrl(config.jdbcUrl)
    hc.setUsername(config.user)
    hc.setPassword(config.password)
    hc.setMaximumPoolSize(config.poolSize)
    hc.setPoolName("scalascript-pg")
    new HikariPgClient(new HikariDataSource(hc), config.fetchSize)

  def connect(url: String, fetchSize: Int = 1000)(using ec: ExecutionContext): PgClient =
    val hc = HikariConfig()
    hc.setJdbcUrl(url)
    hc.setMaximumPoolSize(10)
    new HikariPgClient(new HikariDataSource(hc), fetchSize)

private class HikariPgClient(ds: HikariDataSource, fetchSize: Int)(using ec: ExecutionContext) extends PgClient:

  def query[A](sql: String, params: Any*)(using dec: RowDecoder[A]): Future[List[A]] =
    Future(blocking {
      Using.resource(ds.getConnection()) { conn =>
        withStmt(conn, sql, params) { ps =>
          val rs = ps.executeQuery()
          val buf = List.newBuilder[A]
          while rs.next() do buf += dec.decode(rs)
          buf.result()
        }
      }
    })

  def queryOne[A](sql: String, params: Any*)(using dec: RowDecoder[A]): Future[Option[A]] =
    Future(blocking {
      Using.resource(ds.getConnection()) { conn =>
        withStmt(conn, sql, params) { ps =>
          val rs = ps.executeQuery()
          if rs.next() then Some(dec.decode(rs)) else None
        }
      }
    })

  def execute(sql: String, params: Any*): Future[Int] =
    Future(blocking {
      Using.resource(ds.getConnection()) { conn =>
        withStmt(conn, sql, params)(_.executeUpdate())
      }
    })

  def transaction[A](f: PgClient => Future[A]): Future[A] =
    Future(blocking { ds.getConnection() }).flatMap { conn =>
      conn.setAutoCommit(false)
      val txClient = TransactionClient(conn, fetchSize)
      f(txClient).transform(
        result => { conn.commit(); conn.close(); result },
        err    => { Try(conn.rollback()); conn.close(); throw err }
      )
    }

  def stream[A](sql: String, params: Any*)(f: A => Unit)(using dec: RowDecoder[A]): Future[Unit] =
    Future(blocking {
      Using.resource(ds.getConnection()) { conn =>
        val prev = conn.getAutoCommit
        conn.setAutoCommit(false)
        try
          withCursorStmt(conn, sql, params) { ps =>
            val rs = ps.executeQuery()
            while rs.next() do f(dec.decode(rs))
          }
        finally
          conn.setAutoCommit(prev)
      }
    })

  def foldLeft[A, B](sql: String, params: Any*)(init: B)(f: (B, A) => B)(using dec: RowDecoder[A]): Future[B] =
    Future(blocking {
      Using.resource(ds.getConnection()) { conn =>
        val prev = conn.getAutoCommit
        conn.setAutoCommit(false)
        try
          withCursorStmt(conn, sql, params) { ps =>
            val rs = ps.executeQuery()
            var acc = init
            while rs.next() do acc = f(acc, dec.decode(rs))
            acc
          }
        finally
          conn.setAutoCommit(prev)
      }
    })

  def close(): Unit = ds.close()

  private def withStmt[A](conn: Connection, sql: String, params: Seq[Any])(f: PreparedStatement => A): A =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      Jdbc.bindAll(ps, params)
      f(ps)
    }

  private def withCursorStmt[A](conn: Connection, sql: String, params: Seq[Any])(f: PreparedStatement => A): A =
    Using.resource(conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) { ps =>
      ps.setFetchSize(fetchSize)
      Jdbc.bindAll(ps, params)
      f(ps)
    }

// Used inside transaction{} — shares one connection, no pool
private class TransactionClient(conn: Connection, fetchSize: Int)(using ec: ExecutionContext) extends PgClient:
  private def withStmt[A](sql: String, params: Seq[Any])(f: PreparedStatement => A): A =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      Jdbc.bindAll(ps, params)
      f(ps)
    }

  private def withCursorStmt[A](sql: String, params: Seq[Any])(f: PreparedStatement => A): A =
    Using.resource(conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) { ps =>
      ps.setFetchSize(fetchSize)
      Jdbc.bindAll(ps, params)
      f(ps)
    }

  def query[A](sql: String, params: Any*)(using dec: RowDecoder[A]): Future[List[A]] =
    Future(blocking {
      withStmt(sql, params) { ps =>
        val rs = ps.executeQuery()
        val buf = List.newBuilder[A]
        while rs.next() do buf += dec.decode(rs)
        buf.result()
      }
    })

  def queryOne[A](sql: String, params: Any*)(using dec: RowDecoder[A]): Future[Option[A]] =
    Future(blocking {
      withStmt(sql, params) { ps =>
        val rs = ps.executeQuery()
        if rs.next() then Some(dec.decode(rs)) else None
      }
    })

  def execute(sql: String, params: Any*): Future[Int] =
    Future(blocking { withStmt(sql, params)(_.executeUpdate()) })

  def stream[A](sql: String, params: Any*)(f: A => Unit)(using dec: RowDecoder[A]): Future[Unit] =
    Future(blocking {
      withCursorStmt(sql, params) { ps =>
        val rs = ps.executeQuery()
        while rs.next() do f(dec.decode(rs))
      }
    })

  def foldLeft[A, B](sql: String, params: Any*)(init: B)(f: (B, A) => B)(using dec: RowDecoder[A]): Future[B] =
    Future(blocking {
      withCursorStmt(sql, params) { ps =>
        val rs = ps.executeQuery()
        var acc = init
        while rs.next() do acc = f(acc, dec.decode(rs))
        acc
      }
    })

  def transaction[A](f: PgClient => Future[A]): Future[A] = f(this)

  def close(): Unit = ()
