package scalascript.wallet.vault.trezor

import scala.concurrent.{ExecutionContext, Future}

/** Manages the acquire → use → release lifecycle for a Trezor Bridge
 *  session. Each call to `withSession` acquires a fresh session, runs
 *  `f`, and always releases afterward (even on failure). */
class TrezorSession(
  bridge:     TrezorBridge,
  devicePath: String,
)(using ec: ExecutionContext):

  def withSession[A](f: String => Future[A]): Future[A] =
    bridge.acquire(devicePath, None).flatMap { session =>
      f(session).transformWith { result =>
        bridge.release(session).transform(_ => result)
      }
    }
