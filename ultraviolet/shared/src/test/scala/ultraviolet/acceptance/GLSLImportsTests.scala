package ultraviolet.acceptance

import ultraviolet.DebugAST
import ultraviolet.syntax.*

class GLSLImportsTests extends munit.FunSuite {

  @SuppressWarnings(Array("scalafix:DisableSyntax.var"))
  case class FragEnv(UV: vec2, var COLOR: vec4)

  test("imports") {
    import Importable.*

    inline def fragment: Shader[FragEnv, Int] =
      Shader { _ =>
        val value = 10
        addOneAnon(value)
        addOneInline(value)
      }

    val actual =
      fragment.toGLSL[WebGL2].toOutput.code

    // DebugAST.toAST(fragment)
    // println(actual)

    assertEquals(
      actual,
      s"""
      |int def0(in int i){
      |  return i+1;
      |}
      |int value=10;
      |def0(value);
      |value+1;
      |""".stripMargin.trim
    )
  }

}

object Importable:

  inline def addOneAnon                = (i: Int) => i + 1
  inline def addOneInline(i: Int): Int = i + 1
