package scalascript.compiler.plugin.uuid

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginError, PluginNative, PluginValue}

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

  // ── Monotonic v7 (RFC 9562 §6.2 Method 1 — fixed-length dedicated counter) ──
  // `rand_a` (bits 52–63, 12 bits) is used as a per-millisecond counter. Because
  // rand_a is MORE significant than rand_b, a larger counter always yields a
  // larger UUID regardless of rand_b — so incrementing it within the same
  // millisecond produces STRICTLY increasing UUIDs, and across millisecond
  // boundaries the 48-bit timestamp dominates. State is guarded by `monoLock`.
  private val monoLock      = new Object
  private var monoLastMs    = -1L
  private var monoCounter   = 0   // current 12-bit rand_a value

  private def generateV7Monotonic(): String =
    val (tsMs, randA) = monoLock.synchronized {
      var ms = System.currentTimeMillis()
      // Never move the timestamp backwards (monotonic across a clock rewind).
      if ms < monoLastMs then ms = monoLastMs
      if ms > monoLastMs then
        // New millisecond: seed the counter in the lower half (11 bits) so there
        // is headroom to increment without overflowing the 12-bit field.
        monoLastMs  = ms
        monoCounter = rng.nextInt(0x800)
      else
        // Same millisecond: advance the counter. On overflow, spin to the next ms
        // (≥4096 ids in one ms is ~4M/s — rare) and reseed.
        monoCounter += 1
        if monoCounter > 0xFFF then
          var next = System.currentTimeMillis()
          while next <= monoLastMs do next = System.currentTimeMillis()
          monoLastMs  = next
          monoCounter = rng.nextInt(0x800)
          ms = next
      (monoLastMs, monoCounter)
    }
    val randB1 = rng.nextInt(0x4000) | 0x8000       // 14 bits + variant 0b10
    val randB2 = rng.nextInt(0x10000)
    val randB3 = rng.nextInt(0x10000)
    val randB4 = rng.nextInt(0x10000)
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

  private def generateV7MonotonicWithCtx(ctx: NativeContext): String =
    ctx.featureLocalGet(NativeContextFeatureKeys.UuidFixed) match
      case Some(fixed: String) => fixed
      case _                   => generateV7Monotonic()

  private def nativeCtx(f: (NativeContext, List[Any]) => PluginValue): NativeImpl =
    NativeImpl { (ctx, args) => f(ctx, args).unwrap }

  private def native(f: List[Any] => PluginValue): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(f(args.map(_.unwrap)))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // ── effectful generators (check withFixedUuid override) ─────────────

    QualifiedName("uuidV4") -> nativeCtx { (ctx, _) =>
      PluginValue.string(generateV4(ctx))
    },

    QualifiedName("uuidV7") -> nativeCtx { (ctx, _) =>
      PluginValue.string(generateV7WithCtx(ctx))
    },

    // Monotonic v7: strictly increasing across calls within the same millisecond
    // (rand_a counter). Explicit opt-in — `Uuid.v7Monotonic(): Uuid ! SideEffect`.
    QualifiedName("uuidV7Monotonic") -> nativeCtx { (ctx, _) =>
      PluginValue.string(generateV7MonotonicWithCtx(ctx))
    },

    // ── raw generators (also respect override for test predictability) ──

    QualifiedName("rawUuidV4") -> nativeCtx { (ctx, _) =>
      PluginValue.string(generateV4(ctx))
    },

    QualifiedName("rawUuidV7") -> nativeCtx { (ctx, _) =>
      PluginValue.string(generateV7WithCtx(ctx))
    },

    // ── parsing / validation ─────────────────────────────────────────────

    QualifiedName("uuidFromString") -> native {
      case List(s: String) =>
        if isValidUuid(s) then PluginValue.some(PluginValue.string(s.toLowerCase))
        else PluginValue.none
      case _ => PluginValue.none
    },

    QualifiedName("uuidUnsafeFromString") -> native {
      case List(s: String) =>
        if isValidUuid(s) then PluginValue.string(s.toLowerCase)
        else PluginError.raise(s"uuidUnsafeFromString: not a valid UUID: $s")
      case _ => PluginError.raise("uuidUnsafeFromString(s: String)")
    },

    QualifiedName("uuidIsValid") -> native {
      case List(s: String) => PluginValue.bool(isValidUuid(s))
      case _               => PluginValue.bool(false)
    },

  )
