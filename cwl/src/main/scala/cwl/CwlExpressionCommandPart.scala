package cwl

import cats.syntax.either._
import cats.syntax.validated._
import common.Checked
import common.validation.Checked._
import common.validation.ErrorOr.ErrorOr
import wom.callable.RuntimeEnvironment
import wom.expression.IoFunctionSet
import wom.graph.LocalName
import wom.values._
import wom.{CommandPart, InstantiatedCommand}

import scala.language.postfixOps

case class CwlExpressionCommandPart(expr: Expression)(expressionLib: ExpressionLib) extends CommandPart {
  override def instantiate(inputsMap: Map[LocalName, WomValue],
                           functions: IoFunctionSet,
                           valueMapper: (WomValue) => WomValue,
                           runtimeEnvironment: RuntimeEnvironment): ErrorOr[List[InstantiatedCommand]] = {
    val stringKeyMap = inputsMap map {
      case (LocalName(localName), value) => localName -> valueMapper(value)
    }
    val parameterContext = ParameterContext(
        inputs = stringKeyMap, runtimeOption = Option(runtimeEnvironment)
      )
    ExpressionEvaluator.eval(expr, parameterContext, expressionLib) map { womValue =>
      List(InstantiatedCommand(valueMapper(womValue).valueString.shellQuote))
    }
  }
}

/**
  * Generates command parts from a CWL Binding
  */
abstract class CommandLineBindingCommandPart(commandLineBinding: CommandLineBinding)(expressionLib: ExpressionLib) extends CommandPart {

  
  private lazy val prefixAsString = commandLineBinding.prefix.getOrElse("")
  private lazy val prefixAsList = commandLineBinding.prefix.toList
  private lazy val separate = commandLineBinding.effectiveSeparate

  def handlePrefix(value: String) = {
    if (separate) prefixAsList :+ value else List(s"$prefixAsString$value")
  }

  override def instantiate(inputsMap: Map[LocalName, WomValue],
                           functions: IoFunctionSet,
                           valueMapper: (WomValue) => WomValue,
                           runtimeEnvironment: RuntimeEnvironment): ErrorOr[List[InstantiatedCommand]] = {
      val stringInputsMap = inputsMap map {
        case (LocalName(localName), value) => localName -> valueMapper(value)
      }
      val parameterContext = ParameterContext(inputs = stringInputsMap, runtimeOption = Option(runtimeEnvironment))

    val evaluatedValueFrom = commandLineBinding.optionalValueFrom map {
      case StringOrExpression.Expression(expression) => ExpressionEvaluator.eval(expression, parameterContext, expressionLib) map valueMapper
      case StringOrExpression.String(string) => WomString(string).validNel
    }

    val evaluatedWomValue: Checked[WomValue] = evaluatedValueFrom.orElse(boundValue.map(_.validNel)) match {
      case Some(womValue) => womValue.map(valueMapper).toEither
      case None => "Command line binding has no valueFrom field and no bound value".invalidNelCheck
    }
    
    def applyShellQuote(value: String): String = commandLineBinding.shellQuote match {
      case Some(false) => value
      case _ => value.shellQuote
    }
    
    def processValue(womValue: WomValue): List[String] = womValue match {
      case WomOptionalValue(_, Some(value)) => processValue(valueMapper(value))
      case WomOptionalValue(_, None) => List.empty
      case _: WomString | _: WomInteger | _: WomFile | _: WomLong | _: WomFloat => handlePrefix(valueMapper(womValue).valueString)
      // For boolean values, use the value of the boolean to choose whether to print the prefix or not
      case WomBoolean(false) => List.empty
      case WomBoolean(true) => prefixAsList
      case WomArray(_, values) => commandLineBinding.itemSeparator match {
        case Some(itemSeparator) => handlePrefix(values.map(valueMapper(_).valueString).mkString(itemSeparator))

        /*
        via: http://www.commonwl.org/v1.0/CommandLineTool.html#CommandLineBinding

        > If itemSeparator is specified, add prefix and the join the array into a single string with itemSeparator
        > separating the items. Otherwise first add prefix, then recursively process individual elements.

        Not 100% sure if this is conformant, as we are only recurse the head into `processValue` here... However the
        conformance test "Test InlineJavascriptRequirement with multiple expressions in the same tool" is happy with the
        behavior.
         */
        // InstantiatedCommand elements are generated here when there exists an optionalValueFrom
        case None if commandLineBinding.optionalValueFrom.isDefined => values.toList match {
          case head :: tail => processValue(head) ++ tail.map(valueMapper(_).valueString)
          case Nil => prefixAsList
        }

        // When there is no optionalValueFrom the InstantiatedCommand elements for the womValue are appended elsewhere
        case _ => prefixAsList
      }
      case _: WomObjectLike => prefixAsList
      case w => throw new RuntimeException(s"Unhandled CwlExpressionCommandPart value '$w' of type ${w.womType.toDisplayString}")
    }

    evaluatedWomValue map { v => processValue(v) map applyShellQuote map (InstantiatedCommand(_)) } toValidated
  }
  
  def boundValue: Option[WomValue]
}

case class InputCommandLineBindingCommandPart(commandLineBinding: InputCommandLineBinding, associatedValue: WomValue)(expressionLib: ExpressionLib) extends CommandLineBindingCommandPart(commandLineBinding)(expressionLib) {
  override lazy val boundValue = Option(associatedValue)
}

case class ArgumentCommandLineBindingCommandPart(commandLineBinding: ArgumentCommandLineBinding)(expressionLib: ExpressionLib) extends CommandLineBindingCommandPart(commandLineBinding)(expressionLib) {
  override lazy val boundValue = None
}
