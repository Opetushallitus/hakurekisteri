package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.Resource

case class Arvosana(suoritus: UUID, arvio: Arvio, aine: String, lisatieto: Option[String], valinnainen: Boolean) extends Resource {
  override def identify[R <: Arvosana](id: UUID): R with Identified = Arvosana.identify(this,id).asInstanceOf[R with Identified]
}

object Arvosana {
  def identify(o:Arvosana): Arvosana with Identified = o match {
    case o: Arvosana with Identified => println(o.id);o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(o:Arvosana, identity:UUID) = {


    new Arvosana(o.suoritus, o.arvio , o.aine, o.lisatieto, o.valinnainen) with Identified {
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

case class Arvio410(override val arvosana:String) extends Arvio(arvosana)
