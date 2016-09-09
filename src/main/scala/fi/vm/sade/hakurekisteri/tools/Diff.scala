package fi.vm.sade.hakurekisteri.tools

import scala.reflect.runtime.universe._

case class Diff[T <: Product : TypeTag]() {

  private def classAccessors[T: TypeTag] = typeOf[T].members.collect {
    case m: MethodSymbol if m.isCaseAccessor => m.name.toString
  }.toList.reverse

  val accessors = classAccessors[T]

  def find[T <: Product : TypeTag](a0: T, a1: T): Map[String, (Any, Any)] = {
    val a1data = a1.productIterator.toList
    val diffs = a0.productIterator.zipWithIndex.filter {
      case (value,index) if(a1data(index)==value) => false
      case _ => true
    }
    diffs.map {
      case (value,index) => accessors(index) -> (value, a1data(index))
    }.toMap
  }
}
