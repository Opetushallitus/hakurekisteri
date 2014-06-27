package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.Resource
import scala.util.Try

case class Arvosana(suoritus: UUID, arvio: Arvio, aine: String, lisatieto: Option[String], valinnainen: Boolean) extends Resource[UUID] {
  override def identify(id: UUID): this.type with Identified[UUID]= Arvosana.identify(this,id).asInstanceOf[this.type with Identified[UUID]]
}

object Arvosana {
  def identify(o:Arvosana): Arvosana with Identified[UUID] = o match {
    case o: Arvosana with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(o:Arvosana, identity:UUID) = {


    new Arvosana(o.suoritus, o.arvio , o.aine, o.lisatieto, o.valinnainen) with Identified[UUID] {
      val id: UUID = identity
    }
  }
}

sealed abstract class Arvio(val arvosana:String)

object Arvio {

  val ASTEIKKO_4_10 = "4-10"
  val asteikot = Seq(ASTEIKKO_4_10)

  object NA extends Arvio("NA")

  def apply(arvosana:String, asteikko:String):Arvio = asteikko match {
    case ASTEIKKO_4_10 => Arvio410(arvosana)
    case _ => throw UnknownScaleException(asteikko)
  }
}

case class UnknownScaleException(scale:String) extends IllegalArgumentException("unknown scale: %s" format scale)

case class Arvio410(override val arvosana:String) extends Arvio(arvosana) {
  require(Try(arvosana.toInt).isSuccess, "arvosana must be a number")
  require(arvosana.toInt >= 4, "the arvosana must be greater than or equal to 4")
  require(arvosana.toInt <= 10, "the arvosana must be less than or equal to 10")

}
