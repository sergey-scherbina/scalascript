package ssc.bridge

import scala.meta.{Stat, Tree}
import scalascript.ast.{Module, Content, ScalaNode, Section}
import ssc.Program

/** Converts a v1 AST.Module (output of v1 Parser) to a v2 Core IR Program.
 *
 *  Strategy: walk all sections → code blocks → extract scalameta stats → FrontendBridge.
 *  Each section is flattened left-to-right, maintaining declaration order for de Bruijn. */
object ModuleBridge:

  def convert(module: Module): Program =
    val stats = allStats(module.sections)
    FrontendBridge.convertStats(stats)

  private def allStats(sections: List[Section]): List[Stat] =
    sections.flatMap { s =>
      val here = s.content.flatMap {
        case cb: Content.CodeBlock => sectionStats(cb)
        case _                     => Nil
      }
      here ++ allStats(s.subsections)
    }

  private def sectionStats(cb: Content.CodeBlock): List[Stat] =
    cb.tree match
      case Some(node) =>
        ScalaNode.fold(node) {
          case scala.meta.Source(ss)     => ss.toList
          case scala.meta.Term.Block(ss) => ss.toList
          case s: Stat                   => List(s)
          case _                         => Nil
        }
      case None =>
        // Tree failed to parse — fall back to re-parsing source
        if cb.source.trim.nonEmpty then
          try
            FrontendBridge.parseStats(cb.source)
          catch case _: Exception => Nil
        else Nil
