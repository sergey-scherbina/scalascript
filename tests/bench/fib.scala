//> using scala 3

def fib(n: Int): Int = if n < 2 then n else fib(n - 1) + fib(n - 2)

@main def run(): Unit =
  val n      = 28
  val t0     = System.nanoTime()
  val result = fib(n)
  val t1     = System.nanoTime()
  println(s"BENCH_MS: ${(t1 - t0) / 1000000}")
  println(s"result=$result")
