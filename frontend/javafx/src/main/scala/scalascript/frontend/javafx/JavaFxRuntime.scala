package scalascript.frontend.javafx

import scalascript.frontend.*
import scalascript.frontend.{FetchJsonSignal, JsonDecoder}

import _root_.javafx.application.Application
import _root_.javafx.application.Platform
import _root_.javafx.collections.FXCollections
import _root_.javafx.geometry.{Insets, Orientation}
import _root_.javafx.scene.control.*
import _root_.javafx.scene.layout.*
import _root_.javafx.util.Callback
import scala.collection.mutable

/** Same-process JavaFX runner for JVM-hosted frontend modules.
 *
 *  Runtime counterpart to `JavaFxEmitter`. `ssc run-jvm --frontend javafx`
 *  uses it so the desktop UI runs in the same JVM process as generated backend
 *  code. State is passed to `JavaFxRuntimeApp` via the companion `@volatile var`
 *  pair — the standard pattern for JavaFX apps that need constructor args.
 */
object JavaFxRuntime:

  final case class Options(
      title:           Option[String]          = None,
      width:           Double                  = 640.0,
      height:          Double                  = 420.0,
      fetchDispatcher: Option[FetchDispatcher] = None
  )

  trait FetchDispatcher:
    def request(method: String, url: String, body: String): FetchResponse

  final case class FetchResponse(
      status:  Int,
      body:    String                  = "",
      headers: Map[String, String]     = Map.empty
  )

  @volatile private[javafx] var _pendingModule:  FrontendModule | Null = null
  @volatile private[javafx] var _pendingOptions: Options                = Options()

  def run(module: FrontendModule, options: Options = Options()): Unit =
    _pendingModule  = module
    _pendingOptions = options
    Application.launch(classOf[JavaFxRuntimeApp])

  // ─── RuntimeState ──────────────────────────────────────────────────────────

  final class RuntimeState private[javafx] (
      val signals:     mutable.Map[String, Any],
      val signalRefs:  Map[String, ReactiveSignal[Any]],
      val bindings:    mutable.Map[String, mutable.Buffer[() => Unit]],
      val fetchDispatcher: Option[FetchDispatcher],
      private val subscribed: mutable.Set[String] = mutable.Set.empty,
      private val suppressExternalRefresh: mutable.Set[String] = mutable.Set.empty,
      val modelData:   Map[String, Any] = Map.empty
  ):
    private def onUiThread(run: => Unit): Unit =
      try
        if Platform.isFxApplicationThread then run
        else Platform.runLater(() => run)
      catch case _: IllegalStateException => run

    def bindSignal(id: String)(refresh: => Unit): Unit =
      bindings.getOrElseUpdate(id, mutable.Buffer.empty) += (() => refresh)
      if !subscribed.contains(id) then
        signalRefs.get(id).foreach { signal =>
          subscribed += id
          signal.subscribe { value =>
            if !suppressExternalRefresh.contains(id) then
              onUiThread {
                signals.update(id, value)
                refreshSignal(id)
              }
          }
        }
      refresh

    def refreshSignal(id: String): Unit =
      bindings.get(id).foreach(_.foreach(fn => fn()))

    def setSignal(id: String, value: Any): Unit =
      signals.update(id, value)
      signalRefs.get(id) match
        case Some(signal) =>
          suppressExternalRefresh += id
          try signal.set(value)
          finally suppressExternalRefresh -= id
          refreshSignal(id)
        case None         => refreshSignal(id)

    def incrementSignal(id: String, by: Int): Unit =
      val next = signals.get(id).collect { case n: Int => n }.getOrElse(0) + by
      setSignal(id, next)

    def toggleSignal(id: String): Unit =
      val next = !signals.get(id).collect { case b: Boolean => b }.getOrElse(false)
      setSignal(id, next)

    def signalString(id: String): String =
      signals.get(id).map(String.valueOf).getOrElse("")

    def signalBoolean(id: String): Boolean =
      signals.get(id).collect { case b: Boolean => b }.getOrElse(false)

    def withModel(varName: String, data: Any): RuntimeState =
      RuntimeState(signals, signalRefs, bindings, fetchDispatcher, subscribed, suppressExternalRefresh, modelData + (varName -> data))

  object RuntimeState:
    def from(view: View[?], fetchDispatcher: Option[FetchDispatcher] = None): RuntimeState =
      val collected = collectSignals(NativeElementLowering.lower(view))
      RuntimeState(
        mutable.Map.from(collected.map(s => s.id -> s.value)),
        collected.map(s => s.id -> s.signal.asInstanceOf[ReactiveSignal[Any]]).toMap,
        mutable.Map.empty,
        fetchDispatcher
      )

  // ─── Scene builder ─────────────────────────────────────────────────────────

  def buildRoot(view: View[?]): VBox =
    buildRoot(view, RuntimeState.from(view))

  def buildRoot(view: View[?], state: RuntimeState): VBox =
    val nativeView = NativeElementLowering.lower(view)
    val root = VBox(16.0)
    root.setPadding(Insets(16, 16, 16, 16))
    addTo(root, nativeView, state)
    root

  private[javafx] def buildViewTest(parent: Pane, view: View[?], state: RuntimeState): Unit =
    addTo(parent, view, state)

  private def modelField(data: Any, path: String): Any =
    path.split("\\.").foldLeft(data) {
      case (m: Map[String @unchecked, Any @unchecked], key) => m.getOrElse(key, null)
      case _ => null
    }

  def addTo(parent: Pane, view: View[?], state: RuntimeState): Unit =
    NativeElementLowering.lower(view) match
      case View.Text(content, style) =>
        parent.getChildren.add(styledNode(Label(content()), style))
      case View.TextNode(value) =>
        parent.getChildren.add(Label(value()))
      case View.SignalText(signal, style) =>
        val label = styledNode(Label(state.signalString(signal.id)), style)
        state.bindSignal(signal.id) {
          label.setText(state.signalString(signal.id))
        }
        parent.getChildren.add(label)
      case View.Button(label, action, enabled, style) =>
        val button = styledNode(Button(textOf(label)), style)
        button.setDisable(!enabled())
        wireAction(button, action, state)
        parent.getChildren.add(button)
      case View.TextInput(value, placeholder, multiline, secure, style) =>
        parent.getChildren.add(textInput(value, placeholder, multiline, secure, style, state))
      case View.Toggle(checked, label, style) =>
        val checkbox = styledNode(CheckBox(label), style)
        checkbox.setSelected(state.signalBoolean(checked.id))
        checkbox.selectedProperty().addListener((_, _, newVal) =>
          state.setSignal(checked.id, newVal.booleanValue()))
        state.bindSignal(checked.id) {
          val next = state.signalBoolean(checked.id)
          if checkbox.isSelected != next then checkbox.setSelected(next)
        }
        parent.getChildren.add(checkbox)
      case View.Column(children, spacing, _, style) =>
        parent.getChildren.add(box(children, vertical = true, spacing, style, state))
      case View.Row(children, spacing, _, style) =>
        parent.getChildren.add(box(children, vertical = false, spacing, style, state))
      case View.Stack(children, style) =>
        parent.getChildren.add(box(children, vertical = true, 0, style, state))
      case View.ScrollView(child, axis, style) =>
        val inner = VBox()
        addTo(inner, child, state)
        val scroll = styledNode(ScrollPane(inner), style)
        scroll.setFitToWidth(true)
        axis match
          case Axis.Horizontal =>
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)
          case Axis.Both =>
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)
          case _ =>
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)
        parent.getChildren.add(scroll)
      case View.Divider(axis, style) =>
        val orientation = if axis == Axis.Vertical then Orientation.VERTICAL else Orientation.HORIZONTAL
        parent.getChildren.add(styledNode(Separator(orientation), style))
      case View.Fragment(children) =>
        children.foreach(addTo(parent, _, state))
      case View.Element(_, _, _, children) =>
        children.foreach(addTo(parent, _, state))
      case View.Show(cond, whenTrue, whenFalse) =>
        addTo(parent, if cond() then whenTrue() else whenFalse(), state)
      case View.ShowSignal(cond, whenTrue, whenFalse) =>
        addTo(parent, if cond.apply() then whenTrue else whenFalse, state)
      case View.For(items, render) =>
        items().foreach(item => addTo(parent, render(item), state))
      case View.Styled(child, style) =>
        addTo(parent, restyle(child, style), state)
      case View.Adaptive(_, desktop, _, fallback) =>
        addTo(parent, desktop.getOrElse(fallback), state)
      case View.Spacer(size) =>
        val px = size.map(_.round.toInt).getOrElse(8)
        val spacer = Region()
        spacer.setPrefSize(px.toDouble, px.toDouble)
        parent.getChildren.add(spacer)
      case View.ModelView(signal, bindingVar, template, _) =>
        val wrapper = VBox()
        if !state.signals.contains(signal.id) then state.signals.update(signal.id, null)
        def rebuild(): Unit =
          wrapper.getChildren.clear()
          val data = state.signals.getOrElse(signal.id, null)
          if data != null then
            val childState = state.withModel(bindingVar, data)
            addTo(wrapper, template, childState)
        state.bindings.getOrElseUpdate(signal.id, mutable.Buffer.empty) += (() => rebuild())
        rebuild()
        val typeName = signal match { case fjs: FetchJsonSignal => fjs.modelTypeName; case _ => "" }
        Thread(() =>
          try state.fetchDispatcher.foreach { dispatcher =>
            val response = dispatcher.request("GET", signal.fetchUrl, "")
            if response.status >= 200 && response.status < 300 then
              val decoded = JsonDecoder.current.decodeString(response.body, typeName)
              Platform.runLater(() => state.setSignal(signal.id, decoded))
          } catch case _: Exception => ()
        ).start()
        parent.getChildren.add(wrapper)

      case View.ForModel(bindingVar, fieldPath, itemVar, template, _) =>
        val data  = state.modelData.getOrElse(bindingVar, null)
        val items = if data != null then modelField(data, fieldPath) else null
        val itemList = items match
          case list: scala.collection.immutable.List[Any @unchecked] => list
          case seq:  Seq[Any @unchecked]                             => seq.toList
          case arr:  Array[Any @unchecked]                           => arr.toList
          case _                                                     => Nil
        val forPane = VBox()
        itemList.foreach { item =>
          val childState = state.withModel(itemVar, item)
          addTo(forPane, template, childState)
        }
        parent.getChildren.add(forPane)

      case View.ModelText(varName, fieldPath, style) =>
        val data  = state.modelData.getOrElse(varName, null)
        val value = if data != null then String.valueOf(modelField(data, fieldPath)) else ""
        parent.getChildren.add(styledNode(Label(value), style))

      case dt: View.DataTable =>
        parent.getChildren.add(nativeDataTable(dt, state))

      case other =>
        parent.getChildren.add(Label(s"[unsupported JavaFX view: ${other.productPrefix}]"))

  private def nativeDataTable(dt: View.DataTable, state: RuntimeState): _root_.javafx.scene.Node =
    type Row = _root_.javafx.collections.ObservableList[String]
    val rows = FXCollections.observableArrayList[Row]()
    val tableView = new TableView[Row](rows)
    tableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY)

    val hasEditable = dt.columns.exists(_.editAction.isDefined)
    if hasEditable then tableView.setEditable(true)

    dt.columns.zipWithIndex.foreach { (col, idx) =>
      val tc = new TableColumn[Row, String](col.title)
      tc.setCellValueFactory { cellData =>
        val v = cellData.getValue
        val s = if idx < v.size then v.get(idx) else ""
        new _root_.javafx.beans.property.SimpleStringProperty(s)
      }
      col.editAction.foreach { ea =>
        tc.setCellFactory(_root_.javafx.scene.control.cell.TextFieldTableCell.forTableColumn())
        tc.setOnEditCommit { event =>
          val newValue = event.getNewValue
          val rowData  = event.getRowValue
          rowData.set(idx, newValue)
          val idColIdx = dt.columns.indexWhere(_.fieldPath == ea.idField)
          val idValue  = if idColIdx >= 0 then rowData.get(idColIdx) else ""
          val idKey    = ea.idField.split('.').last
          val valKey   = col.fieldPath.split('.').last
          def esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
          val body = s"""{"${esc(idKey)}":"${esc(idValue)}","${esc(valKey)}":"${esc(newValue)}"}"""
          Thread(() =>
            try state.fetchDispatcher.foreach { d =>
              val r = d.request(ea.method, ea.url, body)
              if r.status >= 200 && r.status < 300 then
                Platform.runLater(() => state.incrementSignal(ea.onSuccessTick.id, 1))
            } catch case _: Exception => ()
          ).start()
        }
      }
      tableView.getColumns.add(tc)
    }

    def rebuildRows(decoded: Any): Unit =
      val items = decoded match
        case list: List[Any @unchecked]  => list
        case seq:  Seq[Any @unchecked]   => seq.toList
        case arr:  Array[Any @unchecked] => arr.toList
        case _                            => Nil
      val newRows = items.map { row =>
        val cells = dt.columns.map(c => String.valueOf(modelField(row, c.fieldPath)))
        FXCollections.observableArrayList(cells*)
      }
      Platform.runLater(() => { rows.setAll(newRows*) })

    def doFetch(): Unit =
      dt.source match
        case TableDataSource.Remote(sig) =>
          Thread(() =>
            try state.fetchDispatcher.foreach { dispatcher =>
              val typeName = sig match { case fjs: FetchJsonSignal => fjs.modelTypeName; case _ => "" }
              val response = dispatcher.request("GET", sig.fetchUrl, "")
              if response.status >= 200 && response.status < 300 then
                val decoded = JsonDecoder.current.decodeString(response.body, typeName)
                rebuildRows(decoded)
            } catch case _: Exception => ()
          ).start()
        case _ => () // StaticRows / SignalRows: not yet implemented in JavaFX

    val wrapper = VBox(4.0)
    wrapper.getChildren.add(new ScrollPane(tableView) {
      setFitToWidth(true)
      setPrefHeight(300)
    })

    if dt.actions.nonEmpty then
      val btnBox = HBox(4.0)
      dt.actions.foreach { action =>
        val label = action match
          case RowActionDef.RowDelete(_, _, _, _)          => "Delete"
          case RowActionDef.RowPost(lbl, _, _, _, _, _)    => lbl
          case RowActionDef.RowLink(lbl, _, _)             => lbl
          case RowActionDef.RowInlineEdit(_, _, _, _, _)   => ""
        val btn = new Button(label)
        btn.setOnAction { _ =>
          val sel = tableView.getSelectionModel.getSelectedIndex
          if sel >= 0 then
            val row = rows.get(sel)
            action match
              case RowActionDef.RowDelete(url, idField, tick, _) =>
                val colIdx = dt.columns.indexWhere(_.fieldPath == idField)
                val idVal  = if colIdx >= 0 then row.get(colIdx) else ""
                Thread(() =>
                  try state.fetchDispatcher.foreach { d =>
                    val r = d.request("POST", url, idVal)
                    if r.status >= 200 && r.status < 300 then
                      Platform.runLater(() => state.incrementSignal(tick.id, 1))
                  } catch case _: Exception => ()
                ).start()
              case RowActionDef.RowPost(_, method, url, payload, tick, _) =>
                val bodyVal = payload match
                  case RowPayload.Field(name) =>
                    val ci = dt.columns.indexWhere(_.fieldPath == name)
                    if ci >= 0 then row.get(ci) else ""
                  case RowPayload.WholeRow =>
                    (0 until row.size).map { ci =>
                      s""""${dt.columns(ci).fieldPath}": "${row.get(ci)}""""
                    }.mkString("{", ", ", "}")
                  case RowPayload.Fields(names) =>
                    names.flatMap { n =>
                      val ci = dt.columns.indexWhere(_.fieldPath == n)
                      if ci >= 0 then Some(s""""$n": "${row.get(ci)}"""") else None
                    }.mkString("{", ", ", "}")
                Thread(() =>
                  try state.fetchDispatcher.foreach { d =>
                    val r = d.request(method, url, bodyVal)
                    if r.status >= 200 && r.status < 300 then
                      Platform.runLater(() => state.incrementSignal(tick.id, 1))
                  } catch case _: Exception => ()
                ).start()
              case RowActionDef.RowLink(_, signal, fieldPath) =>
                val colIdx   = dt.columns.indexWhere(_.fieldPath == fieldPath)
                val fieldVal = if colIdx >= 0 then row.get(colIdx) else ""
                Platform.runLater(() => state.setSignal(signal.id, fieldVal))
              case RowActionDef.RowInlineEdit(_, _, _, _, _) => ()
        }
        btnBox.getChildren.add(btn)
      }
      wrapper.getChildren.add(btnBox)
      dt.actions.foreach {
        case RowActionDef.RowDelete(_, _, tick, _)       =>
          state.bindings.getOrElseUpdate(tick.id, mutable.Buffer.empty) += (() => doFetch())
        case RowActionDef.RowPost(_, _, _, _, tick, _)   =>
          state.bindings.getOrElseUpdate(tick.id, mutable.Buffer.empty) += (() => doFetch())
        case _ => ()
      }

    doFetch()
    wrapper

  private def box(children: Seq[View[?]], vertical: Boolean, spacing: Double, style: Style, state: RuntimeState): Pane =
    val pane: Pane = if vertical then VBox(spacing) else HBox(spacing)
    applyStyle(pane, style)
    children.foreach(addTo(pane, _, state))
    pane

  private def textInput(
      value:       ReactiveSignal[String],
      placeholder: String,
      multiline:   Boolean,
      secure:      Boolean,
      style:       Style,
      state:       RuntimeState
  ): _root_.javafx.scene.Node =
    if multiline then
      val area = styledNode(TextArea(state.signalString(value.id)), style)
      area.setPromptText(placeholder)
      area.textProperty().addListener((_, _, newVal) => state.setSignal(value.id, newVal))
      state.bindSignal(value.id) {
        val next = state.signalString(value.id)
        if area.getText != next then area.setText(next)
      }
      area
    else if secure then
      val field = styledNode(PasswordField(), style)
      field.textProperty().addListener((_, _, newVal) => state.setSignal(value.id, newVal))
      state.bindSignal(value.id) {
        val next = state.signalString(value.id)
        if field.getText != next then field.setText(next)
      }
      field
    else
      val field = styledNode(TextField(state.signalString(value.id)), style)
      field.setPromptText(placeholder)
      field.textProperty().addListener((_, _, newVal) => state.setSignal(value.id, newVal))
      state.bindSignal(value.id) {
        val next = state.signalString(value.id)
        if field.getText != next then field.setText(next)
      }
      field

  private def wireAction(button: Button, action: EventHandler, state: RuntimeState): Unit =
    action match
      case EventHandler.SetSignalLiteral(signal, value) =>
        button.setOnAction(_ => state.setSignal(signal.id, value))
      case EventHandler.IncrementSignal(signal, by) =>
        button.setOnAction(_ => state.incrementSignal(signal.id, by))
      case EventHandler.ToggleSignal(signal) =>
        button.setOnAction(_ => state.toggleSignal(signal.id))
      case EventHandler.Simple(action) =>
        button.setOnAction(_ => action())
      case EventHandler.WithEvent(action) =>
        button.setOnAction(event => action(event))
      case EventHandler.FetchAction(method, url, body, onSuccessTick, clearBody, _) =>
        button.setOnAction { _ =>
          state.fetchDispatcher.foreach { dispatcher =>
            val response = dispatcher.request(method, url, state.signalString(body.id))
            if response.status >= 200 && response.status < 300 then
              state.incrementSignal(onSuccessTick.id, 1)
              if clearBody then state.setSignal(body.id, "")
          }
        }
      case _ => ()

  private def styledNode[A <: _root_.javafx.scene.Node](node: A, style: Style): A =
    val css = buildCss(style)
    if css.nonEmpty then node.setStyle(css)
    node

  private def applyStyle(node: _root_.javafx.scene.Node, style: Style): Unit =
    val css = buildCss(style)
    if css.nonEmpty then node.setStyle(css)

  private def buildCss(style: Style): String =
    val parts = mutable.ArrayBuffer.empty[String]
    style.text.foreground.foreach(c => parts += s"-fx-text-fill: ${cssColor(c)}")
    style.text.fontSize.foreach(s => parts += s"-fx-font-size: ${s.toInt}px")
    style.text.fontWeight.foreach {
      case FontWeight.Bold | FontWeight.ExtraBold | FontWeight.Black | FontWeight.SemiBold =>
        parts += "-fx-font-weight: bold"
      case _ =>
    }
    if style.text.fontStyle == FontStyle.Italic then parts += "-fx-font-style: italic"
    style.decoration.background.foreach(c => parts += s"-fx-background-color: ${cssColor(c)}")
    style.decoration.borderColor.foreach(c => parts += s"-fx-border-color: ${cssColor(c)}")
    style.decoration.borderWidth.foreach(w => parts += s"-fx-border-width: ${w.toInt}px")
    val p = style.layout.padding
    if p != EdgeInsets.zero then
      parts += s"-fx-padding: ${p.top.toInt}px ${p.right.toInt}px ${p.bottom.toInt}px ${p.left.toInt}px"
    parts.mkString("; ")

  private def cssColor(color: Color): String =
    color match
      case Color.Hex(value)         => if value.startsWith("#") then value else s"#$value"
      case Color.Rgb(r, g, b)       => s"rgb($r, $g, $b)"
      case Color.Rgba(r, g, b, _)   => s"rgb($r, $g, $b)"
      case Color.Named(n)           => n
      case Color.System(n)          => n
      case Color.Transparent        => "transparent"

  private def restyle(view: View[?], style: Style): View[?] =
    view match
      case View.Text(content, _)                         => View.Text(content, style)
      case View.SignalText(signal, _)                    => View.SignalText(signal, style)
      case View.Button(label, action, enabled, _)        => View.Button(label, action, enabled, style)
      case View.TextInput(value, ph, ml, sc, _)          => View.TextInput(value, ph, ml, sc, style)
      case View.Toggle(checked, label, _)                => View.Toggle(checked, label, style)
      case View.Column(children, spacing, align, _)      => View.Column(children, spacing, align, style)
      case View.Row(children, spacing, align, _)         => View.Row(children, spacing, align, style)
      case View.Divider(axis, _)                         => View.Divider(axis, style)
      case other                                         => other

  private def textOf(view: View[?]): String =
    view match
      case View.Text(content, _)      => content()
      case View.TextNode(value)       => value()
      case View.SignalText(signal, _) => String.valueOf(signal.apply())
      case View.Fragment(children)    => children.map(textOf).mkString
      case View.Styled(child, _)      => textOf(child)
      case _                          => "Button"

  // ─── Signal collection (mirrors JavaFxEmitter.collectSignals) ──────────────

  private final case class SignalInitial(id: String, value: Any, signal: ReactiveSignal[?])

  private def collectSignals(view: View[?]): List[SignalInitial] =
    def add(acc: Map[String, SignalInitial], sig: ReactiveSignal[?]): Map[String, SignalInitial] =
      sig match
        case seed: SeedSignal =>
          val withSource = add(acc, seed.source)
          withSource.updatedWith(seed.id) {
            case existing @ Some(_) => existing
            case None               => Some(SignalInitial(seed.id, seed.apply(), seed))
          }
        case _ =>
          acc.updatedWith(sig.id) {
            case existing @ Some(_) => existing
            case None               => Some(SignalInitial(sig.id, sig.apply(), sig))
          }
    def action(acc: Map[String, SignalInitial], h: EventHandler): Map[String, SignalInitial] =
      h match
        case EventHandler.SetSignalLiteral(s, _) => add(acc, s)
        case EventHandler.IncrementSignal(s, _)  => add(acc, s)
        case EventHandler.ToggleSignal(s)        => add(acc, s)
        case EventHandler.InputChange(s)         => add(acc, s)
        case EventHandler.FetchAction(_, _, b, t, _, _) => add(add(acc, b), t)
        case _ => acc
    def loop(acc: Map[String, SignalInitial], v: View[?]): Map[String, SignalInitial] =
      v match
        case View.SignalText(s, _)           => add(acc, s)
        case View.Button(_, h, _, _)         => action(acc, h)
        case View.TextInput(s, _, _, _, _)   => add(acc, s)
        case View.Toggle(s, _, _)            => add(acc, s)
        case View.Column(ch, _, _, _)        => ch.foldLeft(acc)(loop)
        case View.Row(ch, _, _, _)           => ch.foldLeft(acc)(loop)
        case View.Stack(ch, _)               => ch.foldLeft(acc)(loop)
        case View.ScrollView(ch, _, _)       => loop(acc, ch)
        case View.Fragment(ch)               => ch.foldLeft(acc)(loop)
        case View.Element(_, _, _, ch)       => ch.foldLeft(acc)(loop)
        case View.Show(_, wt, wf)            => loop(loop(acc, wt()), wf())
        case View.ShowSignal(c, wt, wf)      => loop(loop(add(acc, c), wt), wf)
        case View.For(items, render)         => items().foldLeft(acc)((a, i) => loop(a, render(i)))
        case View.Styled(ch, _)              => loop(acc, ch)
        case View.Adaptive(w, d, m, f)       => List(w, d, m).flatten.foldLeft(loop(acc, f))(loop)
        case View.ModelView(_, _, tmpl, _)   => loop(acc, tmpl)
        case View.ForModel(_, _, _, tmpl, _) => loop(acc, tmpl)
        case dt: View.DataTable =>
          val base = dt.source match
            case TableDataSource.Remote(sig) => add(acc, sig)
            case _ => acc
          dt.actions.foldLeft(base) {
            case (a, RowActionDef.RowDelete(_, _, tick, _))         => add(a, tick)
            case (a, RowActionDef.RowPost(_, _, _, _, tick, _))      => add(a, tick)
            case (a, RowActionDef.RowLink(_, sig, _))               => add(a, sig)
            case (a, RowActionDef.RowInlineEdit(_, _, _, tick, _))  => add(a, tick)
          }
        case _                               => acc
    loop(Map.empty, view).values.toList.sortBy(_.id)
