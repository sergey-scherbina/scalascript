package scalascript.gov.scheduler

import java.time.{Instant, LocalDate, ZoneId}
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.jdk.CollectionConverters.*

/** Single-threaded scheduler backed by `ScheduledExecutorService`.
 *
 *  Jobs are registered with a `JobSpec` and can be:
 *  - triggered manually via `runNow(id)`
 *  - disabled/re-enabled via `disable(id)` / `enable(id)`
 *  - observed via `onJobComplete` / `onJobFailed` callbacks */
class SimpleScheduler(
  zone:    ZoneId = ZoneId.systemDefault(),
  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
):
  private val jobs     = new ConcurrentHashMap[String, ScheduledJob]()
  private val futures  = new ConcurrentHashMap[String, ScheduledFuture[?]]()
  private val handlers = new ConcurrentHashMap[String, () => Unit]()

  @volatile private var onComplete: (String, JobStatus.Succeeded.type) => Unit = (_, _) => ()
  @volatile private var onFailed:   (String, Throwable) => Unit                = (_, _) => ()

  def onJobComplete(cb: (String, JobStatus.Succeeded.type) => Unit): Unit = onComplete = cb
  def onJobFailed(cb: (String, Throwable) => Unit): Unit                  = onFailed   = cb

  def register(id: String, spec: JobSpec)(task: => Unit): ScheduledJob =
    val job = ScheduledJob(id, spec)
    jobs.put(id, job)
    handlers.put(id, () => task)
    scheduleJob(id, spec)
    job

  def runNow(id: String): Unit =
    if !jobs.containsKey(id) then throw new NoSuchElementException(s"Job not found: $id")
    if jobs.get(id).status == JobStatus.Disabled then
      throw new IllegalStateException(s"Job $id is disabled")
    executor.submit((() => executeJob(id)): Runnable)
    ()

  def disable(id: String): Unit =
    updateStatus(id, JobStatus.Disabled)
    Option(futures.remove(id)).foreach(_.cancel(false))

  def enable(id: String): Unit =
    val job = getJob(id)
    job.status match
      case JobStatus.Disabled =>
        updateStatus(id, JobStatus.Pending)
        scheduleJob(id, job.spec)
      case _ => ()

  def getJob(id: String): ScheduledJob =
    Option(jobs.get(id)).getOrElse(throw new NoSuchElementException(s"Job not found: $id"))

  def listJobs(): List[ScheduledJob] = jobs.values().asScala.toList

  def shutdown(): Unit =
    executor.shutdown()
    executor.awaitTermination(5, TimeUnit.SECONDS)
    ()

  private def scheduleJob(id: String, spec: JobSpec): Unit =
    spec match
      case JobSpec.OneTime(delay) =>
        val f = executor.schedule(
          (() => executeJob(id)): Runnable,
          delay.toMillis, TimeUnit.MILLISECONDS
        )
        futures.put(id, f)

      case JobSpec.Recurring(interval, initialDelay) =>
        val f = executor.scheduleAtFixedRate(
          (() => executeJob(id)): Runnable,
          initialDelay.toMillis, interval.toMillis, TimeUnit.MILLISECONDS
        )
        futures.put(id, f)

      case JobSpec.PeriodJob(dayOfPeriod, period) =>
        schedulePeriodJob(id, dayOfPeriod, period)

  private def schedulePeriodJob(id: String, dayOfPeriod: Int, period: Period): Unit =
    val now     = LocalDate.now(zone)
    val target  = nextPeriodJobDate(now, dayOfPeriod, period)
    val runAt   = target.atStartOfDay(zone).toInstant
    val delayMs = java.time.Duration.between(Instant.now(), runAt).toMillis.max(0L)
    val f = executor.schedule(
      (() => {
        executeJob(id)
        // reschedule for next period
        val job = getJob(id)
        if job.status != JobStatus.Disabled then
          schedulePeriodJob(id, dayOfPeriod, period)
      }): Runnable,
      delayMs, TimeUnit.MILLISECONDS
    )
    futures.put(id, f)

  private[scheduler] def nextPeriodJobDate(from: LocalDate, dayOfPeriod: Int, period: Period): LocalDate =
    period match
      case Period.Monthly =>
        val candidate = nthBusinessDayOfMonth(from.getYear, from.getMonthValue, dayOfPeriod)
        if candidate.isAfter(from) then candidate
        else
          val next = from.plusMonths(1)
          nthBusinessDayOfMonth(next.getYear, next.getMonthValue, dayOfPeriod)

      case Period.Annual(month) =>
        val candidate = nthBusinessDayOfMonth(from.getYear, month, dayOfPeriod)
        if candidate.isAfter(from) then candidate
        else nthBusinessDayOfMonth(from.getYear + 1, month, dayOfPeriod)

  private def nthBusinessDayOfMonth(year: Int, month: Int, n: Int): LocalDate =
    val start = LocalDate.of(year, month, 1)
    Iterator.iterate(start)(_.plusDays(1))
      .filter(BureauCalendar.isBusinessDay)
      .drop(n - 1)
      .next()

  private def executeJob(id: String): Unit =
    updateStatus(id, JobStatus.Running)
    try
      Option(handlers.get(id)).foreach(_.apply())
      updateStatus(id, JobStatus.Succeeded)
      onComplete(id, JobStatus.Succeeded)
    catch case t: Throwable =>
      updateStatus(id, JobStatus.Failed(t.getMessage))
      onFailed(id, t)

  private def updateStatus(id: String, status: JobStatus): Unit =
    Option(jobs.get(id)).foreach { job =>
      jobs.put(id, job.copy(status = status))
    }
