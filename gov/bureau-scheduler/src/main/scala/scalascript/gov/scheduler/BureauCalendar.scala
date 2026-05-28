package scalascript.gov.scheduler

import java.time.{DayOfWeek, LocalDate}

/** Polish business day calendar for 2024–2026.
 *
 *  A business day is any day that is:
 *  - Not Saturday or Sunday
 *  - Not a Polish public holiday (Kodeks Pracy art. 1519)
 *
 *  Easter is computed with the Meeus/Jones/Butcher algorithm (Gregorian). */
object BureauCalendar:

  /** Returns true if `date` is a Polish business day. */
  def isBusinessDay(date: LocalDate): Boolean =
    !isWeekend(date) && !isPublicHoliday(date)

  /** Returns the next business day on or after `date`. */
  def nextBusinessDay(date: LocalDate): LocalDate =
    Iterator.iterate(date)(_.plusDays(1)).dropWhile(!isBusinessDay(_)).next()

  /** Returns the previous business day on or before `date`. */
  def prevBusinessDay(date: LocalDate): LocalDate =
    Iterator.iterate(date)(_.minusDays(1)).dropWhile(!isBusinessDay(_)).next()

  /** Number of business days between `from` (inclusive) and `to` (exclusive). */
  def businessDaysBetween(from: LocalDate, to: LocalDate): Int =
    Iterator.iterate(from)(_.plusDays(1)).takeWhile(_.isBefore(to)).count(isBusinessDay)

  def isWeekend(date: LocalDate): Boolean =
    date.getDayOfWeek == DayOfWeek.SATURDAY || date.getDayOfWeek == DayOfWeek.SUNDAY

  def isPublicHoliday(date: LocalDate): Boolean =
    fixedHolidays.contains((date.getMonthValue, date.getDayOfMonth)) ||
    easterHolidays(date.getYear).contains(date)

  /** Polish fixed public holidays (month, day). */
  private val fixedHolidays: Set[(Int, Int)] = Set(
    (1,  1),  // Nowy Rok
    (1,  6),  // Trzech Króli (Epiphany)
    (5,  1),  // Święto Pracy
    (5,  3),  // Święto Konstytucji
    (8,  15), // Wniebowzięcie NMP
    (11, 1),  // Wszystkich Świętych
    (11, 11), // Święto Niepodległości
    (12, 25), // Boże Narodzenie I
    (12, 26), // Boże Narodzenie II
  )

  /** Easter-relative Polish holidays for a given year. */
  def easterHolidays(year: Int): Set[LocalDate] =
    val easter = computeEaster(year)
    Set(
      easter,                    // Niedziela Wielkanocna
      easter.plusDays(1),        // Poniedziałek Wielkanocny
      easter.plusDays(49),       // Zielone Świątki (Pentecost)
      easter.plusDays(60),       // Boże Ciało (Corpus Christi)
    )

  /** Meeus/Jones/Butcher Gregorian Easter algorithm.
   *  Returns Easter Sunday for the given year. */
  def computeEaster(year: Int): LocalDate =
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day   = ((h + l - 7 * m + 114) % 31) + 1
    LocalDate.of(year, month, day)
