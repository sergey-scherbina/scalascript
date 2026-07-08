# V2 Case-Class Instance Methods

## Overview

The default v2 runner currently registers case-class fields but drops methods
declared inside `case class ...:` templates. Calling such a method falls through
runtime field dispatch and returns `Stub("Tag.method")`. This slice lowers
case-class instance methods into v2 callable method dispatch so user-facing std
APIs such as `Cluster.close()` execute on the production v2 lane.

## Interface

- `.ssc` source syntax is unchanged:
  `case class C(a: A): def m(...): R = ...`.
- Calls keep the existing surface: `value.m(args...)`.
- Field access remains unchanged and continues through the registered case-class
  field table.
- `Cluster.close()` remains the public MapReduce shutdown API. The p3 examples
  may keep explicit shutdown for clarity, but the method itself must work under
  default v2.

## Behavior

- [ ] A case-class method with no parameters can read constructor fields and
      return a value under `bin/ssc run` default v2.
- [ ] A case-class method with ordinary parameters can read both constructor
      fields and method parameters.
- [ ] Case-class method dispatch is tag-aware, so same-named methods on
      different case classes dispatch by receiver runtime tag.
- [ ] Existing field access, `.copy(...)`, object methods, and extension methods
      keep their current behavior.
- [ ] `Cluster.close()` executes without returning or printing
      `Stub("Cluster.close")`.

## Out of Scope

- Inheritance, overrides, abstract class methods, or trait method resolution.
- Method default parameters and varargs for case-class instance methods.
- Rewriting existing std APIs to avoid case-class methods.
- Changing `Cluster.connect`, `connectNode`, or remote actor routing semantics.

## Design

Reuse the existing v2 extension-method machinery instead of adding a second
runtime method table. During `FrontendBridge.registerTypes`, record method names
declared in case-class templates as extension-like method names so call sites can
route through `__methodOrExt__` when a receiver has no field with that method
name.

During top-level conversion, each case-class template method emits an
extension-like implementation:

1. The first generated parameter is the receiver value.
2. Ordinary method parameters follow in source order.
3. Constructor fields are bound from the receiver with `fieldAt(receiver, idx)`
   before compiling the method body, preserving wildcard-free field positions.
4. The implementation is accumulated with `typeHead = <case-class-name>` so the
   existing `flushExtensions` tag-dispatcher can select the correct same-named
   method by receiver tag.

Runtime behavior then remains the current precedence:
field access and method-object members win when present; otherwise
`__methodOrExt__` falls back to the generated extension implementation.

## Decisions

- **Reuse extension dispatch** — chosen because it already provides runtime
  tag tests for same-named methods and integrates with call-site lowering.
  Rejected: adding a new runtime case-class method table, which would duplicate
  dispatch rules and increase method precedence risk.
- **Bind fields in bridge-generated lambdas** — chosen because case-class method
  bodies reference constructor params by name. Rejected: teaching runtime
  `__method__` to evaluate source-level bodies, because source lowering belongs
  in `FrontendBridge`.
- **Keep defaults/varargs out of this slice** — chosen to keep the production
  fix small and testable. Rejected: broad Scala method parity in one change.

## Results

Fill after verification with exact commands and outputs.
