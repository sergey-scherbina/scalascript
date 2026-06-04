package scalascript.compiler.plugin.uuid

import scalascript.backend.spi.*
import scalascript.interpreter.{Value, InterpretError}
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

import java.security.SecureRandom
import java.util.UUID

object UuidIntrinsics:

  private val rng = new SecureRandom()

  // ── UUID v7 bit layout (RFC 9562 §5.7) ──────────────────────────────────
  // Bits  0–47: unix_ts_ms (48-bit big-endian millisecond timestamp)
  // Bits 48–51: ver = 0b0111 (7)
  // Bits 52–63: rand_a (12-bit CSPRNG)
  // Bits 64–65: var = 0b10 (RFC 4122 variant)
  // Bits 66–127: rand_b (62-bit CSPRNG)
  private def generateV7(): String =
    val tsMs   = System.currentTimeMillis()
    val randA  = rng.nextInt(0x1000)                // 12 bits
    val randB1 = rng.nextInt(0x4000) | 0x8000       // 14 bits + variant 0b10
    val randB2 = rng.nextInt(0x10000)               // 16 bits
    val randB3 = rng.nextInt(0x10000)               // 16 bits
    val randB4 = rng.nextInt(0x10000)               // 16 bits
    val tHi    = (tsMs >>> 16) & 0xFFFFFFFFL
    val tLo    = tsMs & 0xFFFFL
    f"${tHi}%08x-${tLo}%04x-7${randA}%03x-${randB1}%04x-${randB2}%04x${randB3}%04x${randB4}%04x"

  private val uuidRegex =
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$".r

  private def isValidUuid(s: String): Boolean =
    uuidRegex.matches(s.toLowerCase)

  // ── Shared generator that respects the withFixedUuid override ───────────
  private def generateV4(ctx: NativeContext): String =
    ctx.featureLocalGet(NativeContextFeatureKeys.UuidFixed) match
      case Some(fixed: String) => fixed
      case _                   => UUID.randomUUID().toString

  private def generateV7WithCtx(ctx: NativeContext): String =
    ctx.featureLocalGet(NativeContextFeatureKeys.UuidFixed) match
      case Some(fixed: String) => fixed
      case _                   => generateV7()

  private def nativeCtx(f: (NativeContext, List[Any]) => Value): NativeImpl =
    NativeImpl { (ctx, args) => f(ctx, args) }

  private def native(f: List[Any] => Value): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // ── effectful generators (check withFixedUuid override) ─────────────

    QualifiedName("uuidV4") -> nativeCtx { (ctx, _) =>
      Value.StringV(generateV4(ctx))
    },

    QualifiedName("uuidV7") -> nativeCtx { (ctx, _) =>
      Value.StringV(generateV7WithCtx(ctx))
    },

    // ── raw generators (also respect override for test predictability) ──

    QualifiedName("rawUuidV4") -> nativeCtx { (ctx, _) =>
      Value.StringV(generateV4(ctx))
    },

    QualifiedName("rawUuidV7") -> nativeCtx { (ctx, _) =>
      Value.StringV(generateV7WithCtx(ctx))
    },

    // ── parsing / validation ─────────────────────────────────────────────

    QualifiedName("uuidFromString") -> native {
      case List(s: String) =>
        if isValidUuid(s) then Value.OptionV(Value.StringV(s.toLowerCase))
        else Value.NoneV
      case _ => Value.NoneV
    },

    QualifiedName("uuidUnsafeFromString") -> native {
      case List(s: String) =>
        if isValidUuid(s) then Value.StringV(s.toLowerCase)
        else throw InterpretError(s"uuidUnsafeFromString: not a valid UUID: $s")
      case _ => throw InterpretError("uuidUnsafeFromString(s: String)")
    },

    QualifiedName("uuidIsValid") -> native {
      case List(s: String) => Value.boolV(isValidUuid(s))
      case _               => Value.boolV(false)
    },

  )
