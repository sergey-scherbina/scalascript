package scalascript.gov.scheduler

import java.time.Duration

/** Job type definitions for the bureau scheduler. */
sealed trait JobSpec

object JobSpec:
  /** Runs once after `delay`, then never again. */
  case class OneTime(delay: Duration) extends JobSpec

  /** Runs repeatedly every `interval` starting after `initialDelay`. */
  case class Recurring(interval: Duration, initialDelay: Duration = Duration.ZERO) extends JobSpec

  /** Runs on the N-th business day of each period (month or year).
   *  @param dayOfPeriod 1-based business day index within the period
   *  @param period      Monthly or Annual */
  case class PeriodJob(dayOfPeriod: Int, period: Period) extends JobSpec

enum Period:
  case Monthly
  case Annual(month: Int = 1)

/** Runtime state of a registered job. */
enum JobStatus:
  case Pending
  case Running
  case Succeeded
  case Failed(message: String)
  case Disabled

case class ScheduledJob(
  id:      String,
  spec:    JobSpec,
  status:  JobStatus = JobStatus.Pending,
)
