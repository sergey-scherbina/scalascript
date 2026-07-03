package scalascript.plugin.api

import org.scalatest.funsuite.AnyFunSuite
import java.lang.reflect.Modifier

/** GOLDEN SIGNATURE LOCK for the stable plugin API (`scalascript-plugin-api`).
 *
 *  Reflects the binary (erased) signatures of the stable surface — the `PluginValue` constructors /
 *  extractors / extension methods, `PluginError`, `PluginComputation`, `JsonCodec`, `PluginNative`,
 *  the capability traits, and `PluginApiVersion` — and asserts they match a frozen golden snapshot.
 *
 *  ANY change to the snapshot (a removed/renamed method, a changed parameter or return type) fails
 *  this test.  That is intentional: the stable surface is the plugin ABI.  When a change IS intended:
 *    1. classify it (PATCH / MINOR additive / MAJOR breaking) and bump `PluginApiVersion.Current`;
 *    2. update the `golden` value below to the printed "actual" signature.
 *  This makes every surface change a deliberate, reviewed, version-bumped act. */
class PluginApiSignatureTest extends AnyFunSuite:

  private val P   = "scalascript.plugin.api."
  // top-level opaque types + their companions (PluginValue/PluginError/PluginComputation/PluginContext)
  // compile into a file-level `PluginApi$package$…` carrier; normal objects/traits do not.
  private val pkg = P + "PluginApi$package$"

  private val apiTypes = List(
    pkg + "PluginValue$",
    pkg + "PluginError$",
    pkg + "PluginComputation$",
    pkg + "PluginContext$",
    P + "JsonCodec$",
    P + "PluginNative$",
    P + "PluginApiVersion$",
    P + "NativeContextCap",
    P + "StorageCap",
    P + "HttpCap",
    P + "WsCap",
    P + "DbCap",
    P + "ValidateCap",
    P + "MountCap",
    P + "RemoteCap",
  ) ++ List(
    // the extractor objects (removing one is a breaking change)
    "Str", "Num", "Dbl", "Bool", "Chr", "Lst", "Tpl", "Inst", "InstAny",
    "Opt", "Big", "Dec", "MapVal", "Foreign", "NativeFn", "Fn",
  ).map(n => pkg + s"PluginValue$$$n$$")

  /** Clean short type name derived from the (possibly mangled) class name. */
  private def shortName(cn: String): String =
    cn.stripPrefix(P).stripPrefix("PluginApi$package$").stripSuffix("$")

  /** A stable, sorted list of `Type.method(paramTypes):returnType` for the public surface. */
  private def signature: String =
    val lines =
      for
        cn <- apiTypes
        cls = Class.forName(cn)
        short = shortName(cn)
        m <- cls.getDeclaredMethods.toList
        if Modifier.isPublic(m.getModifiers) && !m.isSynthetic && !m.isBridge
        // drop compiler-generated accessors that are not part of the authored surface
        if !Set("equals", "hashCode", "toString", "productPrefix", "productArity",
                "productElement", "productIterator", "canEqual", "writeReplace").contains(m.getName)
      yield s"$short.${m.getName}(${m.getParameterTypes.map(_.getSimpleName).mkString(",")}):${m.getReturnType.getSimpleName}"
    lines.sorted.distinct.mkString("\n")

  test("stable plugin API binary surface matches the golden snapshot"):
    val actual = signature
    // Compare as SETS so the golden's stored order is irrelevant (robust to sort differences).
    val a = actual.linesIterator.filter(_.nonEmpty).toSet
    val g = golden.linesIterator.filter(_.nonEmpty).toSet
    if a != g then
      val added   = (a -- g).toList.sorted
      val removed = (g -- a).toList.sorted
      fail(
        s"""The stable plugin API surface changed (PluginApiVersion.Current = ${PluginApiVersion.Current}).
           |If this is intentional, bump PluginApiVersion (PATCH/MINOR-additive/MAJOR-breaking) and replace
           |the `golden` value in this test with the ACTUAL signature below.
           |
           |  ADDED (${added.size}):
           |${added.map("    + " + _).mkString("\n")}
           |
           |  REMOVED (${removed.size}):
           |${removed.map("    - " + _).mkString("\n")}
           |
           |  ───── ACTUAL (paste as the new golden) ─────
           |$actual
           |""".stripMargin)

  /** Frozen snapshot of the stable surface.  Update ONLY with a PluginApiVersion bump. */
  private val golden: String =
    """DbCap.dbConnect(String):Connection
DbCap.dbConnect$(DbCap,String):Connection
HttpCap.configureCors(List,List,List):void
HttpCap.configureCors$(HttpCap,List,List,List):void
HttpCap.enableGzip():void
HttpCap.enableGzip$(HttpCap):void
HttpCap.httpBaseUrl():String
HttpCap.httpBaseUrl$(HttpCap):String
HttpCap.httpMaxRetries():int
HttpCap.httpMaxRetries$(HttpCap):int
HttpCap.httpRetryDelayMs():long
HttpCap.httpRetryDelayMs$(HttpCap):long
HttpCap.httpTimeoutMs():long
HttpCap.httpTimeoutMs$(HttpCap):long
HttpCap.registerHealthDefaults():void
HttpCap.registerHealthDefaults$(HttpCap):void
HttpCap.registerMiddleware(Object):void
HttpCap.registerMiddleware$(HttpCap,Object):void
HttpCap.registerOpenApiDefaults():void
HttpCap.registerOpenApiDefaults$(HttpCap):void
HttpCap.registerRoute(String,String,Object):void
HttpCap.registerRoute$(HttpCap,String,String,Object):void
HttpCap.registerRouteWithOpenApi(String,String,Object,OpenApiMetadata):void
HttpCap.registerRouteWithOpenApi$(HttpCap,String,String,Object,OpenApiMetadata):void
HttpCap.setHttpRetry(int,long):void
HttpCap.setHttpRetry$(HttpCap,int,long):void
HttpCap.setHttpTimeout(long):void
HttpCap.setHttpTimeout$(HttpCap,long):void
HttpCap.setMaxBodySize(long):void
HttpCap.setMaxBodySize$(HttpCap,long):void
HttpCap.setSpoolThreshold(long):void
HttpCap.setSpoolThreshold$(HttpCap,long):void
HttpCap.setUploadDir(String):void
HttpCap.setUploadDir$(HttpCap,String):void
HttpCap.startServer(int,String):void
HttpCap.startServer$(HttpCap,int,String):void
HttpCap.startServerAsync(int,String):void
HttpCap.startServerAsync$(HttpCap,int,String):void
HttpCap.startTlsServer(int,String,String,String):void
HttpCap.startTlsServer$(HttpCap,int,String,String,String):void
HttpCap.startTlsServerAsync(int,String,String,String):void
HttpCap.startTlsServerAsync$(HttpCap,int,String,String,String):void
HttpCap.stopServer():void
HttpCap.stopServer$(HttpCap):void
JsonCodec.arr(Seq):Arr
JsonCodec.False():Bool
JsonCodec.Null():Null$
JsonCodec.num(double):Num
JsonCodec.obj(Seq):Obj
JsonCodec.parseString(String):Either
JsonCodec.str(String):Str
JsonCodec.stringify(Value):String
JsonCodec.True():Bool
MountCap.baseDirPath():Option
MountCap.baseDirPath$(MountCap):Option
MountCap.evalFileGetNamedResult(String,String):Object
MountCap.evalFileGetNamedResult$(MountCap,String,String):Object
MountCap.evalFileGetResult(String):Object
MountCap.evalFileGetResult$(MountCap,String):Object
MountCap.invokeCallback(Object,List):Object
MountCap.invokeCallback$(MountCap,Object,List):Object
MountCap.invokeCallbackAsync(Object,List):Object
MountCap.invokeCallbackAsync$(MountCap,Object,List):Object
MountCap.registerMountedRoute(String,String,Object,Option,Map):void
MountCap.registerMountedRoute$(MountCap,String,String,Object,Option,Map):void
MountCap.resolveGlobal(String):Option
MountCap.resolveGlobal$(MountCap,String):Option
NativeContextCap.abortOpenApiDryRun():Nothing$
NativeContextCap.abortOpenApiDryRun$(NativeContextCap):Nothing$
NativeContextCap.err():PrintStream
NativeContextCap.err$(NativeContextCap):PrintStream
NativeContextCap.headless():boolean
NativeContextCap.headless$(NativeContextCap):boolean
NativeContextCap.nativeContext():NativeContext
NativeContextCap.openApiDryRun():boolean
NativeContextCap.openApiDryRun$(NativeContextCap):boolean
NativeContextCap.out():PrintStream
NativeContextCap.out$(NativeContextCap):PrintStream
PluginApiVersion.Current():String
PluginApiVersion.isCompatible(String):boolean
PluginComputation.pure(Object):Object
PluginComputation.unwrap(Object):Object
PluginContext.fromNative(NativeContext):DbCap
PluginError.apply(String):Throwable
PluginError.message(Throwable):String
PluginError.raise(String):Nothing$
PluginError.unwrap(Throwable):Throwable
PluginError.wrap(Throwable):Throwable
PluginNative.eval(Function1,Function2):NativeImpl
PluginNative.eval(Function2):NativeImpl
PluginNative.evalLegacy(Function2):NativeImpl
PluginValue.asBigInt(Object):Option
PluginValue.asBool(Object):Option
PluginValue.asChar(Object):Option
PluginValue.asDecimal(Object):Option
PluginValue.asDouble(Object):Option
PluginValue.asInstance(Object):Option
PluginValue.asInt(Object):Option
PluginValue.asList(Object):Option
PluginValue.asMap(Object):Option
PluginValue.asOption(Object):Option
PluginValue.asString(Object):Option
PluginValue.asTuple(Object):Option
PluginValue.bigint(BigInt):Object
PluginValue.bool(boolean):Object
PluginValue.callFn(Object,List):Object
PluginValue.char(char):Object
PluginValue.decimal(BigDecimal):Object
PluginValue.double(double):Object
PluginValue.field(Object,String):Option
PluginValue.fields(Object):Map
PluginValue.foreign(String,Object):Object
PluginValue.fromHostAny(Object):Object
PluginValue.funArity(Object):Option
PluginValue.instance(String,Map):Object
PluginValue.int(long):Object
PluginValue.isCallable(Object):boolean
PluginValue.isRuntimeValue(Object):boolean
PluginValue.isUnitOrNull(Object):boolean
PluginValue.jsonEncode(Object):String
PluginValue.jsonFacade(Object):Object
PluginValue.list(List):Object
PluginValue.lookupKey(Object,Object):Option
PluginValue.map(List):Object
PluginValue.mapOf(Map):Object
PluginValue.nativeFn(String,Function1):Object
PluginValue.none():Object
PluginValue.nullV():Object
PluginValue.option(Option):Object
PluginValue.orderedInstance(String,Seq):Object
PluginValue.parseJson(String):Either
PluginValue.show(Object):String
PluginValue.showAny(Object):String
PluginValue.some(Object):Object
PluginValue.string(String):Object
PluginValue.tuple(List):Object
PluginValue.typeNameOf(Object):Option
PluginValue.unit():Object
PluginValue.unwrap(Object):Object
PluginValue.wrap(Object):Object
PluginValue.wrapTypedHandler(Object,Function2,Map,String,boolean):Object
PluginValue$Big.unapply(Object):Option
PluginValue$Bool.unapply(Object):Option
PluginValue$Chr.unapply(Object):Option
PluginValue$Dbl.unapply(Object):Option
PluginValue$Dec.unapply(Object):Option
PluginValue$Fn.unapply(Object):Option
PluginValue$Foreign.unapply(Object):Option
PluginValue$Inst.unapply(Object):Option
PluginValue$InstAny.unapply(Object):Option
PluginValue$Lst.unapply(Object):Option
PluginValue$MapVal.unapply(Object):Option
PluginValue$NativeFn.unapply(Object):Option
PluginValue$Num.unapply(Object):Option
PluginValue$Opt.unapply(Object):Option
PluginValue$Str.unapply(Object):Option
PluginValue$Tpl.unapply(Object):Option
RemoteCap.invokeRemoteHandler(String,Object):Either
RemoteCap.invokeRemoteHandler$(RemoteCap,String,Object):Either
RemoteCap.remoteHandlers():List
RemoteCap.remoteHandlers$(RemoteCap):List
StorageCap.featureGet(String):Option
StorageCap.featureGet$(StorageCap,String):Option
StorageCap.featureLocalGet(String):Option
StorageCap.featureLocalGet$(StorageCap,String):Option
StorageCap.featureLocalRemove(String):Option
StorageCap.featureLocalRemove$(StorageCap,String):Option
StorageCap.featureLocalSet(String,Object):void
StorageCap.featureLocalSet$(StorageCap,String,Object):void
StorageCap.featureRemove(String):Option
StorageCap.featureRemove$(StorageCap,String):Option
StorageCap.featureSet(String,Object):void
StorageCap.featureSet$(StorageCap,String,Object):void
StorageCap.storageFieldName(String,String):String
StorageCap.storageFieldName$(StorageCap,String,String):String
ValidateCap.validationRecord(String,String,Object):Object
ValidateCap.validationRecord$(ValidateCap,String,String,Object):Object
WsCap.registerWsAuthRoute(String,Object,Object):void
WsCap.registerWsAuthRoute$(WsCap,String,Object,Object):void
WsCap.registerWsRoute(String,List,List,int,int,Object):void
WsCap.registerWsRoute$(WsCap,String,List,List,int,int,Object):void
WsCap.setMaxWsConnections(int):void
WsCap.setMaxWsConnections$(WsCap,int):void
WsCap.wsConnectSync(String,Map,List,Object):void
WsCap.wsConnectSync$(WsCap,String,Map,List,Object):void"""
