package scalascript.cli

/** `ssc tui [--debug] [--offline] [--verbose] <file.ssc> [-- <args>…]` — the
 *  live terminal UI runner (rust-tui-toolkit S5).
 *
 *  Transpiles the `.ssc` to a Cargo crate with the ratatui `View` renderer
 *  (`uiTarget=tui`) and `cargo run`s it, inheriting the TTY — so `signal` /
 *  `computedSignal` reactivity is LIVE in the terminal (Tab/arrows move focus,
 *  Enter/Space runs an action → recompute → redraw). This is the convergence
 *  target for `--frontend tui`, superseding the static `frontend/tui` emitter
 *  (which could only snapshot a pre-rendered View). */
final class TuiCmd extends CliCommand:
  def name: String = "tui"
  override def aliases: List[String] = List("run-tui")
  override def summary: String = "Run a .ssc UI live in the terminal (ratatui, reactive)"
  override def category: String = "Run & develop"
  override def details: List[String] = List(
    "Flags: --debug, --offline, --verbose; argv after `--`"
  )

  def run(args: List[String]): Unit =
    val code = TuiRunner.run(args)
    if code != 0 then System.exit(code)
