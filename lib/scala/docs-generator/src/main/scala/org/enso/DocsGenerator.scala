package org.enso

import java.io._

import scala.util.Using
import scala.io.Source
import org.enso.syntax.text.{DocParser, DocParserMain, Parser}
import scalatags.Text.{all => HTML}
import HTML._

object TreeOfCommonPrefixes {
  case class Node(name: String, var elems: List[Node]) {
    def html(beg: String = ""): Modifier = {
      val newBeg = if (beg.length > 0) beg + "-" + name else name
      if (elems.isEmpty) {
        HTML.li(HTML.a(HTML.href := newBeg)(name))
      } else {
        HTML.li(HTML.`class` := "section")(
          HTML.input(HTML.`type` := "checkbox", HTML.id := newBeg),
          HTML.label(HTML.`for` := newBeg)(
            HTML.a(HTML.href := newBeg)(name)
          ),
          elems.map(x => HTML.ul(x.html(newBeg)))
        )
      }
    }
  }

  def groupNodesByPrefix(le: List[Node]): List[Node] =
    groupByPrefix(le.map(_.name))

  def groupByPrefix(ls: List[String]): List[Node] = {
    var nodes = List[Node]()
    for (string <- ls) {
      if (string.split('/').length <= 1) {
        nodes = nodes :+ Node(string, List())
      } else {
        val arr      = string.split('/')
        val filtered = nodes.filter(x => x.name == arr.head)
        if (filtered.nonEmpty && nodes.contains(filtered.head)) {
          nodes.map(n =>
            if (n == filtered.head) {
              n.elems = n.elems :+ Node(arr.tail.mkString("/"), List())
            }
          )
        } else {
          nodes =
            nodes :+ Node(arr.head, List(Node(arr.tail.mkString("/"), List())))
        }
      }
    }
    for (node <- nodes) {
      node.elems = groupNodesByPrefix(node.elems)
    }
    nodes
  }
}

object DocsGenerator {

  def generate(program: String): String = {
    val parser   = new Parser()
    val module   = parser.run(program)
    val dropMeta = parser.dropMacroMeta(module)
    val doc      = DocParser.DocParserRunner.createDocs(dropMeta)
    val code =
      DocParser.DocParserHTMLGenerator.generateHTMLForEveryDocumented(doc)
    code
  }

  def generatePure(comment: String): String = {
    val doc  = DocParserMain.runMatched(comment)
    val html = DocParser.DocParserHTMLGenerator.generateHTMLPureDoc(doc)
    html
  }

  def mapIfEmpty(doc: String): String = {
    var tmp = doc
    if (doc.replace("<div>", "").replace("</div>", "").length == 0) {
      tmp =
        "\n\n*Enso Reference Viewer.*\n\nNo documentation available for chosen source file."
      tmp = generatePure(tmp).replace("style=\"font-size: 13px;\"", "")
    }
    tmp
  }

  def removeUnnecessaryDivs(doc: String): String = {
    var tmp = doc
    while (tmp.contains("<div></div>"))
      tmp = tmp.replace("<div></div>", "")
    tmp
  }

  def traverse(root: File): LazyList[File] =
    if (!root.exists) LazyList.empty
    else
      LazyList.apply(root) ++ (root.listFiles match {
        case null  => LazyList.empty
        case files => files.view.flatMap(traverse)
      })
}

object DocsGeneratorMain extends App {
  import DocsGenerator._
  import TreeOfCommonPrefixes._

  /// Files
  val path = "./distribution/std-lib/Standard/src"
  val allFiles = traverse(new File(path))
    .filter(f => f.isFile && f.getName.endsWith(".enso"))
  val allFileNames =
    allFiles.map(_.getPath.replace(path + "/", "").replace(".enso", ""))
  val allPrograms =
    allFiles
      .map(f => Using(Source.fromFile(f, "UTF-8")) { s => s.mkString })
      .toList

  /// HTML's w/o react & tree (for IDE)
  val allDocs =
    allPrograms
      .map(s => generate(s.getOrElse("")))
      .map(mapIfEmpty)
      .map(removeUnnecessaryDivs)
  val allDocFiles = allFiles.map(
    _.getPath.replace(".enso", ".html").replace("Standard/src", "docs")
  )
  val zipped = allDocFiles.zip(allDocs)
  zipped.foreach(x => {
    val file = new File(x._1)
    val dir  = new File(x._1.replaceAll("\\/[a-zA-Z_]*\\.[a-zA-Z]*", ""))
    dir.mkdirs()
    file.createNewFile();
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(x._2)
    bw.close()
  })

  /// HTML's for syntax website.
  val treeNames = groupByPrefix(allFileNames.toList).filter(_.elems.nonEmpty)
  val jsTemplate = new File(
    "./lib/scala/docs-generator/src/main/scala/org/enso/docsgenerator/template.js"
  )
  val templateCode = Using(Source.fromFile(jsTemplate, "UTF-8")) { s =>
    s.mkString
  }
  val treeStyleFile = new File(
    "./lib/scala/docs-generator/src/main/scala/org/enso/docsgenerator/treeStyle.css"
  )
  val treeStyleCode = Using(Source.fromFile(treeStyleFile, "UTF-8")) { s =>
    s.mkString
  }
  val treeStyle = "<style jsx>{`" + treeStyleCode.getOrElse("") + "`}</style>"
  val allDocJSFiles = allFiles.map { x =>
    val outDir = "docs-js"
    val name = x.getPath
      .replace(".enso", ".js")
      .replace("Standard/src", outDir)
      .replace("Main.js", "index.js")
    val ending = name.split(outDir + "/").tail.head
    name.replace(ending, ending.replace('/', '-'))
  }
  val dir = new File(allDocJSFiles.head.split("docs-js").head + "docs-js/")
  dir.mkdirs()
  val zippedJS = allDocJSFiles.zip(allDocs)
  zippedJS.foreach(x => {
    val file = new File(x._1)
    file.createNewFile();
    val bw = new BufferedWriter(new FileWriter(file))
    var treeCode =
      "<div>" + treeStyle + HTML
        .ul(
          treeNames.map(_.html())
        )
        .render
        .replace("class=", "className=") + "</div>"
    if (x._1.contains("/index.js")) {
      treeCode = treeCode.replace("a href=\"", "a href=\"reference/")
    }
    val partials =
      x._1.split("docs-js/").tail.head.replace(".js", "").split("-")
    for (i <- 1 to partials.length) {
      val id  = partials.take(i).mkString("-")
      val beg = "<input type=\"checkbox\" id=\"" + id + "\" "
      treeCode = treeCode.replace(beg, beg + "checked=\"True\"")
    }
    bw.write(
      templateCode
        .getOrElse("")
        .replace(
          "{/*PAGE*/}",
          x._2
            .replace("doc-copy-btn flex", "doc-copy-btn none")
            .replace("{", "&#123;")
            .replace("}", "&#125;")
        )
        .replace("{/*BREADCRUMBS*/}", treeCode)
    )
    bw.close()
  })
}