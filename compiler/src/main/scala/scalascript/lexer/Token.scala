package scalascript.lexer

import scalascript.ast.{Position, Span}

case class Token(kind: TokenKind, text: String, span: Span)

enum TokenKind:
  // Document structure
  case FrontMatterStart    // ---
  case FrontMatterEnd      // ---
  case FrontMatterContent  // YAML content

  // Markdown
  case Heading(level: Int) // #, ##, ###, etc.
  case HeadingText         // Text after #
  case CodeFenceStart      // ``` or ~~~
  case CodeFenceEnd        // ``` or ~~~
  case CodeLang            // Language tag after ```
  case CodeContent         // Raw code inside fence
  case LinkStart           // [
  case LinkEnd             // ]
  case LinkTargetStart     // (
  case LinkTargetEnd       // )
  case Text                // Plain text
  case InlineCode          // `code`
  case InterpolationStart  // ${
  case InterpolationEnd    // }
  case ListMarker          // -, *, +
  case OrderedListMarker   // 1., 2., etc.
  case Newline
  case Indent
  case Dedent
  case HtmlCommentStart    // <!--
  case HtmlCommentEnd      // -->
  case HtmlCommentContent

  // Scala tokens (inside code blocks)
  case Identifier
  case IntLiteral
  case LongLiteral
  case DoubleLiteral
  case StringLiteral
  case CharLiteral
  case InterpolatedStringStart  // s", f", raw"
  case InterpolatedStringPart
  case InterpolatedStringEnd

  // Keywords
  case KwAbstract
  case KwCase
  case KwCatch
  case KwClass
  case KwDef
  case KwDo
  case KwElse
  case KwEnum
  case KwExtends
  case KwFalse
  case KwFinal
  case KwFinally
  case KwFor
  case KwGiven
  case KwIf
  case KwImplicit
  case KwImport
  case KwLazy
  case KwMatch
  case KwNew
  case KwNull
  case KwObject
  case KwOverride
  case KwPackage
  case KwPrivate
  case KwProtected
  case KwReturn
  case KwSealed
  case KwSuper
  case KwThen
  case KwThis
  case KwThrow
  case KwTrait
  case KwTrue
  case KwTry
  case KwType
  case KwVal
  case KwVar
  case KwWhile
  case KwWith
  case KwYield
  case KwExport

  // Operators and punctuation
  case Plus          // +
  case Minus         // -
  case Star          // *
  case Slash         // /
  case Percent       // %
  case Ampersand     // &
  case Pipe          // |
  case Caret         // ^
  case Tilde         // ~
  case Exclaim       // !
  case Question      // ?
  case Lt            // <
  case Gt            // >
  case Eq            // =
  case Colon         // :
  case Semicolon     // ;
  case Comma         // ,
  case Dot           // .
  case At            // @
  case Hash          // #
  case Underscore    // _
  case Arrow         // =>
  case LeftArrow     // <-
  case RightArrow    // ->
  case Subtype       // <:
  case Supertype     // >:
  case EqEq          // ==
  case NotEq         // !=
  case LtEq          // <=
  case GtEq          // >=
  case AndAnd        // &&
  case OrOr          // ||
  case PlusEq        // +=
  case MinusEq       // -=
  case StarEq        // *=
  case SlashEq       // /=
  case ColonColon    // ::

  // Brackets
  case LParen        // (
  case RParen        // )
  case LBracket      // [
  case RBracket      // ]
  case LBrace        // {
  case RBrace        // }

  // Special
  case EOF
  case Error

object TokenKind:
  val keywords: Map[String, TokenKind] = Map(
    "abstract" -> KwAbstract,
    "case" -> KwCase,
    "catch" -> KwCatch,
    "class" -> KwClass,
    "def" -> KwDef,
    "do" -> KwDo,
    "else" -> KwElse,
    "enum" -> KwEnum,
    "extends" -> KwExtends,
    "false" -> KwFalse,
    "final" -> KwFinal,
    "finally" -> KwFinally,
    "for" -> KwFor,
    "given" -> KwGiven,
    "if" -> KwIf,
    "implicit" -> KwImplicit,
    "import" -> KwImport,
    "lazy" -> KwLazy,
    "match" -> KwMatch,
    "new" -> KwNew,
    "null" -> KwNull,
    "object" -> KwObject,
    "override" -> KwOverride,
    "package" -> KwPackage,
    "private" -> KwPrivate,
    "protected" -> KwProtected,
    "return" -> KwReturn,
    "sealed" -> KwSealed,
    "super" -> KwSuper,
    "then" -> KwThen,
    "this" -> KwThis,
    "throw" -> KwThrow,
    "trait" -> KwTrait,
    "true" -> KwTrue,
    "try" -> KwTry,
    "type" -> KwType,
    "val" -> KwVal,
    "var" -> KwVar,
    "while" -> KwWhile,
    "with" -> KwWith,
    "yield" -> KwYield,
    "export" -> KwExport
  )
