package scalascript.gov.scheduler

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.time.{LocalDate, Duration as JDuration}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.nowarn

// ─── BureauCalendarTest ─────────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class BureauCalendarTest extends AnyFunSuite with Matchers:

  test("weekends are not business days") {
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 1, 6)) shouldBe false  // Saturday
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 1, 7)) shouldBe false  // Sunday
  }

  test("regular weekdays are business days") {
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 1, 2)) shouldBe true   // Tuesday
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 3, 15)) shouldBe true  // Friday
  }

  test("fixed public holidays are not business days") {
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 1, 1))  shouldBe false // Nowy Rok
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 5, 1))  shouldBe false // Święto Pracy
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 5, 3))  shouldBe false // Konstytucja
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 11, 1)) shouldBe false // Wszyscy Święci
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 11, 11)) shouldBe false // Niepodległość
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 12, 25)) shouldBe false
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 12, 26)) shouldBe false
  }

  test("computeEaster 2024 is March 31") {
    BureauCalendar.computeEaster(2024) shouldBe LocalDate.of(2024, 3, 31)
  }

  test("computeEaster 2025 is April 20") {
    BureauCalendar.computeEaster(2025) shouldBe LocalDate.of(2025, 4, 20)
  }

  test("computeEaster 2026 is April 5") {
    BureauCalendar.computeEaster(2026) shouldBe LocalDate.of(2026, 4, 5)
  }

  test("Easter Monday 2024 (April 1) is not a business day") {
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 4, 1)) shouldBe false
  }

  test("nextBusinessDay skips weekend") {
    val friday   = LocalDate.of(2024, 1, 5)
    val expected = LocalDate.of(2024, 1, 8) // Monday
    BureauCalendar.nextBusinessDay(friday.plusDays(1)) shouldBe expected
  }

  test("nextBusinessDay on business day returns same day") {
    val monday = LocalDate.of(2024, 1, 8)
    BureauCalendar.nextBusinessDay(monday) shouldBe monday
  }

  test("prevBusinessDay skips weekend") {
    val monday   = LocalDate.of(2024, 1, 8)
    val expected = LocalDate.of(2024, 1, 5) // Friday
    BureauCalendar.prevBusinessDay(monday.minusDays(1)) shouldBe expected
  }

  test("businessDaysBetween counts correctly for a week") {
    val mon = LocalDate.of(2024, 1, 8)
    val sat = LocalDate.of(2024, 1, 13)
    BureauCalendar.businessDaysBetween(mon, sat) shouldBe 5
  }

  test("Epiphany (Jan 6) is a public holiday") {
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 1, 6)) shouldBe false
  }

  test("Corpus Christi 2024 (May 30) is a public holiday") {
    BureauCalendar.isBusinessDay(LocalDate.of(2024, 5, 30)) shouldBe false
  }

