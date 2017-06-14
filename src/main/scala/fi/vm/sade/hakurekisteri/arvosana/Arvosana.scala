package fi.vm.sade.hakurekisteri.arvosana

import scala.collection.JavaConverters._
import java.util.UUID

import fi.vm.sade.hakurekisteri.integration.hakemus.dto
import fi.vm.sade.hakurekisteri.rest.support.UUIDResource
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.LocalDate

case class Arvosana(suoritus: UUID,
                    arvio: Arvio,
                    aine: String,
                    lisatieto: Option[String],
                    valinnainen: Boolean,
                    myonnetty: Option[LocalDate] = None,
                    source: String,
                    lahdeArvot: Map[String,String],
                    jarjestys: Option[Int] = None) extends UUIDResource[Arvosana] {

  if (arvio.isInstanceOf[ArvioYo] || arvio.isInstanceOf[ArvioOsakoe])
    require(myonnetty.isDefined, "myonnetty is required for asteikko YO and OSAKOE")

  if (arvio.isInstanceOf[Arvio410] && valinnainen)
    require(jarjestys.isDefined, "jarjestys is required for valinnainen arvosana")

  override def identify(identity: UUID): Arvosana with Identified[UUID]= new IdentifiedArvosana(this, identity)

  private[Arvosana] case class ArvosanaCore(suoritus: UUID,
                                            aine: String,
                                            lisatieto: Option[String],
                                            valinnainen: Boolean,
                                            myonnetty: Option[LocalDate],
                                            jarjestys: Option[Int])

  override val core = ArvosanaCore(suoritus, aine, lisatieto, valinnainen, myonnetty, jarjestys)

  def asJava = {
    val uusiLahdeArvot: java.util.Map[String, String] = lahdeArvot.asJava
    val uusiArvio = arvio.asJava
    val r = new fi.vm.sade.hakurekisteri.integration.hakemus.dto.Arvosana()
    new fi.vm.sade.hakurekisteri.integration.hakemus.dto.Arvosana("id", suoritus.toString, aine, valinnainen, myonnetty.map(_.toString).orNull, source, uusiLahdeArvot, uusiArvio, lisatieto.orNull)
  }
}

class IdentifiedArvosana(a: Arvosana, val id: UUID) extends Arvosana(a.suoritus, a.arvio , a.aine, a.lisatieto, a.valinnainen, a.myonnetty, a.source, a.lahdeArvot, a.jarjestys) with Identified[UUID]

sealed abstract class Arvio {
  def asJava: dto.Arvio
}

object Arvio {
  val ASTEIKKO_4_10 = "4-10"
  val ASTEIKKO_OSAKOE = "OSAKOE"
  val ASTEIKKOYO = "YO"
  val ASTEIKKO_HYVAKSYTTY = "HYVAKSYTTY"
  val asteikot = Seq(ASTEIKKO_4_10, ASTEIKKOYO, ASTEIKKO_OSAKOE, ASTEIKKO_HYVAKSYTTY)

  def apply(arvosana: String, asteikko: String, pisteet: Option[Int] = None): Arvio = asteikko match {
    case ASTEIKKO_4_10 => Arvio410(arvosana)
    case ASTEIKKOYO => ArvioYo(arvosana, pisteet)
    case ASTEIKKO_OSAKOE => ArvioOsakoe(arvosana)
    case ASTEIKKO_HYVAKSYTTY => ArvioHyvaksytty(arvosana)
    case _ => throw UnknownScaleException(asteikko)
  }
}


case class UnknownScaleException(scale: String) extends RuntimeException(s"unknown scale: $scale")

case class Arvio410(arvosana: String) extends Arvio {
  val allowable = Set[String]("4", "5", "6", "7", "8", "9", "10", "S")
  require(allowable.contains(arvosana), s"$arvosana is not in (${allowable.mkString(", ")})")

  override def asJava: dto.Arvio = new dto.Arvio(arvosana, null, null)
}

case class ArvioYo(arvosana: String, pisteet: Option[Int]) extends Arvio {
  val allowable = Set[String]("L", "E", "M", "C", "B", "A", "I+", "I", "I-", "I=", "K", "P" )
  require(allowable.contains(arvosana), s"$arvosana is not in (${allowable.mkString(", ")})")

  override def asJava: dto.Arvio = new dto.Arvio(arvosana, null, null)
}

case class ArvioOsakoe(arvosana: String) extends Arvio {
  override def asJava: dto.Arvio = new dto.Arvio(arvosana, null, null)
}

case class ArvioHyvaksytty(arvosana: String) extends Arvio {
  val allowable = Set[String]("hyvaksytty", "hylatty", "ei_osallistunut")
  require(allowable.contains(arvosana), s"$arvosana is not in (${allowable.mkString(", ")})")

  override def asJava: dto.Arvio = new dto.Arvio(arvosana, null, null)
}
