package scalascript.wallet.vault.ledger.js

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

final class MockWebHidDevice(initialReports: Array[Byte]*) extends WebHidDevice:
  private val reports = mutable.Queue.from(initialReports)
  val sentReports = mutable.ArrayBuffer.empty[(Int, Array[Byte])]
  private var openFlag = false

  def queueReport(report: Array[Byte]): Unit = reports.enqueue(report)
  def queueApdu(apdu: Array[Byte]): Unit = WebHidFraming.encode(apdu).foreach(queueReport)

  def open()(using ExecutionContext): Future[Unit] = { openFlag = true; Future.successful(()) }
  def close()(using ExecutionContext): Future[Unit] = { openFlag = false; Future.successful(()) }
  def isOpen: Boolean = openFlag
  def sendReport(reportId: Int, data: Array[Byte])(using ExecutionContext): Future[Unit] =
    sentReports += ((reportId, data.clone()))
    Future.successful(())
  def receiveReport()(using ExecutionContext): Future[Array[Byte]] =
    if reports.nonEmpty then Future.successful(reports.dequeue())
    else Future.failed(new IllegalStateException("no queued HID report"))
