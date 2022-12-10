package ultraviolet.datatypes

import ShaderAST.*

trait ShaderPrinter[T]:
  def isValid(
      inType: Option[String],
      outType: Option[String],
      headers: List[ShaderAST],
      functions: List[ShaderAST],
      body: ShaderAST
  ): ShaderValid
  def transformer: PartialFunction[ShaderAST, ShaderAST]
  def printer: PartialFunction[ShaderAST, List[String]]

object ShaderPrinter:

  sealed trait WebGL1
  sealed trait WebGL2

  // A number of the transforms seen in the WebGL1 & 2 printers below are based
  // on this page: https://webgl2fundamentals.org/webgl/lessons/webgl1-to-webgl2.html

  given ShaderPrinter[WebGL1] = new ShaderPrinter:
    def isValid(
        inType: Option[String],
        outType: Option[String],
        headers: List[ShaderAST],
        functions: List[ShaderAST],
        body: ShaderAST
    ): ShaderValid = ShaderValid.Valid

    def transformer: PartialFunction[ShaderAST, ShaderAST] = {
      case ShaderAST.Annotated(ShaderAST.DataTypes.ident("in"), param, v @ ShaderAST.Val(_, _, _)) =>
        ShaderAST.Annotated(ShaderAST.DataTypes.ident("varying"), param, v)

      case ShaderAST.Annotated(ShaderAST.DataTypes.ident("out"), param, v @ ShaderAST.Val(_, _, _)) =>
        ShaderAST.Annotated(ShaderAST.DataTypes.ident("varying"), param, v)
    }

    def printer: PartialFunction[ShaderAST, List[String]] = PartialFunction.empty

  given ShaderPrinter[WebGL2] = new ShaderPrinter:
    def isValid(
        inType: Option[String],
        outType: Option[String],
        headers: List[ShaderAST],
        functions: List[ShaderAST],
        body: ShaderAST
    ): ShaderValid = ShaderValid.Valid

    def transformer: PartialFunction[ShaderAST, ShaderAST] = {
      case ShaderAST.Annotated(ShaderAST.DataTypes.ident("attribute"), param, v @ ShaderAST.Val(_, _, _)) =>
        ShaderAST.Annotated(ShaderAST.DataTypes.ident("in"), param, v)

      case ShaderAST.CallFunction("texture2D", args, argNames, returnType) =>
        ShaderAST.CallFunction("texture", args, argNames, returnType)

      case ShaderAST.CallFunction("textureCube", args, argNames, returnType) =>
        ShaderAST.CallFunction("texture", args, argNames, returnType)
    }

    def printer: PartialFunction[ShaderAST, List[String]] = PartialFunction.empty

  def print[T](ast: ShaderAST)(using pp: ShaderPrinter[T]): List[String] =
    render(ast)

  @SuppressWarnings(Array("scalafix:DisableSyntax.throw"))
  private def render(ast: ShaderAST)(using pp: ShaderPrinter[_]): List[String] =
    val r: ShaderAST => List[String] = {
      case Empty() =>
        Nil

      case Block(statements) =>
        renderStatements(statements)

      case Neg(v) =>
        render(v) match
          case init :+ last => init :+ s"""-${maybeAddBrackets(last)}"""
          case _            => Nil

      case UBO(uboDef) =>
        List(uboDef.render)

      case Struct(name, members) =>
        List(
          List(
            s"""struct $name{""".stripMargin.trim
          ),
          renderStatements(members).map(addIndent),
          List(
            s"""};""".stripMargin.trim
          )
        ).flatten

      case New(name, args) =>
        val renderedArgs: String =
          args
            .map(arg => s"${render(arg).mkString}")
            .mkString(",")

        List(s"""$name($renderedArgs)""")

      case ShaderBlock(_, _, envVarName, headers, statements) =>
        renderStatements(statements)

      case Function(id, args, body, returnType) if id.isEmpty =>
        throw ShaderError.PrintError("Failed to render shader, unnamed function definition found.")

      case Function(id, args, fnBody, returnType) =>
        val statements =
          fnBody match
            case Block(ss) => ss
            case _         => List(fnBody)

        val (body: List[String], rt: String) =
          processFunctionStatements(statements, returnType.flatMap(r => render(r).headOption))

        val renderedArgs: String =
          args
            .map {
              case (typ @ Annotated(_, _, _), name) =>
                s"${render(typ).mkString} $name"

              case (typ, name) =>
                s"in ${render(typ).mkString} $name"
            }
            .mkString(",")

        List(
          List(s"""$rt $id($renderedArgs){"""),
          body.map(addIndent),
          List("}")
        ).flatten

      case CallFunction(id, args, _, _) =>
        List(s"""$id(${args.flatMap(render).mkString(",")})""")

      case FunctionRef(_, _, _) =>
        Nil

      case Cast(value, as) =>
        List(s"""$as(${render(value).mkString})""")

      case Infix(op, left, right, returnType) =>
        val l = render(left).mkString
        val r = render(right).mkString
        List(s"""${maybeAddBrackets(l)}$op${maybeAddBrackets(r)}""")

      case Assign(left, right) =>
        List(s"""${render(left).mkString}=${render(right).mkString}""")

      case If(cond, thenTerm, elseTerm) =>
        elseTerm match
          case None =>
            List(
              List(s"""if(${render(cond).mkString}){"""),
              render(thenTerm).map(addIndent),
              List(s"""}""")
            ).flatten

          case Some(els) =>
            List(
              List(s"""if(${render(cond).mkString}){"""),
              render(thenTerm).map(addIndent),
              List(s"""}else{"""),
              render(els).map(addIndent),
              List(s"""}""")
            ).flatten

      case While(cond, body) =>
        List(
          List(s"""while(${render(cond).mkString}){"""),
          render(body).map(addIndent),
          List("""}""")
        ).flatten

      case For(init, cond, next, body) =>
        val i = render(init).mkString
        val c = render(cond).mkString
        val n = render(next).mkString
        List(
          List(s"""for($i;$c;$n){"""),
          render(body).map(addIndent),
          List("""}""")
        ).flatten

      case Switch(on, cases) =>
        val cs =
          cases.flatMap {
            case (Some(i), body) =>
              List(
                List(s"""case $i:"""),
                render(body).map(addIndent),
                List(s"""  break;""")
              ).flatten

            case (None, body) =>
              List(
                List(s"""default:"""),
                render(body).map(addIndent),
                List(s"""  break;""")
              ).flatten
          }

        List(
          List(s"""switch(${render(on).mkString}){"""),
          cs.map(addIndent),
          List(s"""}""")
        ).flatten

      case DataTypes.ident(id) if id.endsWith(".length") =>
        List(s"$id()")

      case DataTypes.ident(id) =>
        List(s"$id")

      case DataTypes.index(id, at) =>
        render(at).map(idx => s"$id[$idx]")

      case DataTypes.bool(b) =>
        List(s"${b.toString}")

      case DataTypes.float(v) =>
        List(s"${rf(v)}")

      case DataTypes.int(v) =>
        List(s"${v.toString}")

      case DataTypes.vec2(args) =>
        List(s"vec2(${args.flatMap(render).mkString(",")})")

      case DataTypes.vec3(args) =>
        List(s"vec3(${args.flatMap(render).mkString(",")})")

      case DataTypes.vec4(args) =>
        List(s"vec4(${args.flatMap(render).mkString(",")})")

      case DataTypes.bvec2(args) =>
        List(s"bvec2(${args.flatMap(render).mkString(",")})")

      case DataTypes.bvec3(args) =>
        List(s"bvec3(${args.flatMap(render).mkString(",")})")

      case DataTypes.bvec4(args) =>
        List(s"bvec4(${args.flatMap(render).mkString(",")})")

      case DataTypes.ivec2(args) =>
        List(s"ivec2(${args.flatMap(render).mkString(",")})")

      case DataTypes.ivec3(args) =>
        List(s"ivec3(${args.flatMap(render).mkString(",")})")

      case DataTypes.ivec4(args) =>
        List(s"ivec4(${args.flatMap(render).mkString(",")})")

      case DataTypes.mat2(args) =>
        List(s"mat2(${args.flatMap(render).mkString(",")})")

      case DataTypes.mat3(args) =>
        List(s"mat3(${args.flatMap(render).mkString(",")})")

      case DataTypes.mat4(args) =>
        List(s"mat4(${args.flatMap(render).mkString(",")})")

      case DataTypes.array(size, args, typeOf) =>
        List(s"""${typeOf.getOrElse("void")}(${args.flatMap(render).mkString(",")})""")

      case DataTypes.swizzle(genType, swizzle, returnType) =>
        genType match
          case ShaderAST.Infix(_, _, _, _) =>
            List(s"(${render(genType).mkString}).$swizzle")

          case _ =>
            List(s"${render(genType).mkString}.$swizzle")

      case Val(id, value, typeOf) =>
        val tOf = typeOf.getOrElse("void")
        value match
          // This rearranges `vec2[16] foo` to `vec2 foo[16]`, both are valid,
          // however the original is easier once we get to multidimensional arrays
          // (not available until GLSL 4!)
          // case Empty() if tOf.endsWith("]") && tOf.contains("[") =>
          //   // array
          //   val (tName, tSize) = tOf.splitAt(tOf.indexOf("["))
          //   List(s"""$tName $id$tSize""")

          case Empty() =>
            List(s"""$tOf $id""")

          case _ =>
            List(s"""$tOf $id=${render(value).mkString}""")

      case Annotated(label, param, value) =>
        val lbl = render(label).mkString
        value match
          case v @ Val(id, value, typeOf) if lbl == "const" =>
            List(s"""const ${render(v).mkString}""")

          case v @ Val(id, value, typeOf) if lbl == "define" =>
            List(s"""#define $id ${render(value).mkString}""")

          case v @ Val(id, value, typeOf) if lbl == "layout" =>
            List(s"""layout (location = ${render(param).mkString}) in ${render(Val(id, Empty(), typeOf)).mkString}""")

          case v @ Val(id, value, typeOf) =>
            List(s"""$lbl ${render(Val(id, Empty(), typeOf)).mkString}""")

          case _ =>
            List(s"""$lbl ${render(value).mkString}""")

      case RawLiteral(body) =>
        List(body)
    }

    val p =
      pp.printer.orElse { case x => r(x) }

    p(ast.traverse(pp.transformer.orElse(n => n)))

  private def renderStatements(statements: List[ShaderAST])(using pp: ShaderPrinter[_]): List[String] =
    val p =
      pp.printer.orElse {
        case ShaderAST.RawLiteral(raw) =>
          List(raw)

        case f: ShaderAST.Function =>
          render(f)

        case ShaderAST.Block(ss) =>
          renderStatements(ss)

        case x =>
          render(x)
            .map {
              case s if s.isEmpty()       => s
              case s if s.endsWith(";")   => s
              case s if s.endsWith(":")   => s
              case s if s.endsWith("{")   => s
              case s if s.endsWith("}")   => s
              case s if s.startsWith("#") => s
              case s                      => s + ";"
            }
      }

    statements
      .map(_.traverse(pp.transformer.orElse(n => n)))
      .flatMap(p)
      .filterNot(_.isEmpty)

  private def addIndent: String => String = str => "  " + str

  private def decideType(a: ShaderAST)(using pp: ShaderPrinter[_]): Option[String] =
    a match
      case Empty()                       => None
      case Block(_)                      => None
      case Neg(v)                        => decideType(v)
      case UBO(_)                        => None
      case Struct(name, _)               => Option(name)
      case New(name, _)                  => Option(name)
      case ShaderBlock(_, _, _, _, _)    => None
      case Function(_, _, _, rt)         => rt.toList.flatMap(render).headOption
      case CallFunction(_, _, _, rt)     => rt.toList.flatMap(render).headOption
      case FunctionRef(_, _, rt)         => rt.toList.flatMap(render).headOption
      case Cast(_, as)                   => Option(as)
      case Infix(_, _, _, rt)            => rt.toList.flatMap(render).headOption
      case Assign(_, _)                  => None
      case If(_, _, _)                   => None
      case While(_, _)                   => None
      case For(_, _, _, _)               => None
      case Switch(_, _)                  => None
      case Val(_, _, typeOf)             => typeOf
      case Annotated(_, _, value)        => decideType(value)
      case RawLiteral(_)                 => None
      case DataTypes.ident(_)            => None
      case DataTypes.index(_, _)         => None
      case DataTypes.bool(_)             => Option("bool")
      case DataTypes.float(_)            => Option("float")
      case DataTypes.int(_)              => Option("int")
      case DataTypes.vec2(_)             => Option("vec2")
      case DataTypes.vec3(_)             => Option("vec3")
      case DataTypes.vec4(_)             => Option("vec4")
      case DataTypes.bvec2(_)            => Option("bvec2")
      case DataTypes.bvec3(_)            => Option("bvec3")
      case DataTypes.bvec4(_)            => Option("bvec4")
      case DataTypes.ivec2(_)            => Option("ivec2")
      case DataTypes.ivec3(_)            => Option("ivec3")
      case DataTypes.ivec4(_)            => Option("ivec4")
      case DataTypes.mat2(_)             => Option("mat2")
      case DataTypes.mat3(_)             => Option("mat3")
      case DataTypes.mat4(_)             => Option("mat4")
      case DataTypes.array(_, _, typeOf) => typeOf
      case DataTypes.swizzle(_, _, rt)   => rt.toList.flatMap(render).headOption

  private def rf(f: Float): String =
    val s = f.toString
    if s.contains(".") then s else s + ".0"

  private def maybeAddBrackets(str: String): String =
    val isSimpleValue = """^([a-zA-Z0-9\[\]\(\)\.\,_]+)$""".r
    val hasBrackets   = """^\((.*)\)$""".r
    str match
      case s if hasBrackets.matches(s) || isSimpleValue.matches(s) => s
      case s                                                       => s"""($s)"""

  private def processFunctionStatements(
      statements: List[ShaderAST],
      maybeReturnType: Option[String]
  )(using pp: ShaderPrinter[_]): (List[String], String) =
    val nonEmpty = statements
      .filterNot(_.isEmpty)

    val (init, last) =
      if nonEmpty.length > 1 then (nonEmpty.dropRight(1), nonEmpty.takeRight(1))
      else (Nil, nonEmpty)

    val returnType =
      maybeReturnType match
        case None        => last.headOption.flatMap(decideType).getOrElse("void")
        case Some(value) => value

    val end: List[String] =
      last.headOption
        .map { ss =>
          renderStatements(List(ss)) match
            case x :: Nil =>
              List((if returnType != "void" then "return " else "") + x)

            case x :: xs =>
              // This case covers things like if statements
              ((if returnType != "void" then "return " else "") + x) :: xs

            case xs =>
              xs
        }
        .getOrElse(Nil)

    val body =
      renderStatements(init) ++ end

    (body, returnType)
