package wdl.draft3.transforms.linking.expression.types

import cats.data.NonEmptyList
import cats.syntax.validated._
import common.validation.ErrorOr.ErrorOr
import common.validation.ErrorOr._
import common.validation.Validation._
import wdl.model.draft3.elements.ExpressionElement.{ExpressionMemberAccess, IdentifierLookup, IdentifierMemberAccess}
import wdl.model.draft3.graph._
import wdl.model.draft3.graph.expression.TypeEvaluator
import wdl.model.draft3.graph.expression.TypeEvaluator.ops._
import wom.types._

object LookupEvaluators {

  implicit val identifierLookupTypeEvaluator: TypeEvaluator[IdentifierLookup] = new TypeEvaluator[IdentifierLookup] {
    override def evaluateType(a: IdentifierLookup, linkedValues: Map[UnlinkedConsumedValueHook, GeneratedValueHandle]): ErrorOr[WomType] = {
      linkedValues.collectFirst {
        case (UnlinkedIdentifierHook(id), gen) if a.identifier == id => gen.womType
      } match {
        case Some(womType) => womType.validNel
        case None => s"Type evaluation failure. No suitable type found for identifier lookup '${a.identifier}' amongst {${linkedValues.map(_._2.linkableName).mkString(", ")}}".invalidNel
      }
    }
  }

  implicit val expressionMemberAccessEvaluator: TypeEvaluator[ExpressionMemberAccess] = new TypeEvaluator[ExpressionMemberAccess] {
    override def evaluateType(a: ExpressionMemberAccess, linkedValues: Map[UnlinkedConsumedValueHook, GeneratedValueHandle]): ErrorOr[WomType] = {
      val baseType = a.expression.evaluateType(linkedValues)
      baseType flatMap { doLookup(_, a.memberAccessTail) }
    }
  }

  implicit val identifierMemberAccessEvaluator: TypeEvaluator[IdentifierMemberAccess] = new TypeEvaluator[IdentifierMemberAccess] {
    override def evaluateType(a: IdentifierMemberAccess, linkedValues: Map[UnlinkedConsumedValueHook, GeneratedValueHandle]): ErrorOr[WomType] = {
      val generatedValueHandle = linkedValues.get(UnlinkedCallOutputOrIdentifierAndMemberAccessHook(a.first, a.second))

      generatedValueHandle match {
        case Some(GeneratedIdentifierValueHandle(a.first, womType)) => doLookup(womType, NonEmptyList(a.second, a.memberAccessTail.toList))
        case Some(GeneratedCallOutputValueHandle(a.first, a.second, womType)) => NonEmptyList.fromList(a.memberAccessTail.toList) match {
          case Some(tailList) => doLookup(womType, tailList)
          case None => womType.validNel
        }
        case _ => s"Type evaluation failure. No suitable type found for identifier lookup '${a.first}' or '${a.first}.${a.second}' amongst {${linkedValues.map(_._2.linkableName).mkString(", ")}}".invalidNel
      }
    }
  }

  /**
    * Try to perform the first lookup in the chain.
    * Then, depending on whether we're done or not, return the result or recurse for the next lookup
    *
    * @param womType The type to perform the lookup on
    * @param lookupChain The chain of lookups to perform
    *
    * @return The ultimate value of the final lookup (or any error along the way!).
    */
  private def doLookup(womType: WomType, lookupChain: NonEmptyList[String]): ErrorOr[WomType] = {
    val key = lookupChain.head
    val tail = NonEmptyList.fromList(lookupChain.tail)

    val thisValue: ErrorOr[WomType] = womType match {
      case WomCompositeType(typeMap) => typeMap.get(key).toErrorOr(s"No such field '$key' on type ${womType.toDisplayString}.")
      case WomObjectType => WomAnyType.validNel
      case WomPairType(left, _) if key == "left" => left.validNel
      case WomPairType(_, right) if key == "right" => right.validNel
      case WomAnyType => WomAnyType.validNel
      case _ => s"No such field '$key' on type ${womType.toDisplayString}. Report this bug! Static validation failed.".invalidNel
    }

    tail match {
      case None => thisValue
      case Some(lookupTail) => thisValue flatMap { doLookup(_, lookupTail) }
    }
  }
}
