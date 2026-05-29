#!/usr/bin/env scala-cli

//> using scala "3.8.3"
//> using dep "com.lihaoyi::os-lib:0.9.3"

/** Generate the ScalaScript package registry static site.
 *
 *  Usage:
 *    scala-cli run generate.sc -- [<packages-yaml>] [<output-dir>]
 *
 *  Defaults:
 *    packages-yaml = ../../registry/packages.yaml  (relative to script dir)
 *    output-dir    = ../../registry/site
 *
 *  Reads packages.yaml, generates:
 *    site/packages/{group}/{artifact}/index.json
 *    site/search-index.json
 *    site/index.html
 *
 *  See docs/arch-registry.md §3e. */

// Inline minimal versions of RegistryEntry and RegistrySiteGenerator
// so this script is self-contained and doesn't depend on the sbt build.

case class Entry(
  name:               String,
  version:            String,
  description:        String       = "",
  keywords:           List[String] = Nil,
  backends:           List[String] = Nil,
  url:                String       = "",
  license:            String       = "",
  author:             String       = "",
  homepage:           String       = "",
  changelog:          String       = "",
  scalaScriptVersion: String       = "",
  deprecated:         Boolean      = false,
)

def parseEntries(yaml: String): List[Entry] =
  // Minimal YAML list parser: each entry starts with "- name:"
  val blocks = yaml.split("(?m)^- ").toList.drop(1)
  blocks.flatMap { block =>
    def field(k: String): String =
      block.split("\n").find(_.trim.startsWith(k + ":"))
        .map(_.trim.stripPrefix(k + ":").trim.stripPrefix("\"").stripSuffix("\""))
        .getOrElse("")
    def listField(k: String): List[String] =
      block.split("\n").find(_.trim.startsWith(k + ":"))
        .map { line =>
          val v = line.trim.stripPrefix(k + ":").trim
          if v.startsWith("[") then
            v.stripPrefix("[").stripSuffix("]").split(",").map(_.trim.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty).toList
          else if v.nonEmpty then List(v) else Nil
        }.getOrElse(Nil)
    val name    = field("name")
    val version = field("version")
    if name.isEmpty || version.isEmpty then Nil
    else List(Entry(
      name               = name,
      version            = version,
      description        = field("description"),
      keywords           = listField("keywords"),
      backends           = listField("backends"),
      url                = field("url"),
      license            = field("license"),
      author             = field("author"),
      homepage           = field("homepage"),
      changelog          = field("changelog"),
      scalaScriptVersion = field("scala-script-version"),
      deprecated         = field("deprecated") == "true",
    ))
  }

def escJson(s: String): String =
  s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

def escHtml(s: String): String =
  s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

def packageJson(e: Entry): String =
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

def searchIndex(entries: List[Entry]): String =
  val docs = entries.map { e =>
    val body = List(e.name, e.description, e.keywords.mkString(" ")).filter(_.nonEmpty).mkString(" ")
    s"""  {"ref": "${escJson(e.name)}", "name": "${escJson(e.name)}", "version": "${escJson(e.version)}", "description": "${escJson(e.description)}", "body": "${escJson(body)}"}"""
  }
  "[\n" + docs.mkString(",\n") + "\n]\n"

def indexHtml(entries: List[Entry]): String =
  val totalCount = entries.length
  val tableRows = entries.sortBy(_.name).map { e =>
    val dep  = if e.deprecated then """ style="opacity:0.5"""" else ""
    val depr = if e.deprecated then " <span class=\"deprecated\">[deprecated]</span>" else ""
    s"""      <tr$dep data-name="${escHtml(e.name)}" data-desc="${escHtml(e.description)}" data-keywords="${escHtml(e.keywords.mkString(" "))}">
        <td><a href="packages/${e.name}/index.json">${escHtml(e.name)}</a>$depr</td>
        <td>${escHtml(e.version)}</td>
        <td>${escHtml(e.description)}</td>
        <td>${e.backends.mkString(", ")}</td>
      </tr>"""
  }.mkString("\n")
  s"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>ScalaScript Package Registry</title>
  <style>body{font-family:sans-serif;max-width:900px;margin:0 auto;padding:1rem}input{width:100%;padding:.5rem;font-size:1rem;margin-bottom:1rem;box-sizing:border-box}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid #eee}th{background:#f5f5f5}.deprecated{color:#999;font-size:.8em}</style>
</head>
<body>
  <h1>ScalaScript Package Registry</h1>
  <input id="search" type="search" placeholder="Search packages..." autofocus>
  <div id="count">$totalCount packages</div>
  <table><thead><tr><th>Package</th><th>Version</th><th>Description</th><th>Backends</th></tr></thead>
  <tbody id="pkg-body">
$tableRows
  </tbody></table>
  <script>
    const rows=Array.from(document.querySelectorAll('#pkg-body tr'));
    document.getElementById('search').addEventListener('input',function(){
      const q=this.value.toLowerCase();let v=0;
      rows.forEach(r=>{const show=!q||r.dataset.name.includes(q)||r.dataset.desc.includes(q)||r.dataset.keywords.includes(q);r.style.display=show?'':'none';if(show)v++;});
      document.getElementById('count').textContent=v+' package'+(v===1?'':'s');
    });
  </script>
</body>
</html>
"""

// ── Main ────────────────────────────────────────────────────────────────────

val scriptDir   = os.Path(sourcecode.File())  / os.up
val defaultYaml = scriptDir / os.up / os.up / "registry" / "packages.yaml"
val defaultOut  = scriptDir / os.up / os.up / "registry" / "site"

val args0 = args.toList
val yamlPath = args0.headOption.map(os.Path(_, os.pwd)).getOrElse(defaultYaml)
val outDir   = args0.drop(1).headOption.map(os.Path(_, os.pwd)).getOrElse(defaultOut)

if !os.exists(yamlPath) then
  System.err.println(s"generate: packages.yaml not found: $yamlPath")
  System.exit(1)

val entries = parseEntries(os.read(yamlPath))
println(s"Loaded ${entries.length} packages from ${yamlPath.last}")

os.makeDir.all(outDir / "packages")

entries.foreach { e =>
  val slash  = e.name.indexOf('/')
  val (grp, art) = if slash >= 0 then (e.name.take(slash), e.name.drop(slash + 1)) else ("unknown", e.name)
  val dir = outDir / "packages" / grp / art
  os.makeDir.all(dir)
  os.write.over(dir / "index.json", packageJson(e))
}

os.write.over(outDir / "search-index.json", searchIndex(entries))
os.write.over(outDir / "index.html", indexHtml(entries))

println(s"Generated site in ${outDir}")
println(s"  site/index.html")
println(s"  site/search-index.json")
println(s"  site/packages/ (${entries.length} packages)")
