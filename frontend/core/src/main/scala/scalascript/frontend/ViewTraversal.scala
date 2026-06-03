package scalascript.frontend

import scala.annotation.nowarn

/** Shared `View` tree traversal for collector passes.
 *
 *  This helper deliberately does not lower or render nodes. It only defines
 *  which child `View`s are reachable from each IR case so backend collectors
 *  can share one exhaustive walk while keeping backend-specific registration
 *  logic local.
 */
@nowarn("cat=deprecation")
object ViewTraversal:

  enum AdaptiveMode:
    /** Visit every adaptive branch plus fallback. Useful for whole-IR analysis. */
    case All
    /** Visit the branch a web renderer will emit: `web` when present, else fallback. */
    case WebRendered
    /** Visit the branch a desktop renderer will emit: `desktop` when present, else fallback. */
    case DesktopRendered
    /** Visit the branch a mobile renderer will emit: `mobile` when present, else fallback. */
    case MobileRendered

  final case class Options(adaptiveMode: AdaptiveMode = AdaptiveMode.All)

  object Options:
    val Default: Options = Options()
    val Web:     Options = Options(adaptiveMode = AdaptiveMode.WebRendered)
    val Desktop: Options = Options(adaptiveMode = AdaptiveMode.DesktopRendered)
    val Mobile:  Options = Options(adaptiveMode = AdaptiveMode.MobileRendered)

  def foreachDepthFirst(root: View[?], options: Options = Options.Default)(visit: View[?] => Unit): Unit =
    def loop(v: View[?]): Unit =
      visit(v)
      children(v, options).foreach(loop)
    loop(root)

  def children(view: View[?], options: Options = Options.Default): Seq[View[?]] = view match
    case View.Column(children, _, _, _) => children
    case View.Row(children, _, _, _)    => children
    case View.Stack(children, _)        => children
    case View.ScrollView(child, _, _)   => Seq(child)

    case View.Button(label, _, _, _) => Seq(label)

    case View.LazyList(items, render, _, _)       => items().map(item => render(item)).toSeq
    case View.LazyGrid(items, render, _, _, _)    => items().map(item => render(item)).toSeq
    case View.TabBar(tabs, _, _)                  => tabs.map(_.content)
    case View.NavigationStack(routes, _, _)       => routes.values.map(fn => fn()).toSeq
    case View.Sheet(content, _)                   => Seq(content)
    case View.Form(child, _, _)                   => Seq(child)
    case View.SafeArea(child, _)                  => Seq(child)
    case View.KeyboardAvoiding(child)             => Seq(child)
    case View.Animated(child, _, _)               => Seq(child)
    case View.Fragment(children)                  => children
    case View.ComponentInstance(component, props) => Seq(component.render(props.asInstanceOf[Nothing]))
    case View.Show(_, whenTrue, whenFalse)        => Seq(whenTrue(), whenFalse())
    case View.ShowSignal(_, whenTrue, whenFalse)  => Seq(whenTrue, whenFalse)
    case View.For(items, render)                  => items().map(item => render(item)).toSeq
    case View.ForSignal(_, _, _, itemTemplate)    => itemTemplate.toSeq
    case View.Styled(child, _)                    => Seq(child)
    case View.Adaptive(web, desktop, mobile, fallback) =>
      options.adaptiveMode match
        case AdaptiveMode.All             => web.toSeq ++ desktop.toSeq ++ mobile.toSeq :+ fallback
        case AdaptiveMode.WebRendered     => Seq(web.getOrElse(fallback))
        case AdaptiveMode.DesktopRendered => Seq(desktop.getOrElse(fallback))
        case AdaptiveMode.MobileRendered  => Seq(mobile.getOrElse(fallback))
    case View.Element(_, _, _, children)           => children
    case View.Portal(_, children)                  => children
    case View.ModelView(_, _, template, _)         => Seq(template)
    case View.ForModel(_, _, _, template, _)       => Seq(template)

    case View.Spacer(_) |
         View.Divider(_, _) |
         View.Text(_, _) |
         View.SignalText(_, _) |
         View.Image(_, _) |
         View.Icon(_, _) |
         View.TextInput(_, _, _, _, _) |
         View.Toggle(_, _, _) |
         View.Slider(_, _, _, _, _) |
         View.Picker(_, _, _, _) |
         View.AlertDialog(_, _, _, _) |
         View.FormField(_, _, _, _) |
         View.ItemText |
         View.DataTable(_, _, _, _) |
         View.ModelText(_, _, _) |
         View.FormattedField(_, _, _, _) |
         View.EditableCell(_, _, _) |
         View.TextNode(_) => Nil
