package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite

class TypedJsonCodecRuntimeTest extends AnyFunSuite:

  test("typed JSON facade exposes stable JS and JVM entrypoints"):
    assert(TypedJsonCodecRuntime.encodeFunctionName == "_ssc_typed_json_encode")
    assert(TypedJsonCodecRuntime.decodeResponseFunctionName == "_ssc_typed_json_decode_response")
    assert(TypedJsonCodecRuntime.jsFacade.contains("function _ssc_typed_json_encode(value)"))
    assert(TypedJsonCodecRuntime.jsFacade.contains("function _ssc_typed_json_decode_response(text, contentType)"))
    assert(TypedJsonCodecRuntime.jvmFacade.contains("private def _ssc_typed_json_encode(value: Any): String"))
    assert(TypedJsonCodecRuntime.jvmFacade.contains("private inline def _ssc_typed_json_decode_response[T](response: scalascript.backend.spi.BackendResponse): T"))
