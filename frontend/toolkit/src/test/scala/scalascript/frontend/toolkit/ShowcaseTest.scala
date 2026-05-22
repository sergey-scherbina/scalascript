package scalascript.frontend.toolkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.frontend.{View, AttrValue, EventHandler, ReactiveSignal}

/** v1.18 / Phase B — comprehensive frontend-toolkit showcase.
 *
 *  This file is **the** reference example for the toolkit: a single
 *  mini-app built entirely through the `Tk` facade (no raw `View`,
 *  no case-class node constructors) that exercises every public
 *  widget pack at least once.
 *
 *  Anyone reading this test learns:
 *    - How to use `Tk.router` + `Tk.route` for multi-page apps
 *    - How to lay out screens with `Tk.vstack`, `Tk.hstack`, `Tk.box`,
 *      `Tk.spacer`, `Tk.divider`, `Tk.card`
 *    - How to wire forms with `Tk.form` + `Validators.*` + the new
 *      `Tk.select`, `Tk.radioGroup`, `Tk.textarea`, `Tk.datePicker`,
 *      `Tk.numberInput` from the FormInputs pack
 *    - How to render data with `Tk.table` + sortable columns
 *    - How to compose the Widgets v2 pack (`Tk.tabs`, `Tk.modal`,
 *      `Tk.drawer`, `Tk.badge`, `Tk.avatar`, `Tk.spinner`,
 *      `Tk.progress`, `Tk.alert`)
 *    - How `Theme.default` vs `Theme.dark` plug in transparently
 *
 *  The structural assertions are deliberately broad — depth-first
 *  tag scans, "contains-substring" style checks — so the showcase
 *  stays useful even as widget internals evolve. */
