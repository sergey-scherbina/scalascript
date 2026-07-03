package scalascript.codegen

/** Dataset[T] JVM runtime preamble.
 *
 *  Emitted as Scala source in the generated script when `blocksUseDataset`
 *  detects Dataset usage.  `_Dataset[T]` wraps a lazy source thunk and a
 *  composed pipeline function.  `runParallel()` uses JDK virtual threads
 *  (Project Loom, JDK 21+) to partition and process in parallel. */
val JvmRuntimeDataset: String =
  """|
     |// ── v1.21 Dataset[T] — lazy map-reduce pipeline ──────────────────────────
     |
     |class _Dataset[T](
     |  private val _sourceFn:  () => List[Any],
     |  private val _pipeline:  List[Any] => List[Any],
     |  private val _parallel:  Boolean = false
     |):
     |  // ── Lazy transformations ──────────────────────────────────────────────
     |
     |  def map[U](f: T => U): _Dataset[U] =
     |    new _Dataset(() => _sourceFn(), xs => _pipeline(xs).map(x => f(x.asInstanceOf[T])), _parallel)
     |
     |  def filter(p: T => Boolean): _Dataset[T] =
     |    new _Dataset(() => _sourceFn(), xs => _pipeline(xs).filter(x => p(x.asInstanceOf[T])), _parallel)
     |
     |  def flatMap[U](f: T => List[U]): _Dataset[U] =
     |    new _Dataset(() => _sourceFn(), xs => _pipeline(xs).flatMap(x => f(x.asInstanceOf[T]).asInstanceOf[List[Any]]), _parallel)
     |
     |  def take(n: Int): _Dataset[T] =
     |    new _Dataset(() => _sourceFn(), xs => _pipeline(xs).take(n), _parallel)
     |
     |  def drop(n: Int): _Dataset[T] =
     |    new _Dataset(() => _sourceFn(), xs => _pipeline(xs).drop(n), _parallel)
     |
     |  def distinct: _Dataset[T] =
     |    new _Dataset(() => _sourceFn(), xs => _pipeline(xs).distinct, _parallel)
     |
     |  // Spark-style set ops between two datasets. Both pipelines fire at
     |  // terminal time; multiplicities preserved by union (use .distinct
     |  // after for set semantics). Intersect dedups + preserves left order.
     |  def union(other: _Dataset[T]): _Dataset[T] =
     |    new _Dataset(
     |      () => _pipeline(_sourceFn()) ++ other._pipeline(other._sourceFn()),
     |      xs => xs,
     |      _parallel)
     |
     |  def intersect(other: _Dataset[T]): _Dataset[T] =
     |    new _Dataset(
     |      () =>
     |        val rs = other._pipeline(other._sourceFn()).toSet
     |        _pipeline(_sourceFn()).distinct.filter(rs.contains),
     |      xs => xs,
     |      _parallel)
     |
     |  // Element-wise pairing — stops at shorter side (Scala zip semantics).
     |  def zip[U](other: _Dataset[U]): _Dataset[(T, U)] =
     |    new _Dataset(
     |      () =>
     |        val ls = _pipeline(_sourceFn()).asInstanceOf[List[T]]
     |        val rs = other._pipeline(other._sourceFn()).asInstanceOf[List[U]]
     |        ls.zip(rs).asInstanceOf[List[Any]],
     |      xs => xs,
     |      _parallel)
     |
     |  def zipWithIndex: _Dataset[(T, Int)] =
     |    new _Dataset(
     |      () =>
     |        _pipeline(_sourceFn()).asInstanceOf[List[T]]
     |          .zipWithIndex.asInstanceOf[List[Any]],
     |      xs => xs,
     |      _parallel)
     |
     |  def groupBy[K](key: T => K): _Dataset[(K, List[T])] =
     |    new _Dataset(() => _sourceFn(), xs =>
     |      _pipeline(xs)
     |        .groupBy(x => key(x.asInstanceOf[T]))
     |        .toList
     |        .map { case (k, vs) => (k, vs.asInstanceOf[List[T]]) }
     |        .asInstanceOf[List[Any]],
     |      _parallel)
     |
     |  def reduceByKey[K](key: T => K)(combine: (T, T) => T): _Dataset[(K, T)] =
     |    new _Dataset(() => _sourceFn(), xs =>
     |      _pipeline(xs)
     |        .groupBy(x => key(x.asInstanceOf[T]))
     |        .toList
     |        .map { case (k, vs) => (k, vs.asInstanceOf[List[T]].reduce(combine)) }
     |        .asInstanceOf[List[Any]],
     |      _parallel)
     |
     |  def sortBy[K](key: T => K)(using ord: Ordering[K]): _Dataset[T] =
     |    new _Dataset(() => _sourceFn(), xs =>
     |      _pipeline(xs).sortBy(x => key(x.asInstanceOf[T])),
     |      _parallel)
     |
     |  // ── Execution mode ────────────────────────────────────────────────────
     |
     |  def runLocal(): _Dataset[T] =
     |    new _Dataset(_sourceFn, _pipeline, false)
     |
     |  def runParallel(): _Dataset[T] =
     |    new _Dataset(_sourceFn, _pipeline, true)
     |
     |  // ── Terminal operations ───────────────────────────────────────────────
     |
     |  def collect(): List[T] =
     |    if _parallel then _runParallel() else _pipeline(_sourceFn()).asInstanceOf[List[T]]
     |
     |  def count(): Long = collect().length.toLong
     |
     |  def reduce(combine: (T, T) => T): T =
     |    val xs = if _parallel then _runParallel() else _pipeline(_sourceFn()).asInstanceOf[List[T]]
     |    if xs.isEmpty then throw new RuntimeException("Dataset.reduce: empty dataset")
     |    xs.reduce(combine)
     |
     |  // Numeric / ordered terminal aggregations. min/max use Ordering[T]
     |  // and throw on empty. sum/avg use Numeric[T] (so Int/Long/Double
     |  // pick up their respective stdlib instances).  All four use () to
     |  // match the user-facing call style `ds.sum()` / `ds.min()`.
     |  def min()(using ord: Ordering[T]): T =
     |    val xs = collect()
     |    if xs.isEmpty then throw new RuntimeException("Dataset.min: empty dataset")
     |    xs.min
     |
     |  def max()(using ord: Ordering[T]): T =
     |    val xs = collect()
     |    if xs.isEmpty then throw new RuntimeException("Dataset.max: empty dataset")
     |    xs.max
     |
     |  def sum()(using num: Numeric[T]): T = collect().sum
     |
     |  def avg()(using num: Numeric[T]): Double =
     |    val xs = collect()
     |    if xs.isEmpty then throw new RuntimeException("Dataset.avg: empty dataset")
     |    num.toDouble(xs.sum) / xs.length
     |
     |  // top(n) — n largest by natural Ordering, descending.
     |  // takeOrdered(n) — n smallest by natural Ordering, ascending.
     |  // countByValue — Map[T, Long] of element frequencies.
     |  def top(n: Int)(using ord: Ordering[T]): List[T] = collect().sorted(using ord.reverse).take(n)
     |  def takeOrdered(n: Int)(using ord: Ordering[T]): List[T] = collect().sorted(using ord).take(n)
     |  def countByValue(): Map[T, Long] =
     |    collect().groupBy(identity).map { case (k, vs) => (k, vs.length.toLong) }
     |
     |  // Shape / conversion ops. partition returns Scala's (List[T], List[T])
     |  // tuple. mkString has three overloads matching Scala's List.mkString.
     |  // toMap / toSet / mkString use `()` so user code can call them with
     |  // or without parentheses without colliding with String/Map.apply(i).
     |  def partition(p: T => Boolean): (List[T], List[T]) = collect().partition(p)
     |  def mkString(): String                                                = collect().mkString
     |  def mkString(sep: String): String                                     = collect().mkString(sep)
     |  def mkString(start: String, sep: String, end: String): String         = collect().mkString(start, sep, end)
     |  def toMap[K, V]()(using ev: T <:< (K, V)): Map[K, V]                  = collect().map(ev).toMap
     |  def toSet(): Set[T]                                                   = collect().toSet
     |
     |  // saveToFile(path) — write each element on its own line via _show.
     |  // Counterpart to Dataset.fromFile.
     |  def saveToFile(path: String): Unit =
     |    val body = collect().map(v => _show(v)).mkString("", "\n", "\n")
     |    java.nio.file.Files.write(
     |      java.nio.file.Paths.get(path),
     |      body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
     |    ()
     |
     |  def fold[U](z: U)(combine: (U, T) => U): U =
     |    val xs = if _parallel then _runParallel() else _pipeline(_sourceFn()).asInstanceOf[List[T]]
     |    xs.foldLeft(z)(combine)
     |
     |  def foreach(action: T => Unit): Unit =
     |    (if _parallel then _runParallel() else _pipeline(_sourceFn()).asInstanceOf[List[T]]).foreach(action)
     |
     |  def first(): Option[T] =
     |    (if _parallel then _runParallel() else _pipeline(_sourceFn()).asInstanceOf[List[T]]).headOption
     |
     |  def toGenerator(): _Generator[T] =
     |    val data = if _parallel then _runParallel() else _pipeline(_sourceFn()).asInstanceOf[List[T]]
     |    new _Generator[T]({ () => data.foreach(_suspend) })
     |
     |  // ── Parallel execution (Loom virtual threads) ─────────────────────────
     |
     |  private def _runParallel(): List[T] =
     |    val src    = _sourceFn()
     |    if src.isEmpty then return Nil.asInstanceOf[List[T]]
     |    val ncores = Runtime.getRuntime.availableProcessors.max(2)
     |    val size   = (src.length + ncores - 1) / ncores
     |    val chunks = src.grouped(size).toList
     |    val results = new Array[List[Any]](chunks.length)
     |    val threads = chunks.zipWithIndex.map { case (chunk, i) =>
     |      Thread.ofVirtual().start { () =>
     |        results(i) = _pipeline(chunk)
     |      }
     |    }
     |    threads.foreach(_.join())
     |    results.toList.flatten.asInstanceOf[List[T]]
     |
     |// ── Companion object ─────────────────────────────────────────────────────
     |
     |object _Dataset:
     |  def of[T](items: T*): _Dataset[T] =
     |    val lst = items.toList.asInstanceOf[List[Any]]
     |    new _Dataset[T](() => lst, xs => xs)
     |
     |  def fromList[T](list: List[T]): _Dataset[T] =
     |    val lst = list.asInstanceOf[List[Any]]
     |    new _Dataset[T](() => lst, xs => xs)
     |
     |  def fromGenerator[T](gen: _Generator[T]): _Dataset[T] =
     |    // Materialise once when first terminal op fires (closure captures gen).
     |    new _Dataset[T](() => gen.toList.asInstanceOf[List[Any]], xs => xs)
     |
     |  def fromFile(path: String): _Dataset[String] =
     |    new _Dataset[String](
     |      () => scala.io.Source.fromFile(path).getLines().toList.asInstanceOf[List[Any]],
     |      xs => xs)
     |
     |// User-facing alias — `Dataset.of(...)` in .ssc files.
     |val Dataset = _Dataset
     |""".stripMargin
