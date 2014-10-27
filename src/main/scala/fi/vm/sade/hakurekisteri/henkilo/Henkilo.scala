package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.{UUIDResource, Resource}
import scala.language.implicitConversions


case class Henkilo (
                     yhteystiedotRyhma: Seq[YhteystiedotRyhma],
                     yksiloity: Boolean,
                     sukunimi: String,
                     kielisyys: Seq[Kieli],
                     yksilointitieto: Option[Yksilointitieto],
                     henkiloTyyppi: String,
                     oidHenkilo: String,
                     duplicate: Boolean,
                     oppijanumero: String,
                     kayttajatiedot: Option[Kayttajatiedot],
                     kansalaisuus: Seq[Kansalaisuus],
                     passinnumero: String,
                     asiointiKieli: Kieli,
                     kutsumanimi: String,
                     passivoitu: Boolean,
                     eiSuomalaistaHetua: Boolean,
                     etunimet: String,
                     sukupuoli: String,
                     turvakielto: Boolean,
                     hetu: String,
                     syntymaaika: String,
                     markkinointilupa: Option[Boolean]) extends Resource[String, Henkilo] with Identified[String] {

  val source = ""

  override def identify(id: String): Henkilo with Identified[String] = this

  val id = oidHenkilo

  def newId = oidHenkilo


  override val core: AnyRef = oidHenkilo
}


case class Yhteystiedot (id: Int,
                         yhteystietoTyyppi: String,
                         yhteystietoArvo: String)


case class Kansalaisuus (
                          kansalaisuusKoodi: String)

case class Kayttajatiedot (
                            username: String)

case class Kieli (
                   kieliKoodi: String,
                   kieliTyyppi: String)

object Kieli {

  def apply(kieli:String) =  new Kieli(kieli, kieli)

}

case class YhteystiedotRyhma (
                               id: Int,
                               ryhmaKuvaus: String,
                               ryhmaAlkuperaTieto: String,
                               readOnly: Boolean,
                               yhteystiedot: Seq[Yhteystiedot])

object YhteystiedotRyhma {
  implicit def yhteystietoryhmatToMap(yhteystiedot: Seq[YhteystiedotRyhma]): Map[(String, String), Seq[Yhteystiedot]] = {
    yhteystiedot.map((y) => (y.ryhmaAlkuperaTieto, y.ryhmaKuvaus) -> y.yhteystiedot).toMap
  }

}

object Yhteystiedot {

  implicit def yhteystiedotToMap(yhteystiedot: Seq[Yhteystiedot]): Map[String, String] = {
    yhteystiedot.map((y) => y.yhteystietoTyyppi -> y.yhteystietoArvo).toMap
  }


}

case class Yksilointitieto (
                             hetu: String)

