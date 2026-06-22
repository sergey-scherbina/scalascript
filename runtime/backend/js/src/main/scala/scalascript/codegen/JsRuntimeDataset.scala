package scalascript.codegen

/** Dataset[T] JS runtime preamble — emitted when dataset usage is detected.
 *
 *  `_Dataset` is a lazy pipeline: a source thunk `() => Array` plus a
 *  composed transformation `Array => Array`.  Nothing executes until a
 *  terminal operation is called.  `runParallel()` falls back to sequential
 *  on JS (Node parallel via worker_threads deferred to v1.3 Node). */
val JsRuntimeDataset: String = JsRuntimeResource.load("dataset.mjs")
