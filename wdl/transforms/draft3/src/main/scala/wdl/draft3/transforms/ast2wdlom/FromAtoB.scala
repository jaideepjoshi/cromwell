package wdl.draft3.transforms.ast2wdlom

import cats.syntax.traverse._
import cats.instances.vector._
import common.validation.ErrorOr.ErrorOr
import common.validation.ErrorOr.ShortCircuitingFlatMap

trait FromAtoB[A, B] {
  def convert(a: A): ErrorOr[B]
}

object FromAtoB {
  implicit def viaX[A, X, B](implicit ax: FromAtoB[A, X], xb: FromAtoB[X, B]): FromAtoB[A, B] = { a => ax.convert(a) flatMap xb.convert }
  implicit def forVectors[A, B](implicit ab: FromAtoB[A, B]): FromAtoB[Vector[A], Vector[B]] = as => as.traverse[ErrorOr, B] { ab.convert }
}