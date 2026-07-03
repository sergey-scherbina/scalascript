package scalascript.imports

/** Generates the ScalaScript package registry static site.
 *
 *  Given a list of `RegistryEntry` values, produces:
 *  - `site/packages.yaml` — registry catalog served at the CLI default URL
 *  - `site/packages/{group}/{artifact}/index.json` — per-package metadata
 *  - `site/search-index.json` — lunr.js-compatible search index
 *  - `site/index.html` — self-contained searchable HTML page
 *
 *  Invoked by `tools/registry-site/generate.sc` (scala-cli) and by tests.
 *
 *  See `specs/arch-registry.md §3e`. */
object RegistrySiteGenerator:

  /** Generate the full site under `outputDir`.  Creates all directories. */
  def generate(entries: List[RegistryEntry], outputDir: os.Path): Unit =
    os.makeDir.all(outputDir / "packages")
    os.write.over(outputDir / "packages.yaml", RegistryEntry.toYaml(entries))
    entries.foreach { e => writePackageJson(e, outputDir) }
    writeSearchIndex(entries, outputDir)
    writeIndexHtml(entries, outputDir)

  private def writePackageJson(e: RegistryEntry, outputDir: os.Path): Unit =
    val (group, artifact) = splitName(e.name)
    val dir = outputDir / "packages" / group / artifact
    os.makeDir.all(dir)
    os.write.over(dir / "index.json", packageJson(e))

  /** JSON representation of a single package — machine-readable. */
  def packageJson(e: RegistryEntry): String =
    val fields = List.newBuilder[String]
    fields += s"""  "name": "${escJson(e.name)}""""
    fields += s"""  "version": "${escJson(e.version)}""""
    if e.description.nonEmpty     then fields += s"""  "description": "${escJson(e.description)}""""
    if e.keywords.nonEmpty        then fields += s"""  "keywords": [${e.keywords.map(k => s""""${escJson(k)}"""").mkString(", ")}]"""
    if e.backends.nonEmpty        then fields += s"""  "backends": [${e.backends.map(b => s""""$b"""").mkString(", ")}]"""
    if e.url.nonEmpty             then fields += s"""  "url": "${escJson(e.url)}""""
    if e.license.nonEmpty         then fields += s"""  "license": "${escJson(e.license)}""""
    if e.author.nonEmpty          then fields += s"""  "author": "${escJson(e.author)}""""
    if e.homepage.nonEmpty        then fields += s"""  "homepage": "${escJson(e.homepage)}""""
    if e.changelog.nonEmpty       then fields += s"""  "changelog": "${escJson(e.changelog)}""""
    if e.scalaScriptVersion.nonEmpty then fields += s"""  "scalaScriptVersion": "${escJson(e.scalaScriptVersion)}""""
    if e.deprecated               then fields += s"""  "deprecated": true"""
    fields += s"""  "install": "dep:${escJson(e.name)}:${escJson(e.version)}""""
    "{\n" + fields.result().mkString(",\n") + "\n}\n"

  /** lunr.js-compatible search index: list of documents with `ref` and `body` fields.
   *  Each document contains name, description, and keywords concatenated for indexing. */
  def searchIndex(entries: List[RegistryEntry]): String =
    val docs = entries.map { e =>
      val body = List(e.name, e.description, e.keywords.mkString(" "), e.backends.mkString(" "))
        .filter(_.nonEmpty).mkString(" ")
      s"""  {"ref": "${escJson(e.name)}", "name": "${escJson(e.name)}", "version": "${escJson(e.version)}", "description": "${escJson(e.description)}", "body": "${escJson(body)}"}"""
    }
    "[\n" + docs.mkString(",\n") + "\n]\n"

  private def writeSearchIndex(entries: List[RegistryEntry], outputDir: os.Path): Unit =
    os.write.over(outputDir / "search-index.json", searchIndex(entries))

  private def writeIndexHtml(entries: List[RegistryEntry], outputDir: os.Path): Unit =
    os.write.over(outputDir / "index.html", indexHtml(entries))

  /** Self-contained HTML page with embedded JS that loads search-index.json
   *  and renders results client-side. */
  def indexHtml(entries: List[RegistryEntry]): String =
    val totalCount = entries.length
    val tableRows  = entries.sortBy(_.name).map { e =>
      val dep = if e.deprecated then """ style="opacity:0.5"""" else ""
      val desc = escHtml(e.description)
      val backs = e.backends.mkString(", ")
      val depr = if e.deprecated then " <span class=\"deprecated\">[deprecated]</span>" else ""
      s"""      <tr$dep data-name="${escHtml(e.name)}" data-desc="${escHtml(e.description)}" data-keywords="${escHtml(e.keywords.mkString(" "))}">
        <td><a href="packages/${escHtml(e.name.replace('/', '/'))}/index.json">${escHtml(e.name)}</a>$depr</td>
        <td>${escHtml(e.version)}</td>
        <td>$desc</td>
        <td>$backs</td>
      </tr>"""
    }.mkString("\n")

    s"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>ScalaScript Package Registry</title>
  <style>
    body { font-family: sans-serif; max-width: 900px; margin: 0 auto; padding: 1rem; }
    h1 { color: #333; }
    input { width: 100%; padding: .5rem; font-size: 1rem; margin-bottom: 1rem; box-sizing: border-box; }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: .4rem .6rem; border-bottom: 1px solid #eee; }
    th { background: #f5f5f5; }
    .deprecated { color: #999; font-size: .8em; }
    #count { color: #666; font-size: .9em; margin-bottom: .5rem; }
  </style>
</head>
<body>
  <h1>ScalaScript Package Registry</h1>
  <input id="search" type="search" placeholder="Search packages..." autofocus>
  <div id="count">$totalCount packages</div>
  <table id="pkg-table">
    <thead><tr><th>Package</th><th>Version</th><th>Description</th><th>Backends</th></tr></thead>
    <tbody id="pkg-body">
$tableRows
    </tbody>
  </table>
  <script>
    const rows = Array.from(document.querySelectorAll('#pkg-body tr'));
    document.getElementById('search').addEventListener('input', function() {
      const q = this.value.toLowerCase();
      let visible = 0;
      rows.forEach(function(row) {
        const name = row.dataset.name || '';
        const desc = row.dataset.desc || '';
        const kw   = row.dataset.keywords || '';
        const show = !q || name.includes(q) || desc.includes(q) || kw.includes(q);
        row.style.display = show ? '' : 'none';
        if (show) visible++;
      });
      document.getElementById('count').textContent = visible + ' package' + (visible === 1 ? '' : 's');
    });
  </script>
</body>
</html>
"""

  private def splitName(name: String): (String, String) =
    val slash = name.indexOf('/')
    if slash >= 0 then (name.substring(0, slash), name.substring(slash + 1))
    else ("unknown", name)

  private def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

  private def escHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
