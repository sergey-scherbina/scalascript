//> using scala 3

def sum(n: Long, acc: Long): Long =
  if n <= 0 then acc else sum(n - 1, acc + n)

@main def run(): Unit =
  val n      = 1000000L
  val t0     = System.nanoTime()
  val result = sum(n, 0L)
  val t1     = System.nanoTime()
  println(s"BENCH_MS: ${(t1 - t0) / 1000000}")
  println(s"result=$result")
