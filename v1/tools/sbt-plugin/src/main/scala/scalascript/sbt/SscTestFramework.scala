package scalascript.sbt

import sbt._

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object SscTestFramework {
  def parseJUnitXml(resultsDir: File, log: Logger): TestResult = {
    val files = (resultsDir ** "*.xml").get().filter(_.isFile)
    if (files.isEmpty) {
      log.error(s"[ssc] no JUnit XML files found in $resultsDir")
      TestResult.Error
    } else {
      val summary = files.foldLeft(Summary.empty) { (acc, file) =>
        acc + parseFile(file)
      }
      log.info(
        s"[ssc] tests=${summary.tests}, failures=${summary.failures}, errors=${summary.errors}, skipped=${summary.skipped}"
      )
      if (summary.errors > 0) TestResult.Error
      else if (summary.failures > 0) TestResult.Failed
      else TestResult.Passed
    }
  }

  private final case class Summary(tests: Int, failures: Int, errors: Int, skipped: Int) {
    def +(other: Summary): Summary =
      Summary(
        tests = tests + other.tests,
        failures = failures + other.failures,
        errors = errors + other.errors,
        skipped = skipped + other.skipped
      )
  }

  private object Summary {
    val empty: Summary = Summary(0, 0, 0, 0)
  }

  private def parseFile(file: File): Summary = {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setXIncludeAware(false)
    factory.setExpandEntityReferences(false)

    val doc = factory.newDocumentBuilder().parse(file)
    val suites = doc.getElementsByTagName("testsuite")
    if (suites.getLength == 0) {
      doc.getDocumentElement match {
        case elem: Element => summary(elem)
        case _             => Summary.empty
      }
    } else {
      var acc = Summary.empty
      var i = 0
      while (i < suites.getLength) {
        acc += summary(suites.item(i).asInstanceOf[Element])
        i += 1
      }
      acc
    }
  }

  private def summary(elem: Element): Summary =
    Summary(
      tests = intAttr(elem, "tests"),
      failures = intAttr(elem, "failures"),
      errors = intAttr(elem, "errors"),
      skipped = intAttr(elem, "skipped")
    )

  private def intAttr(elem: Element, name: String): Int = {
    val value = elem.getAttribute(name)
    if (value == null || value.isEmpty) 0 else value.toInt
  }
}
