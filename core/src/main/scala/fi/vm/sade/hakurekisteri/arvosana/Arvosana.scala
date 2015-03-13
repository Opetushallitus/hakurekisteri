package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.{UUIDResource, Resource}
import scala.util.Try
import org.joda.time.LocalDate

case class Arvosana(suoritus: UUID,
                    arvio: Arvio,
                    aine: String,
                    lisatieto: Option[String],
                    valinnainen: Boolean,
                    myonnetty: Option[LocalDate] = None,
                    source: String,
                    jarjestys: Option[Int] = None) extends UUIDResource[Arvosana] {

  override def identify(identity: UUID): Arvosana with Identified[UUID]= new IdentifiedArvosana(this, identity)

  private[Arvosana] case class ArvosanaCore(suoritus: UUID,
                                            arvio: Arvio,
                                            aine: String,
                                            lisatieto: Option[String],
                                            valinnainen: Boolean,
                                            myonnetty: Option[LocalDate],
                                            jarjestys: Option[Int])

  override val core = ArvosanaCore(suoritus, arvio, aine, lisatieto, valinnainen, myonnetty, jarjestys)
}

class IdentifiedArvosana(a: Arvosana, val id: UUID) extends Arvosana(a.suoritus, a.arvio , a.aine, a.lisatieto, a.valinnainen, a.myonnetty, a.source, a.jarjestys) with Identified[UUID]

sealed abstract class Arvio

object Arvio {
  val ASTEIKKO_4_10 = "4-10"
  val ASTEIKKO_OSAKOE = "OSAKOE"
  val ASTEIKKOYO = "YO"
  val asteikot = Seq(ASTEIKKO_4_10, ASTEIKKOYO, ASTEIKKO_OSAKOE)

  def apply(arvosana: String, asteikko: String, pisteet: Option[Int] = None): Arvio = asteikko match {
    case ASTEIKKO_4_10 => Arvio410(arvosana)
    case ASTEIKKOYO => ArvioYo(arvosana, pisteet)
    case ASTEIKKO_OSAKOE => ArvioOsakoe(arvosana)
    case _ => throw UnknownScaleException(asteikko)
  }
}


case class UnknownScaleException(scale: String) extends IllegalArgumentException(s"unknown scale: $scale")

case class Arvio410(arvosana: String) extends Arvio {
  val allowable = Set[String]("4", "5", "6", "7", "8", "9", "10", "S")
  require(allowable.contains(arvosana), s"$arvosana is not in (${allowable.mkString(", ")})")
}

case class ArvioYo(arvosana: String, pisteet: Option[Int]) extends Arvio {
  val allowable = Set[String]("L", "E", "M", "C", "B", "A", "I+", "I", "I-", "I=", "K", "P" )
  require(allowable.contains(arvosana), s"$arvosana is not in (${allowable.mkString(", ")})")
}

case class ArvioOsakoe(arvosana: String) extends Arvio {
}
