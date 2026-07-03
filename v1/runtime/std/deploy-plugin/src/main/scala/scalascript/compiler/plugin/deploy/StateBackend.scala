package scalascript.compiler.plugin.deploy

case class StateKey(env: String, target: String, slot: Option[String] = None)

case class StateRecord(
  env:              String,
  target:           String,
  slot:             Option[String],
  revision:         String,
  artifactHash:     String,
  deployedAt:       String,
  deployedBy:       String,
  outputs:          Map[String, String],
)

case class LockHandle(key: StateKey, token: String)

trait StateBackend:
  def read(key: StateKey): Option[StateRecord]
  def write(key: StateKey, record: StateRecord): Unit
  def lock(key: StateKey, ttlSeconds: Int): LockHandle
  def unlock(handle: LockHandle): Unit

/** No-op in-memory state backend (default when no `state:` block). */
object NoopStateBackend extends StateBackend:
  def read(key: StateKey): Option[StateRecord] = None
  def write(key: StateKey, record: StateRecord): Unit = ()
  def lock(key: StateKey, ttlSeconds: Int): LockHandle = LockHandle(key, "noop")
  def unlock(handle: LockHandle): Unit = ()
