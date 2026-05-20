package scalascript.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.sql.{Connection, PreparedStatement}
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
  def close(): Unit

object PgClient:
  def connect(config: PgConfig)(using ec: ExecutionContext): PgClient =
    val hc = HikariConfig()
    hc.setJdbcUrl(config.jdbcUrl)
    hc.setUsername(config.user)
    hc.setPassword(config.password)
    hc.setMaximumPoolSize(config.poolSize)
    hc.setPoolName("scalascript-pg")
    new HikariPgClient(new HikariDataSource(hc))

  def connect(url: String)(using ec: ExecutionContext): PgClient =
    val hc = HikariConfig()
    hc.setJdbcUrl(url)
    hc.setMaximumPoolSize(10)
    new HikariPgClient(new HikariDataSource(hc))

private class HikariPgClient(ds: HikariDataSource)(using ec: ExecutionContext) extends PgClient:

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
      val txClient = TransactionClient(conn)
      f(txClient).transform(
        result => { conn.commit(); conn.close(); result },
        err    => { Try(conn.rollback()); conn.close(); throw err }
      )
    }

  def close(): Unit = ds.close()

  private def withStmt[A](conn: Connection, sql: String, params: Seq[Any])(f: PreparedStatement => A): A =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      // v1.26.1 — delegate to the shared `Jdbc.bindAll` so PgClient
      // picks up the full type matrix (java.time.*, BigInt, UUID,
      // Array[Byte], boxed primitives) and any future additions
      // without a separate edit here.
      Jdbc.bindAll(ps, params)
      f(ps)
    }

// Used inside transaction{} — shares one connection, no pool
private class TransactionClient(conn: Connection)(using ec: ExecutionContext) extends PgClient:
  private def withStmt[A](sql: String, params: Seq[Any])(f: PreparedStatement => A): A =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      // v1.26.1 — was using a dumber per-param `setObject` path that
      // (a) didn't recursively unwrap nested `Some(Some(x))`, (b)
      // skipped typed setters for String / Int / Long / Boolean /
      // BigDecimal, and (c) didn't handle any java.time / UUID /
      // Array[Byte] type.  Delegating to the shared `Jdbc.bindAll`
      // makes the tx path consistent with the pooled `HikariPgClient`
      // and inherits every type the rest of the JDBC stack supports.
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

  def transaction[A](f: PgClient => Future[A]): Future[A] = f(this)

  def close(): Unit = ()
