//> using scala 3

@main def run(): Unit =
  val n  = 100000
  val t0 = System.nanoTime()

  val xs     = List.range(0, n)
  val result = xs.map(x => x * 2).filter(x => x % 3 == 0).foldLeft(0L)((a, x) => a + x)

  val t1 = System.nanoTime()
  println(s"BENCH_MS: ${(t1 - t0) / 1000000}")
  println(s"result=$result")