class ShowcaseTest extends AnyFunSuite with Matchers:

  import Tk.*

  // ─── Mini-app domain ───────────────────────────────────────────

  /** One user row in the showcase user list. */
  final case class User(id: String, name: String, email: String)

  /** Settings form's `Tier` enum — wired into `Tk.select`. */
  enum Tier:
    case Free, Pro, Team

  /** Settings form's `Theme preference` — wired into `Tk.radioGroup`. */
  enum ThemeChoice:
    case Light, Dark, System

  // ─── App-tree builder ──────────────────────────────────────────

  /** Build the entire showcase app tree.  Returns the root
   *  `ToolkitNode` plus every reactive signal it touches, so
   *  individual tests can drive interactions (click, sort, route
   *  change, modal open) and re-lower to observe the effects. */
  private def buildApp(): ShowcaseApp =
    // Routing state — start on the user list.
    val currentPath = new ReactiveSignal[String]("path", "/users")

    // Drawer / modal / tab signals.
    val drawerOpen = new ReactiveSignal[Boolean]("drawer", false)
    val modalOpen  = new ReactiveSignal[Boolean]("modal",  false)
    val activeTab  = new ReactiveSignal[String]("tab",     "settings")

    // Login form signals (driven through Tk.form below).
    val loginEmail    = new ReactiveSignal[String]("login_email", "")
    val loginPassword = new ReactiveSignal[String]("login_pass",  "")

    // User list — table data.
    val users = new ReactiveSignal[Seq[User]]("users", Seq(
      User("u1", "Alice",   "alice@example.com"),
      User("u2", "Charlie", "charlie@example.com"),
      User("u3", "Bob",     "bob@example.com")
    ))
    val tableSort = new ReactiveSignal[TableSort]("sort",
      TableSort(0, SortDirection.Off))

    // Settings form signals (FormInputs pack).
    val tier        = new ReactiveSignal[Tier]("tier", Tier.Free)
    val themePref   = new ReactiveSignal[ThemeChoice]("themePref", ThemeChoice.System)
    val bio         = new ReactiveSignal[String]("bio", "")
    val birthday    = new ReactiveSignal[String]("birthday", "")
    val seats       = new ReactiveSignal[Double]("seats", 1.0)

    // Progress signal for the "loading" badge.
    val uploadPct   = new ReactiveSignal[Double]("upload", 35.0)

    // Submission counters — give tests a way to observe form submits.
    val loginSubmits    = new java.util.concurrent.atomic.AtomicInteger(0)
    val settingsSubmits = new java.util.concurrent.atomic.AtomicInteger(0)

    // Login form (used inside the "/" home route).
    def loginForm: ToolkitNode =
      form(onSubmit = _ => loginSubmits.incrementAndGet()) { ctx =>
        val email = ctx.field[String]("email", loginEmail(),
          Validators.and(Validators.required, Validators.email))
        val pwd   = ctx.field[String]("password", loginPassword(),
          Validators.and(Validators.required, Validators.minLength(8)))
        // Mirror form fields into the outer signals so tests can
        // both *seed* the form (write to the outer signal before
        // building) and *observe* the form (read back from the
        // inner field signal after submit).
        loginEmail.set(email.value())
        loginPassword.set(pwd.value())
        vstack(gap = 8)(
          heading(2, "Sign in"),
          textField(value = email.value, label = Some("Email"),
                    placeholder = Some("you@example.com"),
                    required = true, inputType = "email",
                    error = Some(email.error)),
          textField(value = pwd.value, label = Some("Password"),
                    required = true, inputType = "password",
                    error = Some(pwd.error)),
          button("Sign in", onClick = () => (), formSubmit = true)
        )
      }

    // Settings form (used inside the "Settings" tab).
    def settingsForm: ToolkitNode =
      form(onSubmit = _ => settingsSubmits.incrementAndGet()) { ctx =>
        ctx.field[Tier]("tier", tier())
        ctx.field[ThemeChoice]("theme", themePref())
        ctx.field[String]("bio", bio())
        ctx.field[String]("birthday", birthday())
        ctx.field[Double]("seats", seats())
        vstack(gap = 12)(
          heading(3, "Account settings"),
          select[Tier](
            value   = tier,
            options = Seq(Tier.Free -> "Free", Tier.Pro -> "Pro", Tier.Team -> "Team"),
            label   = Some("Subscription")
          ),
          radioGroup[ThemeChoice](
            value   = themePref,
            options = Seq(
              ThemeChoice.Light  -> "Light",
              ThemeChoice.Dark   -> "Dark",
              ThemeChoice.System -> "System"
            ),
            label   = Some("Appearance")
          ),
          textarea(value = bio, label = Some("Bio"),
                   placeholder = Some("Tell us about yourself"),
                   rows = 4, maxLength = Some(240)),
          datePicker(value = birthday, label = Some("Birthday"),
                     min = Some("1900-01-01")),
          numberInput(value = seats, label = Some("Seats"),
                      min = Some(1.0), max = Some(100.0), step = 1.0),
          button("Save", onClick = () => (), formSubmit = true)
        )
      }

    // Users table (used inside the "/users" route).
    def usersTable: ToolkitNode =
      table[User](
        rows = users,
        key  = _.id,
        columns = Seq(
          sortableColumn[User]("Name",  _.name) (u => text(u.name)),
          sortableColumn[User]("Email", _.email)(u => text(u.email))
        ),
        sort       = Some(tableSort),
        emptyState = Some(text("No users yet")),
        caption    = Some("Active users")
      )

    // User-detail panel (used inside the "/users/:id" route).
    def userDetail(params: Map[String, String]): ToolkitNode =
      val id = params.getOrElse("id", "?")
      val user = users().find(_.id == id)
      card(header = Some(heading(2, s"User $id"))) {
        user match
          case Some(u) =>
            vstack(gap = 8)(
              hstack(gap = 12, align = Alignment.Center)(
                avatar(u.name, size = 48),
                vstack(gap = 4)(
                  text(u.name, weight = Some(600)),
                  text(u.email, variant = TextVariant.BodySmall)
                )
              ),
              divider(),
              button("Delete user", onClick = () => modalOpen.set(true),
                     kind = ButtonKind.Danger)
            )
          case None =>
            Tk.alert(AlertSeverity.Warning, title = Some("Not found"))(
              text(s"No user with id $id")
            )
      }

    // The persistent shell: top bar + main router + drawer + modal.
    def shell(routerView: ToolkitNode): ToolkitNode =
      vstack(gap = 16)(
        // Top bar.
        box(padding = 12, bg = Some("surface"))(
          hstack(gap = 12, align = Alignment.Center)(
            button("☰", onClick = () => drawerOpen.set(true),
                   kind = ButtonKind.Ghost),
            heading(1, "Showcase"),
            spacer(grow = true),
            badge("BETA", BadgeVariant.Notification),
            avatar("Sergiy S")
          )
        ),
        // Main content.
        box(padding = 16)(routerView),
        // Drawer + modal — both lower to empty Fragments when closed
        // so they cost nothing in that state.
        drawer(drawerOpen, side = DrawerSide.Left,
               onClose = () => drawerOpen.set(false))(
          vstack(gap = 8)(
            heading(3, "Navigation"),
            link("/",          currentPath = Some(currentPath))(text("Home")),
            link("/users",     currentPath = Some(currentPath))(text("Users")),
            link("/users/u1",  currentPath = Some(currentPath))(text("User u1"))
          )
        ),
        modal(modalOpen, title = Some("Confirm delete"),
              onClose = () => modalOpen.set(false))(
          vstack(gap = 8)(
            text("Are you sure you want to delete this user?"),
            hstack(gap = 8)(
              button("Cancel", onClick = () => modalOpen.set(false),
                     kind = ButtonKind.Subtle),
              button("Delete", onClick = () => modalOpen.set(false),
                     kind = ButtonKind.Danger)
            )
          )
        )
      )

    // Three tabs: settings | profile | billing.  Settings re-uses
    // the FormInputs-heavy settings form; billing shows a spinner +
    // progress + alert so the Widgets-v2 display widgets all appear.
    def settingsScreen: ToolkitNode =
      tabs(activeTab)(
        tab("settings", "Settings", settingsForm),
        tab("profile",  "Profile",
          vstack(gap = 8)(
            heading(3, "Profile"),
            hstack(gap = 12, align = Alignment.Center)(
              avatar("Sergiy S", size = 64),
              text("Sergiy Shcherbyna")
            )
          )),
        tab("billing", "Billing",
          vstack(gap = 8)(
            heading(3, "Billing"),
            Tk.alert(AlertSeverity.Info, title = Some("Payment"))(
              text("Your next invoice is due May 31.")
            ),
            progress(uploadPct, max = 100.0, label = Some("Upload")),
            hstack(gap = 8)(
              spinner(),
              text("Processing transaction…")
            )
          ))
      )

    val routerView = router(
      currentPath = currentPath,
      notFound    = Tk.alert(AlertSeverity.Error, title = Some("404"))(
                      text("Page not found")
                    )
    )(
      route("/")           (_      => loginForm),
      route("/users")      (_      => vstack(gap = 12)(
                                        heading(2, "Users"),
                                        card()(usersTable)
                                      )),
      route("/users/:id")  (params => userDetail(params)),
      route("/settings")   (_      => settingsScreen)
    )

    ShowcaseApp(
      root            = shell(routerView),
      currentPath     = currentPath,
      drawerOpen      = drawerOpen,
      modalOpen       = modalOpen,
      activeTab       = activeTab,
      loginEmail      = loginEmail,
      loginPassword   = loginPassword,
      users           = users,
      tableSort       = tableSort,
      tier            = tier,
      themePref       = themePref,
      bio             = bio,
      birthday        = birthday,
      seats           = seats,
      uploadPct       = uploadPct,
      loginSubmits    = loginSubmits,
      settingsSubmits = settingsSubmits
    )

  /** Bag of state returned by `buildApp` — the showcase root +
   *  every signal a test might want to poke at. */
  private final case class ShowcaseApp(
    root:            ToolkitNode,
    currentPath:     ReactiveSignal[String],
    drawerOpen:      ReactiveSignal[Boolean],
    modalOpen:       ReactiveSignal[Boolean],
    activeTab:       ReactiveSignal[String],
    loginEmail:      ReactiveSignal[String],
    loginPassword:   ReactiveSignal[String],
    users:           ReactiveSignal[Seq[User]],
    tableSort:       ReactiveSignal[TableSort],
    tier:            ReactiveSignal[Tier],
    themePref:       ReactiveSignal[ThemeChoice],
    bio:             ReactiveSignal[String],
    birthday:        ReactiveSignal[String],
    seats:           ReactiveSignal[Double],
    uploadPct:       ReactiveSignal[Double],
    loginSubmits:    java.util.concurrent.atomic.AtomicInteger,
    settingsSubmits: java.util.concurrent.atomic.AtomicInteger
  )

  // ─── Main showcase test ────────────────────────────────────────

  test("Showcase: full app tree lowers under Theme.default"):
    val app  = buildApp()
    val view = Toolkit.lower(app.root, Theme.default)
    // Root is the shell — a vstack wraps everything.
    view shouldBe a [View.Element]
    view.asInstanceOf[View.Element].tag shouldBe "div"

  test("Showcase: top-level shell contains the BETA badge + avatar"):
    val view = Toolkit.lower(buildApp().root, Theme.default)
    val rendered = renderStructure(view)
    rendered should include ("BETA")        // badge content
    rendered should include ("Showcase")    // heading text

  test("Showcase: /users route renders the table with both columns"):
    val app = buildApp()
    app.currentPath.set("/users")
    val view  = Toolkit.lower(app.root, Theme.default)
    val tables = collectTag(view, "table")
    tables.length shouldBe 1
    val headerCells = collectTag(view, "th")
    headerCells.length shouldBe 2
    val rendered = renderStructure(view)
    rendered should include ("Name")
    rendered should include ("Email")
    rendered should include ("Alice")
    rendered should include ("Bob")

  test("Showcase: /users/:id route picks up the param + renders the user card"):
    val app = buildApp()
    app.currentPath.set("/users/u2")
    val rendered = renderStructure(Toolkit.lower(app.root, Theme.default))
    rendered should include ("User u2")
    rendered should include ("Charlie")
    rendered should include ("charlie@example.com")

  test("Showcase: unknown path falls through to the 404 notFound view"):
    val app = buildApp()
    app.currentPath.set("/nowhere")
    val rendered = renderStructure(Toolkit.lower(app.root, Theme.default))
    rendered should include ("404")
    rendered should include ("Page not found")

  test("Showcase: home (/) renders the login form with email + password"):
    val app = buildApp()
    app.currentPath.set("/")
    val view = Toolkit.lower(app.root, Theme.default)
    val forms = collectTag(view, "form")
    forms.length shouldBe 1
    val inputs = collectTag(view, "input")
    // Email + password inputs only — login form has just those two.
    inputs.length shouldBe 2
    val rendered = renderStructure(view)
    rendered should include ("Sign in")

  test("Showcase: /settings tab pack contains all FormInputs widgets"):
    val app = buildApp()
    app.currentPath.set("/settings")
    val view = Toolkit.lower(app.root, Theme.default)
    val rendered = renderStructure(view)
    // FormInputs pack: select, radio group, textarea, date input,
    // number input.
    collectTag(view, "select").length     should be >= 1
    collectTag(view, "textarea").length   should be >= 1
    // Date + number inputs share the <input> tag — at least one of
    // each distinguishable by its `type` attr.
    val inputTypes = collectTag(view, "input")
      .map(_.attrs.get("type").collect { case AttrValue.Str(s) => s }.getOrElse(""))
    inputTypes should contain ("date")
    inputTypes should contain ("number")
    // Three radio buttons in the ThemeChoice group.
    inputTypes.count(_ == "radio") shouldBe 3
    // Tab labels show up.
    rendered should include ("Settings")
    rendered should include ("Profile")
    rendered should include ("Billing")

  test("Showcase: /settings (billing tab) shows alert + progress + spinner"):
    val app = buildApp()
    app.currentPath.set("/settings")
    app.activeTab.set("billing")
    val view = Toolkit.lower(app.root, Theme.default)
    val rendered = renderStructure(view)
    rendered should include ("Your next invoice")
    collectTag(view, "progress").length shouldBe 1
    // Spinner div carries role=status with aria-label="Loading".
    val statusEls = collectAllElements(view).filter { e =>
      e.attrs.get("role").contains(AttrValue.Str("status")) &&
      e.attrs.get("aria-label").contains(AttrValue.Str("Loading"))
    }
    statusEls.length shouldBe 1

  // ─── Routing tests ─────────────────────────────────────────────

  test("Routing: setting currentPath swaps which route renders"):
    val app = buildApp()
    app.currentPath.set("/")
    val home = renderStructure(Toolkit.lower(app.root, Theme.default))
    home should include ("Sign in")
    home should not include ("Users")  // /users heading not yet visible

    app.currentPath.set("/users")
    val users = renderStructure(Toolkit.lower(app.root, Theme.default))
    users should not include ("Sign in")
    users should include ("Alice")

  test("Routing: param routes extract :id from the path"):
    val app = buildApp()
    app.currentPath.set("/users/u3")
    val rendered = renderStructure(Toolkit.lower(app.root, Theme.default))
    rendered should include ("User u3")
    rendered should include ("Bob")

  // ─── Login form tests ──────────────────────────────────────────

  test("Login form: invalid email + short password blocks submit"):
    val app = buildApp()
    app.currentPath.set("/")
    // Seed bad credentials.
    app.loginEmail.set("not-an-email")
    app.loginPassword.set("123")
    // Re-lower so the form is built with the new defaults.
    val view = Toolkit.lower(app.root, Theme.default)
    val formEl = collectTag(view, "form").head
    formEl.events("submit") match
      case EventHandler.WithEvent(f) => f(null)
      case other => fail(s"got $other")
    // No successful submit recorded.
    app.loginSubmits.get shouldBe 0

  test("Login form: valid email + 8+ char password passes submit"):
    val app = buildApp()
    app.currentPath.set("/")
    app.loginEmail.set("alice@example.com")
    app.loginPassword.set("hunter2!!")
    val view   = Toolkit.lower(app.root, Theme.default)
    val formEl = collectTag(view, "form").head
    formEl.events("submit").asInstanceOf[EventHandler.WithEvent].action(null)
    app.loginSubmits.get shouldBe 1

  // ─── Settings form tests ───────────────────────────────────────

  test("Settings form: submit reaches the onSubmit callback"):
    val app = buildApp()
    app.currentPath.set("/settings")
    app.activeTab.set("settings")
    val view = Toolkit.lower(app.root, Theme.default)
    val formEl = collectTag(view, "form").head
    formEl.events("submit").asInstanceOf[EventHandler.WithEvent].action(null)
    app.settingsSubmits.get shouldBe 1

  // ─── Table click-to-sort ──────────────────────────────────────

  test("Table: click-to-sort cycles Off → Asc → Desc → Off"):
    val app = buildApp()
    app.currentPath.set("/users")
    // Round 1: Off → Asc on column 0.
    var view  = Toolkit.lower(app.root, Theme.default)
    val ths0  = collectTag(view, "th")
    ths0.head.events("click").asInstanceOf[EventHandler.Simple].action()
    app.tableSort().columnIndex shouldBe 0
    app.tableSort().direction   shouldBe SortDirection.Asc

    // Round 2: Asc → Desc on the same column.
    view = Toolkit.lower(app.root, Theme.default)
    val ths1 = collectTag(view, "th")
    ths1.head.events("click").asInstanceOf[EventHandler.Simple].action()
    app.tableSort().direction shouldBe SortDirection.Desc

    // Round 3: Desc → Off.
    view = Toolkit.lower(app.root, Theme.default)
    val ths2 = collectTag(view, "th")
    ths2.head.events("click").asInstanceOf[EventHandler.Simple].action()
    app.tableSort().direction shouldBe SortDirection.Off

  test("Table: sort by Name asc reorders the rendered rows"):
    val app = buildApp()
    app.currentPath.set("/users")
    app.tableSort.set(TableSort(0, SortDirection.Asc))
    val view = Toolkit.lower(app.root, Theme.default)
    // tbody children are <tr> with data-row-key set to user.id —
    // asc by name puts Alice → Bob → Charlie.
    val rows = collectAllElements(view).filter(_.tag == "tr")
      .flatMap(_.attrs.get("data-row-key").collect { case AttrValue.Str(s) => s })
    rows shouldBe Seq("u1", "u3", "u2")  // Alice, Bob, Charlie

  // ─── Modal open/close tests ────────────────────────────────────

  test("Modal: closed by default — drawer + modal both render empty Fragments"):
    val app = buildApp()
    val view = Toolkit.lower(app.root, Theme.default)
    // No role=dialog appears anywhere — both modal + drawer are closed.
    val dialogs = collectAllElements(view).filter { e =>
      e.attrs.get("role").contains(AttrValue.Str("dialog"))
    }
    dialogs shouldBe empty

  test("Modal: setting modalOpen=true makes the dialog appear"):
    val app = buildApp()
    app.modalOpen.set(true)
    val view = Toolkit.lower(app.root, Theme.default)
    val rendered = renderStructure(view)
    rendered should include ("Confirm delete")
    rendered should include ("Are you sure")
    val dialogs = collectAllElements(view).filter { e =>
      e.attrs.get("role").contains(AttrValue.Str("dialog"))
    }
    dialogs.length should be >= 1

  test("Modal: clicking the Cancel button flips modalOpen back to false"):
    val app = buildApp()
    app.modalOpen.set(true)
    val view = Toolkit.lower(app.root, Theme.default)
    // The dialog's button labelled "Cancel" lives inside the modal's
    // content vstack — find it by its label text.
    val cancelBtn = collectAllElements(view)
      .filter(_.tag == "button")
      .find { btn =>
        btn.children.collectFirst {
          case View.TextNode(thunk) =>
            try thunk() catch case _: Throwable => ""
        }.contains("Cancel")
      }.getOrElse(fail("expected a Cancel button inside the modal"))
    cancelBtn.events("click").asInstanceOf[EventHandler.Simple].action()
    app.modalOpen() shouldBe false

  // ─── Drawer + Tabs tests ──────────────────────────────────────

  test("Drawer: opening the drawer renders the nav links"):
    val app = buildApp()
    app.drawerOpen.set(true)
    val rendered = renderStructure(Toolkit.lower(app.root, Theme.default))
    rendered should include ("Navigation")
    rendered should include ("Home")
    rendered should include ("Users")

  test("Drawer: backdrop click closes the drawer"):
    val app = buildApp()
    app.drawerOpen.set(true)
    val view = Toolkit.lower(app.root, Theme.default)
    val backdrop = collectAllElements(view).find { e =>
      e.attrs.get("data-drawer-backdrop").contains(AttrValue.Bool(true))
    }.getOrElse(fail("expected a drawer backdrop element"))
    backdrop.events("click").asInstanceOf[EventHandler.Simple].action()
    app.drawerOpen() shouldBe false

  test("Tabs: clicking a tab button updates the active signal"):
    val app = buildApp()
    app.currentPath.set("/settings")
    val view = Toolkit.lower(app.root, Theme.default)
    val tabButtons = collectAllElements(view).filter { e =>
      e.attrs.get("role").contains(AttrValue.Str("tab"))
    }
    tabButtons.length shouldBe 3
    // Click the "billing" tab — identified by data-tab-id.
    val billingBtn = tabButtons.find { e =>
      e.attrs.get("data-tab-id").contains(AttrValue.Str("billing"))
    }.getOrElse(fail("expected a billing tab button"))
    billingBtn.events("click").asInstanceOf[EventHandler.Simple].action()
    app.activeTab() shouldBe "billing"

  // ─── Theme variants ───────────────────────────────────────────

  test("Theme: same app tree under default vs dark produces different output"):
    val app = buildApp()
    val light = renderStructure(Toolkit.lower(app.root, Theme.default))
    val dark  = renderStructure(Toolkit.lower(app.root, Theme.dark))
    // Smoke check: each theme's signature text + surface colours
    // appear in *its* rendering (headings use `colors.text`; the
    // top-bar box uses `colors.surface`).  The renderings differ
    // overall — that's the load-bearing assertion.
    light should include (Theme.default.colors.text)
    light should include (Theme.default.colors.surface)
    dark  should include (Theme.dark.colors.text)
    dark  should include (Theme.dark.colors.surface)
    light should not equal dark

  test("Theme: dark theme reaches a deep widget (avatar initials background)"):
    val app = buildApp()
    val view = Toolkit.lower(app.root, Theme.dark)
    // Find an avatar fallback span (initials) — its background is
    // the theme's primary colour.
    val avatars = collectAllElements(view).filter { e =>
      e.tag == "span" && e.attrs.get("role").contains(AttrValue.Str("img"))
    }
    avatars.length should be >= 1
    val style = avatars.head.attrs("style").asInstanceOf[AttrValue.Str].value
    style should include (Theme.dark.colors.primary)

  // ─── helpers ───────────────────────────────────────────────────

  /** Collect every `View.Element` with the given tag, depth-first.
   *  Same pattern as the helpers in `FormInputsTest` / `TableTest`. */
  private def collectTag(v: View, tag: String): Seq[View.Element] = v match
    case e @ View.Element(t, _, _, kids) =>
      val here = if t == tag then Seq(e) else Seq.empty
      here ++ kids.flatMap(collectTag(_, tag))
    case View.Fragment(kids) => kids.flatMap(collectTag(_, tag))
    case _                    => Seq.empty

  /** Collect every `View.Element` in the tree, depth-first.  Useful
   *  for searching by attribute rather than by tag. */
  private def collectAllElements(v: View): Seq[View.Element] = v match
    case e @ View.Element(_, _, _, kids) =>
      e +: kids.flatMap(collectAllElements)
    case View.Fragment(kids) => kids.flatMap(collectAllElements)
    case _                    => Seq.empty

  /** Recursively concat every attribute string + child text — for
   *  "does this rendering contain ___" smoke checks.  Matches the
   *  helper in `ToolkitTest`. */
  private def renderStructure(v: View): String = v match
    case View.Element(tag, attrs, _, kids) =>
      val attrStr = attrs.values.collect { case AttrValue.Str(s) => s }.mkString(" ")
      val kidStr  = kids.map(renderStructure).mkString(" ")
      s"$tag $attrStr $kidStr"
    case View.Fragment(kids)  => kids.map(renderStructure).mkString(" ")
    case View.TextNode(thunk) =>
      try thunk() catch case _: Throwable => ""
    case _ => ""
