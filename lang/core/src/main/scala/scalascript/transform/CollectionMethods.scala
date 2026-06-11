package scalascript.transform

/** Canonical taxonomy of collection method names, shared across backends
 *  (T3.3 — `cross-backend-method-classifier`). The single source of truth for
 *  "which method names belong to which semantic category", so a backend's
 *  codegen *classification* decisions consult this instead of re-listing the
 *  names. (Runtime *dispatch implementations* — e.g. the interpreter's
 *  `case "takeWhile" => …` arms — are out of scope: they hold per-method logic,
 *  not a classification.)
 *
 *  Categories are deliberately separate because backends use them for different
 *  decisions; do not collapse them into one set. */
object CollectionMethods:

  /** Single-element-parameter HOFs whose closure parameter is the receiver's
   *  element type (`xs.map(x => …)`). Used by JS numeric closure-param typing
   *  (`JsGen.genClosureWithParamType`). */
  val elementHofs: Set[String] = Set(
    "map", "filter", "filterNot", "foreach", "forall", "exists", "find",
    "count", "takeWhile", "dropWhile",
  )

  /** Unary list operations that return a list of the **same element type**
   *  (`List[T] => List[T]`). Used by JS element-type inference
   *  (`numericElemOf`) to see through a chain of these. */
  val typePreservingListOps: Set[String] = Set(
    "filter", "filterNot", "take", "drop", "takeWhile", "dropWhile",
    "reverse", "sorted", "distinct", "tail", "init",
  )

  /** True for an element HOF (see [[elementHofs]]). */
  def isElementHof(name: String): Boolean = elementHofs.contains(name)

  /** True for a type-preserving unary list op (see [[typePreservingListOps]]). */
  def isTypePreservingListOp(name: String): Boolean = typePreservingListOps.contains(name)
