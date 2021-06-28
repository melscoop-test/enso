package org.enso.compiler.test.pass.resolve

import org.enso.compiler.context.{InlineContext, ModuleContext}
import org.enso.compiler.core.IR
import org.enso.compiler.pass.resolve.GenerateDocumentation
import org.enso.compiler.pass.{PassConfiguration, PassManager}
import org.enso.compiler.test.CompilerTest
import org.scalatest.Inside

class GenerateDocumentationTest extends CompilerTest with Inside {

  // === Test Setup ===========================================================

  val passConfig: PassConfiguration = PassConfiguration()

  implicit val passManager: PassManager =
    new PassManager(List(), passConfig)

  /** Resolves documentation comments in a module.
    *
    * @param ir the module
    */
  implicit class ResolveModule(ir: IR.Module) {

    /** Resolves documentation comments for [[ir]].
      *
      * @param moduleContext the context in which to resolve
      * @return [[ir]], with documentation resolved
      */
    def resolve(implicit moduleContext: ModuleContext): IR.Module = {
      GenerateDocumentation.runModule(ir, moduleContext)
    }
  }

  /** Resolves documentation comments in an expression.
    *
    * @param ir the expression
    */
  implicit class ResolveExpression(ir: IR.Expression) {

    /** Resolves documentation comments for [[ir]].
      *
      * @param inlineContext the context in which to resolve
      * @return [[ir]], with documentation resolved
      */
    def resolve(implicit inlineContext: InlineContext): IR.Expression = {
      GenerateDocumentation.runExpression(ir, inlineContext)
    }
  }

  /** Creates a defaulted module context.
    *
    * @return a defaulted module context
    */
  def mkModuleContext: ModuleContext = {
    buildModuleContext()
  }

  /** Creates a defaulted inline context.
    *
    * @return a defaulted inline context
    */
  def mkInlineContext: InlineContext = {
    buildInlineContext()
  }

  /** Gets documentation metadata from a node.
    * Throws an exception if missing.
    *
    * @param ir the ir to get the doc from.
    * @return the doc assigned to `ir`.
    */
  def getDoc(ir: IR): String = {
    val meta = ir.getMetadata(GenerateDocumentation)
    meta shouldBe defined
    meta.get.documentation
  }

  def docGenGetAssertion(inner: String): String =
    s"""<html>
       | <body>
       |   <div class="doc" style="font-size: 13px;">
       |     <div>
       |       <div class="">
       |         <div class="example">
       |           <div class="summary">$inner</div>
       |         </div>
       |       </div>
       |     </div>
       |   </div>
       | </body>
       |</html>""".stripMargin
      .replaceAll(System.lineSeparator(), "")
      .replaceAll(">[ ]+<", "><")

  // === The Tests ============================================================

  "Documentation comments in the top scope" should {
    "be associated with atoms and methods" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """
          |## This is doc for My_Atom
          |type My_Atom a b c
          |
          |## This is doc for my_method
          |MyAtom.my_method x = x + this
          |
          |""".stripMargin.preprocessModule.resolve

      ir.bindings.length shouldEqual 2
      ir.bindings(0) shouldBe an[IR.Module.Scope.Definition.Atom]
      ir.bindings(1) shouldBe an[IR.Module.Scope.Definition.Method]

      getDoc(
        ir.bindings(0)
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;This is doc for My&lt;&lt;Atom&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      getDoc(
        ir.bindings(1)
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;This is doc for my&lt;&lt;method&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;"
      )
    }
  }

  "Documentation comments in blocks" should {
    "be associated with the documented expression in expression flow" in {
      implicit val inlineContext: InlineContext = mkInlineContext
      val ir =
        """
          |x -> y ->
          |    ## Do thing
          |    x + y
          |    ## Do another thing
          |    z = x * y
          |""".stripMargin.preprocessExpression.get.resolve
      val body = ir
        .asInstanceOf[IR.Function.Lambda]
        .body
        .asInstanceOf[IR.Function.Lambda]
        .body
        .asInstanceOf[IR.Expression.Block]

      body.expressions.length shouldEqual 1
      getDoc(
        body.expressions(0)
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;Do thing&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      getDoc(
        body.returnValue
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;Do another thing&lt;&lt;&lt;&lt;&lt;&lt;"
      )
    }

    "be associated with the documented expression in module flow" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """
          |method x =
          |    ## Do thing
          |    x + y
          |    ## Do another thing
          |    z = x * y
          |""".stripMargin.preprocessModule.resolve
      val body = ir
        .bindings(0)
        .asInstanceOf[IR.Module.Scope.Definition.Method.Binding]
        .body
        .asInstanceOf[IR.Expression.Block]

      body.expressions.length shouldEqual 1
      getDoc(
        body.expressions(0)
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;Do thing&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      getDoc(
        body.returnValue
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;Do another thing&lt;&lt;&lt;&lt;&lt;&lt;"
      )
    }

    "be associated with the type ascriptions" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """
          |method x =
          |    ## Id
          |    f : Any -> Any
          |    f x = x
          |
          |    ## Return thing
          |    f 1
          |""".stripMargin.preprocessModule.resolve
      val body = ir
        .bindings(0)
        .asInstanceOf[IR.Module.Scope.Definition.Method.Binding]
        .body
        .asInstanceOf[IR.Expression.Block]

      body.expressions.length shouldEqual 2
      body.expressions(0) shouldBe an[IR.Application.Operator.Binary]
      getDoc(
        body.expressions(0)
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;Id&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      getDoc(
        body.returnValue
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;Return thing&lt;&lt;&lt;&lt;&lt;&lt;"
      )
    }
  }

  "Documentation in complex type definitions" should {
    implicit val moduleContext: ModuleContext = mkModuleContext
    "assign docs to all entities" in {
      val ir =
        """
          |## the type Foo
          |type Foo
          |    ## the constructor Bar
          |    type Bar
          |
          |    ## the included Unit
          |    Unit
          |
          |    ## a method
          |    foo x =
          |        ## a statement
          |        IO.println "foo"
          |        ## the return
          |        0
          |""".stripMargin.preprocessModule.resolve
      val tp = ir.bindings(0).asInstanceOf[IR.Module.Scope.Definition.Type]
      getDoc(
        tp
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;the type Foo&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      val t1 = tp.body(0)
      getDoc(
        t1
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;the constructor Bar&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      val t2 = tp.body(1)
      getDoc(
        t2
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;the included Unit&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      val method = tp.body(2).asInstanceOf[IR.Function.Binding]
      getDoc(
        method
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;a method&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      val block = method.body.asInstanceOf[IR.Expression.Block]
      getDoc(
        block.expressions(0)
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;a statement&lt;&lt;&lt;&lt;&lt;&lt;"
      )
      getDoc(
        block.returnValue
      ) shouldEqual docGenGetAssertion(
        "&lt;&lt;&lt;&lt;&lt;&lt;the return&lt;&lt;&lt;&lt;&lt;&lt;"
      )
    }
  }
}