const UntransformedCode = "JS_DIRECT_UNTRANSFORMED"

export class DirectMarkerContractError extends Error {
  constructor(marker) {
    super(
      `@scalascript/control-direct ${marker} marker reached runtime; ` +
      "compile this source with the control-direct transform"
    )
    this.name = "DirectMarkerContractError"
    this.code = UntransformedCode
    this.marker = marker
    Object.freeze(this)
  }
}

function untransformed(marker) {
  throw new DirectMarkerContractError(marker)
}

export const direct = Object.freeze({
  reset() {
    return untransformed("reset")
  },
  shift() {
    return untransformed("shift")
  }
})
