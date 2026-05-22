package scalascript.interpreter

import Computation.{Pure, Perform}

private[interpreter] object StdEffectsRuntime:

  def install(interp: Interpreter): Unit =
    // Logger: info / warn / error / debug — four log levels.
    interp.globals("Logger") = Value.InstanceV("Logger", Map(
      "info"  -> Value.NativeFnV("Logger.info",  args => Perform("Logger", "info",  args)),
      "warn"  -> Value.NativeFnV("Logger.warn",  args => Perform("Logger", "warn",  args)),
      "error" -> Value.NativeFnV("Logger.error", args => Perform("Logger", "error", args)),
      "debug" -> Value.NativeFnV("Logger.debug", args => Perform("Logger", "debug", args)),
    ))

    // Random: nextInt(n) / nextDouble() / uuid() / pick(xs).
    interp.globals("Random") = Value.InstanceV("Random", Map(
      "nextInt"    -> Value.NativeFnV("Random.nextInt",
        args => Perform("Random", "nextInt",    args)),
      "nextDouble" -> Value.NativeFnV("Random.nextDouble",
        args => Perform("Random", "nextDouble", args)),
      "uuid"       -> Value.NativeFnV("Random.uuid",
        args => Perform("Random", "uuid",       args)),
      "pick"       -> Value.NativeFnV("Random.pick",
        args => Perform("Random", "pick",       args)),
    ))

    // Clock: now() / nowIso() / sleep(ms).
    interp.globals("Clock") = Value.InstanceV("Clock", Map(
      "now"    -> Value.NativeFnV("Clock.now",
        args => Perform("Clock", "now",    args)),
      "nowIso" -> Value.NativeFnV("Clock.nowIso",
        args => Perform("Clock", "nowIso", args)),
      "sleep"  -> Value.NativeFnV("Clock.sleep",
        args => Perform("Clock", "sleep",  args)),
    ))

    // Env: get(key) / set(key, v) / required(key).
    interp.globals("Env") = Value.InstanceV("Env", Map(
      "get"      -> Value.NativeFnV("Env.get",
        args => Perform("Env", "get",      args)),
      "set"      -> Value.NativeFnV("Env.set",
        args => Perform("Env", "set",      args)),
      "required" -> Value.NativeFnV("Env.required",
        args => Perform("Env", "required", args)),
    ))

    // Http: get(url) / post(url, body) / request(method, url, headers, body).
    interp.globals("Http") = Value.InstanceV("Http", Map(
      "get"     -> Value.NativeFnV("Http.get",
        args => Perform("Http", "get",     args)),
      "post"    -> Value.NativeFnV("Http.post",
        args => Perform("Http", "post",    args)),
      "request" -> Value.NativeFnV("Http.request",
        args => Perform("Http", "request", args)),
    ))

    // Retry: attempt(n, delayMs)(thunk) — retry thunk up to n times on exception.
    interp.globals("Retry") = Value.InstanceV("Retry", Map(
      "attempt" -> Value.NativeFnV("Retry.attempt", args => args match
        case List(Value.IntV(n), Value.IntV(delayMs)) =>
          Pure(Value.NativeFnV("Retry.attempt.thunk", thunkArgs => thunkArgs match
            case List(thunk) => Perform("Retry", "attempt", List(Value.IntV(n), Value.IntV(delayMs), thunk))
            case _           => throw InterpretError("Retry.attempt(n, delayMs)(thunk: () => Any)")
          ))
        case _ => throw InterpretError("Retry.attempt(n: Int, delayMs: Long)(thunk: () => Any)")
      ),
    ))

    // Cache: memoize(key, ttlSeconds)(thunk) — process-local TTL memoization.
    interp.globals("Cache") = Value.InstanceV("Cache", Map(
      "memoize" -> Value.NativeFnV("Cache.memoize", args => args match
        case List(Value.StringV(key), Value.IntV(ttlSeconds)) =>
          Pure(Value.NativeFnV("Cache.memoize.thunk", thunkArgs => thunkArgs match
            case List(thunk) => Perform("Cache", "memoize", List(Value.StringV(key), Value.IntV(ttlSeconds), thunk))
            case _           => throw InterpretError("Cache.memoize(key, ttlSeconds)(thunk: () => Any)")
          ))
        case _ => throw InterpretError("Cache.memoize(key: String, ttlSeconds: Long)(thunk: () => Any)")
      ),
    ))

    // State[S]: get / set(s) — Free-Monad effect.
    interp.globals("State") = Value.InstanceV("State", Map(
      "get"    -> Value.NativeFnV("State.get",
        args => Perform("State", "get", args)),
      "set"    -> Value.NativeFnV("State.set",
        args => Perform("State", "set", args)),
      "modify" -> Value.NativeFnV("State.modify",
        args => Perform("State", "modify", args)),
    ))

    // Tx: atomic { body } — signals transactional scope; default is no-op.
    interp.globals("Tx") = Value.InstanceV("Tx", Map(
      "atomic" -> Value.NativeFnV("Tx.atomic",
        args => args match
          case List(v) => Pure(v)
          case _       => throw InterpretError("Tx.atomic { body }")
      ),
    ))

    // Auth: currentUser / require — thread-local current user.
    interp.globals("Auth") = Value.InstanceV("Auth", Map(
      "currentUser" -> Value.NativeFnV("Auth.currentUser",
        _ => Pure(interp._authUser.get().fold[Value](Value.OptionV(None))(u => Value.OptionV(Some(u))))),
      "require"     -> Value.NativeFnV("Auth.require",
        _ => interp._authUser.get() match
          case Some(u) => Pure(u)
          case None    => throw RuntimeException("Auth.require: no authenticated user in context")
      ),
    ))
