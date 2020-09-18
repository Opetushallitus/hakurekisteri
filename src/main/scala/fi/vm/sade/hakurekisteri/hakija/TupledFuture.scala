package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object TupledFuture {

  implicit def tuple42TupledFuture4[T1, T2, T3, T4](
    t: (Future[T1], Future[T2], Future[T3], Future[T4])
  ): TupledFuture4[T1, T2, T3, T4] =
    new TupledFuture4(t)

  implicit def future2Tupler4[T1, T2, T3, T4](
    fut: Future[(T1, T2, T3, T4)]
  ): Tupler4[T1, T2, T3, T4] = new Tupler4(fut)

  implicit def tuple32TupledFuture3[T1, T2, T3](
    t: (Future[T1], Future[T2], Future[T3])
  ): TupledFuture3[T1, T2, T3] =
    new TupledFuture3(t)

  implicit def future2Tupler3[T1, T2, T3](fut: Future[(T1, T2, T3)]): Tupler3[T1, T2, T3] =
    new Tupler3(fut)

}

class TupledFuture4[T1, T2, T3, T4](t: (Future[T1], Future[T2], Future[T3], Future[T4])) {

  def join(implicit ec: ExecutionContext) = {
    t._1
      .flatMap((first) => t._2.map((first, _)))
      .flatMap(t2 => t._3.map((t2._1, t2._2, _)))
      .flatMap(t2 => t._4.map((t2._1, t2._2, t2._3, _)))
  }

}

class Tupler4[T1, T2, T3, T4](fut: Future[(T1, T2, T3, T4)]) {
  def tupledMap[R](f: Function4[T1, T2, T3, T4, R])(implicit ec: ExecutionContext) =
    fut.map(f.tupled)

}

class TupledFuture3[T1, T2, T3](t: (Future[T1], Future[T2], Future[T3])) {

  def join(implicit ec: ExecutionContext) = {
    t._1.flatMap((first) => t._2.map((first, _))).flatMap(t2 => t._3.map((t2._1, t2._2, _)))
  }

}

class Tupler3[T1, T2, T3](fut: Future[(T1, T2, T3)]) {
  def tupledMap[R](f: Function3[T1, T2, T3, R])(implicit ec: ExecutionContext) = fut.map(f.tupled)

}
