package scalascript.codegen

/** Per-host library packaging for the **Java** host (Task B, `specs/polyglot-libraries.md` §4) — the
 *  counterpart of `JsLibPackager` / `JvmLibPackager` / `RustLibPackager`. Packages the pure **optics**
 *  feature as a standalone, dependency-free Java/Maven project with no ScalaScript dependency.
 *
 *  Like the JVM (Scala) and Rust libraries, this is a **native, dependency-free Java optics
 *  implementation** over a dynamic value (`Map<String,Object>` objects, `List<Object>` arrays,
 *  `Optional<Object>`, and `"_type"`-tagged sum variants), faithful to the four optic shapes
 *  (Lens / Optional / Traversal / Prism) of the JS `@scalascript/optics` package. */
object JavaLibPackager:

  /** The self-contained Java optics source — no ScalaScript / external dependencies (Java 17+). */
  val opticsJavaSource: String =
    """package ssc.optics;
      |
      |import java.util.*;
      |import java.util.function.Function;
      |
      |/** Composable optics (Lens / Optional / Traversal / Prism) over dynamic JSON-like values:
      | *  {@code Map<String,Object>} objects, {@code List<Object>} arrays, {@code Optional<Object>},
      | *  and {@code "_type"}-tagged sum variants. The Java port of the {@code @scalascript/optics}
      | *  package; no ScalaScript dependency. */
      |public final class Optics {
      |    private Optics() {}
      |
      |    // ── Path steps ──────────────────────────────────────────────────────────
      |    public sealed interface Step permits FieldStep, IndexStep, AtStep, SomeStep, EachStep {}
      |    public record FieldStep(String name) implements Step {}
      |    public record IndexStep(int i)       implements Step {}
      |    public record AtStep(Object key)     implements Step {}
      |    public record SomeStep()             implements Step {}
      |    public record EachStep()             implements Step {}
      |
      |    public static Step field(String name) { return new FieldStep(name); }
      |    public static Step index(int i)       { return new IndexStep(i); }
      |    public static Step at(Object key)     { return new AtStep(key); }
      |    public static Step some()             { return new SomeStep(); }
      |    public static Step each()             { return new EachStep(); }
      |
      |    @SuppressWarnings("unchecked")
      |    private static Map<String, Object> asObj(Object v) { return (Map<String, Object>) v; }
      |    @SuppressWarnings("unchecked")
      |    private static Map<Object, Object> asMap(Object v) { return (Map<Object, Object>) v; }
      |    @SuppressWarnings("unchecked")
      |    private static List<Object> asList(Object v) { return (List<Object>) v; }
      |    @SuppressWarnings("unchecked")
      |    private static Optional<Object> asOpt(Object v) { return (Optional<Object>) v; }
      |
      |    // ── Lens ────────────────────────────────────────────────────────────────
      |    public static final class Lens {
      |        public final List<String> path;
      |        public Lens(List<String> path) { this.path = path; }
      |        public Object get(Object s) {
      |            Object cur = s;
      |            for (String k : path) cur = (cur instanceof Map) ? asObj(cur).get(k) : null;
      |            return cur;
      |        }
      |        public Object set(Object s, Object v) { return setPath(path, s, v); }
      |        public Object modify(Object s, Function<Object, Object> f) { return set(s, f.apply(get(s))); }
      |        public Lens andThen(Lens other) {
      |            List<String> p = new ArrayList<>(path); p.addAll(other.path); return new Lens(p);
      |        }
      |    }
      |    public static Lens makeLens(List<String> path) { return new Lens(path); }
      |
      |    private static Object setPath(List<String> path, Object s, Object v) {
      |        if (path.isEmpty()) return v;
      |        if (!(s instanceof Map)) return s;
      |        String h = path.get(0);
      |        Map<String, Object> m = new LinkedHashMap<>(asObj(s));
      |        m.put(h, setPath(path.subList(1, path.size()), asObj(s).get(h), v));
      |        return m;
      |    }
      |
      |    // ── Optional ────────────────────────────────────────────────────────────
      |    public static final class Optional_ {
      |        public final List<Step> steps;
      |        public Optional_(List<Step> steps) { this.steps = steps; }
      |        public Optional<Object> getOption(Object s) { return getOpt(steps, s); }
      |        public Object set(Object s, Object v) { return setOpt(steps, s, v); }
      |        public Object modify(Object s, Function<Object, Object> f) {
      |            Optional<Object> a = getOption(s);
      |            return a.isPresent() ? set(s, f.apply(a.get())) : s;
      |        }
      |        public Optional_ andThen(Optional_ other) {
      |            List<Step> st = new ArrayList<>(steps); st.addAll(other.steps); return new Optional_(st);
      |        }
      |    }
      |    public static Optional_ makeOptional(List<Step> steps) { return new Optional_(steps); }
      |
      |    private static Optional<Object> getOpt(List<Step> steps, Object s) {
      |        if (steps.isEmpty()) return Optional.of(s);
      |        Step st = steps.get(0); List<Step> rest = steps.subList(1, steps.size());
      |        if (st instanceof FieldStep f) {
      |            if (s instanceof Map && asObj(s).containsKey(f.name())) return getOpt(rest, asObj(s).get(f.name()));
      |            return Optional.empty();
      |        } else if (st instanceof AtStep a) {
      |            if (s instanceof Map && asMap(s).containsKey(a.key())) return getOpt(rest, asMap(s).get(a.key()));
      |            return Optional.empty();
      |        } else if (st instanceof IndexStep ix) {
      |            if (s instanceof List && ix.i() >= 0 && ix.i() < asList(s).size()) return getOpt(rest, asList(s).get(ix.i()));
      |            return Optional.empty();
      |        } else if (st instanceof SomeStep) {
      |            if (s instanceof Optional && asOpt(s).isPresent()) return getOpt(rest, asOpt(s).get());
      |            return Optional.empty();
      |        }
      |        return Optional.empty();  // EachStep
      |    }
      |
      |    private static Object setOpt(List<Step> steps, Object s, Object v) {
      |        if (steps.isEmpty()) return v;
      |        Step st = steps.get(0); List<Step> rest = steps.subList(1, steps.size());
      |        if (st instanceof FieldStep f) {
      |            if (s instanceof Map && asObj(s).containsKey(f.name())) {
      |                Map<String, Object> m = new LinkedHashMap<>(asObj(s));
      |                m.put(f.name(), setOpt(rest, asObj(s).get(f.name()), v)); return m;
      |            }
      |            return s;
      |        } else if (st instanceof AtStep a) {
      |            if (s instanceof Map && asMap(s).containsKey(a.key())) {
      |                Map<Object, Object> m = new LinkedHashMap<>(asMap(s));
      |                m.put(a.key(), setOpt(rest, asMap(s).get(a.key()), v)); return m;
      |            }
      |            return s;
      |        } else if (st instanceof IndexStep ix) {
      |            if (s instanceof List && ix.i() >= 0 && ix.i() < asList(s).size()) {
      |                List<Object> l = new ArrayList<>(asList(s));
      |                l.set(ix.i(), setOpt(rest, asList(s).get(ix.i()), v)); return l;
      |            }
      |            return s;
      |        } else if (st instanceof SomeStep) {
      |            if (s instanceof Optional && asOpt(s).isPresent()) return Optional.of(setOpt(rest, asOpt(s).get(), v));
      |            return s;
      |        }
      |        return s;  // EachStep
      |    }
      |
      |    // ── Traversal ───────────────────────────────────────────────────────────
      |    public static final class Traversal {
      |        public final List<Step> steps;
      |        public Traversal(List<Step> steps) { this.steps = steps; }
      |        public List<Object> getAll(Object s) { return Optics.getAll(steps, s); }
      |        public Object modify(Object s, Function<Object, Object> f) { return modAll(steps, s, f); }
      |        public Object set(Object s, Object v) { return modify(s, x -> v); }
      |        public Traversal andThen(Traversal other) {
      |            List<Step> st = new ArrayList<>(steps); st.addAll(other.steps); return new Traversal(st);
      |        }
      |    }
      |    public static Traversal makeTraversal(List<Step> steps) { return new Traversal(steps); }
      |
      |    private static List<Object> getAll(List<Step> steps, Object s) {
      |        if (steps.isEmpty()) { List<Object> r = new ArrayList<>(); r.add(s); return r; }
      |        Step st = steps.get(0); List<Step> rest = steps.subList(1, steps.size());
      |        if (st instanceof FieldStep f) {
      |            if (s instanceof Map && asObj(s).containsKey(f.name())) return getAll(rest, asObj(s).get(f.name()));
      |        } else if (st instanceof AtStep a) {
      |            if (s instanceof Map && asMap(s).containsKey(a.key())) return getAll(rest, asMap(s).get(a.key()));
      |        } else if (st instanceof IndexStep ix) {
      |            if (s instanceof List && ix.i() >= 0 && ix.i() < asList(s).size()) return getAll(rest, asList(s).get(ix.i()));
      |        } else if (st instanceof SomeStep) {
      |            if (s instanceof Optional && asOpt(s).isPresent()) return getAll(rest, asOpt(s).get());
      |        } else if (st instanceof EachStep) {
      |            if (s instanceof List) {
      |                List<Object> out = new ArrayList<>();
      |                for (Object item : asList(s)) out.addAll(getAll(rest, item));
      |                return out;
      |            }
      |        }
      |        return new ArrayList<>();
      |    }
      |
      |    private static Object modAll(List<Step> steps, Object s, Function<Object, Object> f) {
      |        if (steps.isEmpty()) return f.apply(s);
      |        Step st = steps.get(0); List<Step> rest = steps.subList(1, steps.size());
      |        if (st instanceof FieldStep fs) {
      |            if (s instanceof Map && asObj(s).containsKey(fs.name())) {
      |                Map<String, Object> m = new LinkedHashMap<>(asObj(s));
      |                m.put(fs.name(), modAll(rest, asObj(s).get(fs.name()), f)); return m;
      |            }
      |        } else if (st instanceof AtStep a) {
      |            if (s instanceof Map && asMap(s).containsKey(a.key())) {
      |                Map<Object, Object> m = new LinkedHashMap<>(asMap(s));
      |                m.put(a.key(), modAll(rest, asMap(s).get(a.key()), f)); return m;
      |            }
      |        } else if (st instanceof IndexStep ix) {
      |            if (s instanceof List && ix.i() >= 0 && ix.i() < asList(s).size()) {
      |                List<Object> l = new ArrayList<>(asList(s));
      |                l.set(ix.i(), modAll(rest, asList(s).get(ix.i()), f)); return l;
      |            }
      |        } else if (st instanceof SomeStep) {
      |            if (s instanceof Optional && asOpt(s).isPresent()) return Optional.of(modAll(rest, asOpt(s).get(), f));
      |        } else if (st instanceof EachStep) {
      |            if (s instanceof List) {
      |                List<Object> out = new ArrayList<>();
      |                for (Object item : asList(s)) out.add(modAll(rest, item, f));
      |                return out;
      |            }
      |        }
      |        return s;
      |    }
      |
      |    // ── Prism ───────────────────────────────────────────────────────────────
      |    public static final class Prism {
      |        public final String variant;
      |        public Prism(String variant) { this.variant = variant; }
      |        private boolean matches(Object s) {
      |            return s instanceof Map && variant.equals(asObj(s).get("_type"));
      |        }
      |        public Optional<Object> getOption(Object s) { return matches(s) ? Optional.of(s) : Optional.empty(); }
      |        public Object reverseGet(Object v) { return v; }
      |        public Object set(Object s, Object v) { return matches(s) ? v : s; }
      |        public Object modify(Object s, Function<Object, Object> f) { return matches(s) ? f.apply(s) : s; }
      |    }
      |    public static Prism makePrism(String variant) { return new Prism(variant); }
      |}
      |""".stripMargin

  def opticsPomXml(version: String): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<project xmlns="http://maven.apache.org/POM/4.0.0"
       |         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       |         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
       |  <modelVersion>4.0.0</modelVersion>
       |  <groupId>io.scalascript</groupId>
       |  <artifactId>ssc-optics</artifactId>
       |  <version>$version</version>
       |  <packaging>jar</packaging>
       |  <description>Composable optics (Lens/Optional/Traversal/Prism) over dynamic JSON-like values — the Java port of @scalascript/optics.</description>
       |  <properties>
       |    <maven.compiler.release>17</maven.compiler.release>
       |    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
       |  </properties>
       |</project>
       |""".stripMargin

  val opticsReadme: String =
    """# ssc-optics (Java)
      |
      |Composable **optics** — Lens / Optional / Traversal / Prism — over dynamic JSON-like values
      |(`Map<String,Object>` / `List<Object>` / `Optional<Object>` / `String` / `Integer` / `Boolean` /
      |`null`, with `"_type"`-tagged sum variants). The Java port of `@scalascript/optics`; no
      |ScalaScript dependency. Java 17+.
      |
      |```java
      |import ssc.optics.Optics;
      |import java.util.*;
      |
      |var inner = new LinkedHashMap<String,Object>(); inner.put("b", 5);
      |var outer = new LinkedHashMap<String,Object>(); outer.put("a", inner);
      |var l = Optics.makeLens(List.of("a", "b"));
      |l.get(outer);            // 5
      |l.set(outer, 9);         // { a: { b: 9 } }  (immutable)
      |```
      |
      |Build: `mvn package` (or just `javac src/main/java/ssc/optics/Optics.java`).
      |""".stripMargin

  /** The complete buildable Java/Maven project as `relative-path -> content`. */
  def opticsJavaPackage(version: String): Map[String, String] = Map(
    "pom.xml"                            -> opticsPomXml(version),
    "src/main/java/ssc/optics/Optics.java" -> opticsJavaSource,
    "README.md"                          -> opticsReadme,
  )
