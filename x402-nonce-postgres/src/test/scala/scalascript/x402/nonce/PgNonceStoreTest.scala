package scalascript.x402.nonce

import scalascript.db.{PgClient, RowDecoder}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

class PgNonceStoreTest extends AnyFunSuite:

  private class MockPg(insertResult: Int = 1) extends PgClient:
    val sqls   = scala.collection.mutable.Buffer.empty[String]
    val params = scala.collection.mutable.Buffer.empty[Seq[Any]]

    def query[A](sql: String, ps: Any*)(using d: RowDecoder[A]): Future[List[A]] =
      Future.successful(Nil)
    def queryOne[A](sql: String, ps: Any*)(using d: RowDecoder[A]): Future[Option[A]] =
      Future.successful(None)
    def execute(sql: String, ps: Any*): Future[Int] =
      sqls += sql
      params += ps.toSeq
      Future.successful(insertResult)
    def transaction[A](f: PgClient => Future[A]): Future[A] = f(this)
    def close(): Unit = ()

  test("claim: rowcount=1 → true (first use)") {
    val pg    = MockPg(1)
    val store = PgNonceStore(pg)
    val result = Await.result(store.claim("0x" + "ab" * 32, BigInt(9_999_999_999L)), 5.seconds)
    assert(result)
  }

  test("claim: rowcount=0 → false (replay)") {
    val pg    = MockPg(0)
    val store = PgNonceStore(pg)
    val result = Await.result(store.claim("0x" + "aa" * 32, BigInt(9_999_999_999L)), 5.seconds)
    assert(!result)
  }

  test("claim executes INSERT with ON CONFLICT DO NOTHING") {
    val pg    = MockPg(1)
    val store = PgNonceStore(pg)
    Await.result(store.claim("0xabcd", BigInt(9_999_999_999L)), 5.seconds)
    assert(pg.sqls.exists(s => s.contains("INSERT") && s.contains("ON CONFLICT")))
    val insertParams = pg.params.head
    assert(insertParams(0) == "0xabcd")
  }

  test("cleanup executes DELETE WHERE valid_before <= now") {
    val pg    = MockPg()
    val store = PgNonceStore(pg)
    Await.result(store.cleanup(), 5.seconds)
    assert(pg.sqls.exists(s => s.contains("DELETE") && s.contains("valid_before")))
  }

  test("createTable SQL contains expected columns") {
    assert(PgNonceStore.createTable.contains("x402_nonces"))
    assert(PgNonceStore.createTable.contains("nonce"))
    assert(PgNonceStore.createTable.contains("valid_before"))
  }
