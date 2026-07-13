// The P6.0 toy expressions as standard Scala 3 — proves the subset is Scala-valid
// AND semantically agrees (prints 7 5 9 9, matching ssc-v2 run-ir).
object SpikeToy:
  def addMul: Int = 1 + 2 * 3
  def mulAdd: Int = 1 * 2 + 3
  def paren:  Int = (1 + 2) * 3
  def nested: Int = 2 * (3 + 4) - 5
  def main(args: Array[String]): Unit =
    println(s"$addMul $mulAdd $paren $nested")
