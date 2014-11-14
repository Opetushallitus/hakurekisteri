package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.{UUIDResource, Resource}
import scala.util.Try
import org.joda.time.LocalDate

case class Arvosana(suoritus: UUID, arvio: Arvio, aine: String, lisatieto: Option[String], valinnainen: Boolean, myonnetty: Option[LocalDate] = None, source: String) extends UUIDResource[Arvosana] {
  override def identify(identity: UUID): Arvosana with Identified[UUID]= new IdentifiedArvosana(this, identity)

  private[Arvosana] case class ArvosanaCore(suoritus: UUID, arvio: Arvio, aine: String, lisatieto: Option[String], valinnainen: Boolean, myonnetty: Option[LocalDate])

  override val core = ArvosanaCore(suoritus, arvio, aine, lisatieto, valinnainen, myonnetty)
}

class IdentifiedArvosana(a: Arvosana, val id: UUID) extends Arvosana(a.suoritus, a.arvio , a.aine, a.lisatieto, a.valinnainen, a.myonnetty, a.source) with Identified[UUID]

sealed abstract class Arvio

object Arvio {
  val ASTEIKKO_4_10 = "4-10"
  val ASTEIKKOYO = "YO"
  val asteikot = Seq(ASTEIKKO_4_10, ASTEIKKOYO)

  def apply(arvosana: String, asteikko: String, pisteet: Option[Int] = None): Arvio = asteikko match {
    case ASTEIKKO_4_10 => Arvio410(arvosana)
    case ASTEIKKOYO => ArvioYo(arvosana, pisteet)
    case _ => throw UnknownScaleException(asteikko)
  }
}


case class UnknownScaleException(scale: String) extends IllegalArgumentException(s"unknown scale: $scale")

case class Arvio410(arvosana: String) extends Arvio {
  require(Try(arvosana.toInt).isSuccess, "arvosana must be a number")
  require(arvosana.toInt >= 4, "the arvosana must be greater than or equal to 4")
  require(arvosana.toInt <= 10, "the arvosana must be less than or equal to 10")
}

case class ArvioYo(arvosana: String, pisteet: Option[Int]) extends Arvio {
  val allowable = Set[String]("L", "E", "M", "C", "B", "A", "I+", "I", "I-", "I=", "K", "P" )
  require(allowable.contains(arvosana), s"$arvosana is not in (${allowable.mkString(", ")})")
}
