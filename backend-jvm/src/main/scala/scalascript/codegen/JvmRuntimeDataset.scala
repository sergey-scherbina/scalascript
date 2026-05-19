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