// ─── SimpleSchedulerTest ────────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class SimpleSchedulerTest extends AnyFunSuite with Matchers:

  test("register and runNow executes job synchronously") {
    val s      = SimpleScheduler()
    val latch  = CountDownLatch(1)
    s.register("job1", JobSpec.OneTime(JDuration.ofHours(1))) { latch.countDown() }
    s.runNow("job1")
    latch.await(2, TimeUnit.SECONDS) shouldBe true
    s.shutdown()
  }

  test("runNow on unknown job throws NoSuchElementException") {
    val s = SimpleScheduler()
    intercept[NoSuchElementException] { s.runNow("nonexistent") }
    s.shutdown()
  }

  test("disable prevents runNow") {
    val s = SimpleScheduler()
    s.register("j", JobSpec.OneTime(JDuration.ofHours(1))) { () }
    s.disable("j")
    intercept[IllegalStateException] { s.runNow("j") }
    s.shutdown()
  }

  test("enable after disable allows runNow again") {
    val s     = SimpleScheduler()
    val count = AtomicInteger(0)
    s.register("j", JobSpec.Recurring(JDuration.ofHours(1))) { count.incrementAndGet(); () }
    s.disable("j")
    s.enable("j")
    val latch = CountDownLatch(1)
    s.onJobComplete((_, _) => latch.countDown())
    s.runNow("j")
    latch.await(2, TimeUnit.SECONDS) shouldBe true
    s.shutdown()
  }

  test("job status transitions: Pending → Running → Succeeded") {
    val s     = SimpleScheduler()
    val latch = CountDownLatch(1)
    s.register("j", JobSpec.OneTime(JDuration.ofHours(1))) { () }
    s.onJobComplete((_, _) => latch.countDown())
    s.runNow("j")
    latch.await(2, TimeUnit.SECONDS) shouldBe true
    s.getJob("j").status shouldBe JobStatus.Succeeded
    s.shutdown()
  }

  test("failed job status is Failed with message") {
    val s     = SimpleScheduler()
    val latch = CountDownLatch(1)
    s.register("j", JobSpec.OneTime(JDuration.ofHours(1))) { throw RuntimeException("boom") }
    s.onJobFailed((_, _) => latch.countDown())
    s.runNow("j")
    latch.await(2, TimeUnit.SECONDS) shouldBe true
    s.getJob("j").status match
      case JobStatus.Failed(msg) => msg should include("boom")
      case other                 => fail(s"Expected Failed, got $other")
    s.shutdown()
  }

  test("onJobComplete callback is invoked") {
    val s       = SimpleScheduler()
    val called  = AtomicInteger(0)
    val latch   = CountDownLatch(1)
    s.register("j", JobSpec.OneTime(JDuration.ofHours(1))) { () }
    s.onJobComplete((_, _) => { called.incrementAndGet(); latch.countDown() })
    s.runNow("j")
    latch.await(2, TimeUnit.SECONDS) shouldBe true
    called.get() shouldBe 1
    s.shutdown()
  }

  test("onJobFailed callback is invoked on exception") {
    val s      = SimpleScheduler()
    val latch  = CountDownLatch(1)
    var caught = ""
    s.register("j", JobSpec.OneTime(JDuration.ofHours(1))) { throw RuntimeException("err") }
    s.onJobFailed((_, t) => { caught = t.getMessage; latch.countDown() })
    s.runNow("j")
    latch.await(2, TimeUnit.SECONDS) shouldBe true
    caught shouldBe "err"
    s.shutdown()
  }

  test("listJobs returns all registered jobs") {
    val s = SimpleScheduler()
    s.register("a", JobSpec.OneTime(JDuration.ofHours(1))) { () }
    s.register("b", JobSpec.OneTime(JDuration.ofHours(2))) { () }
    val ids = s.listJobs().map(_.id).toSet
    ids should contain("a")
    ids should contain("b")
    s.shutdown()
  }

  test("disabled job status is Disabled") {
    val s = SimpleScheduler()
    s.register("j", JobSpec.OneTime(JDuration.ofHours(1))) { () }
    s.disable("j")
    s.getJob("j").status shouldBe JobStatus.Disabled
    s.shutdown()
  }

  test("recurring job fires via runNow multiple times") {
    val s     = SimpleScheduler()
    val count = AtomicInteger(0)
    val latch = CountDownLatch(2)
    s.register("j", JobSpec.Recurring(JDuration.ofHours(1), JDuration.ofHours(1))) {
      count.incrementAndGet()
      latch.countDown()
    }
    s.runNow("j")
    s.runNow("j")
    latch.await(2, TimeUnit.SECONDS) shouldBe true
    count.get() shouldBe 2
    s.shutdown()
  }

// ─── NextPeriodJobDateTest ──────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class NextPeriodJobDateTest extends AnyFunSuite with Matchers:

  private val s = SimpleScheduler()

  test("nextPeriodJobDate Monthly: returns 1st business day of next month when past current") {
    val from     = LocalDate.of(2024, 1, 31)
    val expected = s.nextPeriodJobDate(from, 1, Period.Monthly)
    expected.isAfter(from) shouldBe true
    expected.getYear  shouldBe 2024
    expected.getMonthValue shouldBe 2
    BureauCalendar.isBusinessDay(expected) shouldBe true
  }

  test("nextPeriodJobDate Monthly: returns Nth business day of current month when in future") {
    val from = LocalDate.of(2024, 1, 2)
    val res  = s.nextPeriodJobDate(from, 5, Period.Monthly)
    res.getMonthValue shouldBe 1
    BureauCalendar.isBusinessDay(res) shouldBe true
  }

  test("nextPeriodJobDate Annual: returns 1st business day of target month") {
    val from = LocalDate.of(2024, 1, 1)
    val res  = s.nextPeriodJobDate(from, 1, Period.Annual(3))
    res.getMonthValue shouldBe 3
    BureauCalendar.isBusinessDay(res) shouldBe true
  }

  test("nextPeriodJobDate Annual: rolls to next year when month already passed") {
    val from = LocalDate.of(2024, 6, 15)
    val res  = s.nextPeriodJobDate(from, 1, Period.Annual(3))
    res.getYear shouldBe 2025
    res.getMonthValue shouldBe 3
    BureauCalendar.isBusinessDay(res) shouldBe true
  }
