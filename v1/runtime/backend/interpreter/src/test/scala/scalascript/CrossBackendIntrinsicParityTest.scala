package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JvmIntrinsics, JsIntrinsics}

/** Build-time cross-backend parity gate (specs/cross-backend-intrinsic-parity.md).
 *
 *  Guards the two hand-maintained CORE codegen intrinsic tables — `JvmIntrinsics`
 *  (`JvmCapabilities.scala`) and `JsIntrinsics` (`JsCapabilities.scala`) — against
 *  undocumented drift: adding a platform intrinsic to one table but forgetting the
 *  peer. This is the concrete surface where "added to JS, forgot JVM" happens for
 *  core intrinsics; previously such drift was caught only post-hoc by the conformance
 *  suite. The interpreter's SPI map is deliberately NOT compared (it is sparse — the
 *  interpreter dispatches most intrinsics via hardcoded `nativeP` natives, a different
 *  mechanism), nor are plugin-overlay / hardcoded-codegen intrinsics (each owns its
 *  own parity). See the spec for the full scope rationale.
 *
 *  The allowlists below are the documented, intentional core-table asymmetries. The
 *  test asserts the actual asymmetry EQUALS the allowlist exactly (a ratchet):
 *   - a NEW undocumented divergence (real drift) fails the test, naming the offenders;
 *   - a FIXED/STALE allowlist entry also fails, prompting allowlist cleanup.
 */
class CrossBackendIntrinsicParityTest extends AnyFunSuite:

  // JVM-only by design: native wallet-token crypto with no browser equivalent.
  private val allowedJvmOnly: Set[String] = Set(
    "ApplePay.decryptToken",
    "ApplePay.validateMerchant",
    "GooglePay.decryptToken"
  )

  // JS-only in the CORE table because JVM provides each via a PLUGIN (not the core
  // val): JS bundles crypto/uuid/graphql/json/auth intrinsics into `JsIntrinsics`, while
  // JVM delegates them to crypto-plugin / uuid-plugin / graphql-plugin / json-plugin /
  // auth-plugin.
  // A registration-location inconsistency, NOT a capability gap (each verified present
  // on JVM via its plugin, 2026-06-15). Harmonising this is a separate BACKLOG follow-up
  // (`intrinsic-registration-harmonise`); until then these are documented exceptions.
  private val allowedJsOnly: Set[String] = Set(
    // → JVM crypto-plugin
    "base64Decode", "base64Encode", "hmacSha256", "sha256",
    // → JVM uuid-plugin
    "rawUuidV4", "rawUuidV7", "uuidFromString", "uuidIsValid",
    "uuidUnsafeFromString", "uuidV4", "uuidV7", "uuidV7Monotonic",
    // → JVM graphql-plugin
    "GraphQL.dataLoader", "GraphQL.entityResolvers", "GraphQL.options",
    "GraphQL.resolvers", "GraphQL.scalar", "GraphQL.schema",
    "graphqlHandler", "graphqlMount", "graphqlQuery", "graphqlSse",
    "graphqlSubgraphMount", "graphqlSubscribe", "serveGraphQL", "serveSubgraph",
    // → JVM json-plugin
    "jsonValue",
    // → JVM auth-plugin
    "webauthnConfigureStore", "webauthnStoreRemove"
  )

  private val jvmKeys: Set[String] = JvmIntrinsics.keySet.map(_.value)
  private val jsKeys:  Set[String] = JsIntrinsics.keySet.map(_.value)

  test("core JVM and JS intrinsic tables stay in parity (only allowlisted exceptions)"):
    val jvmOnly = jvmKeys -- jsKeys
    val jsOnly  = jsKeys  -- jvmKeys

    val driftJvm = (jvmOnly -- allowedJvmOnly).toList.sorted // in JVM core, missing from JS core
    val driftJs  = (jsOnly  -- allowedJsOnly).toList.sorted  // in JS core, missing from JVM core
    val staleJvm = (allowedJvmOnly -- jvmOnly).toList.sorted // allowlisted but no longer JVM-only
    val staleJs  = (allowedJsOnly  -- jsOnly).toList.sorted  // allowlisted but no longer JS-only

    val problems = scala.collection.mutable.ListBuffer.empty[String]
    if driftJvm.nonEmpty then
      problems += s"DRIFT — in JvmIntrinsics but missing from JsIntrinsics: ${driftJvm.mkString(", ")}\n" +
        "  → add the JS impl in JsCapabilities.scala, or add to allowedJvmOnly with a reason."
    if driftJs.nonEmpty then
      problems += s"DRIFT — in JsIntrinsics but missing from JvmIntrinsics: ${driftJs.mkString(", ")}\n" +
        "  → add the JVM impl (core table or a plugin), or add to allowedJsOnly with a reason."
    if staleJvm.nonEmpty then
      problems += s"STALE allowlist — allowedJvmOnly lists ${staleJvm.mkString(", ")} which are no longer JVM-only; remove them."
    if staleJs.nonEmpty then
      problems += s"STALE allowlist — allowedJsOnly lists ${staleJs.mkString(", ")} which are no longer JS-only; remove them."

    assert(
      problems.isEmpty,
      "\nCore intrinsic-table parity broke (see specs/cross-backend-intrinsic-parity.md):\n" +
        problems.mkString("\n")
    )
