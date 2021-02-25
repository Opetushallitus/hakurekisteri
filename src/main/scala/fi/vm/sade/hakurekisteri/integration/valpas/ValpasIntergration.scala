package fi.vm.sade.hakurekisteri.integration.valpas

import java.util.concurrent.Executors

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.{AtaruHakemus, FullHakemus, HakijaHakemus, IHakemusService}
import fi.vm.sade.hakurekisteri.integration.valpas
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}
import scala.concurrent.duration._

object ValpasHakemusTila extends Enumeration {
  type ValpasHakemusTila = Value

  val AKTIIVINEN: valpas.ValpasHakemusTila.Value = Value("AKTIIVINEN")
  val PUUTTEELLINEN: valpas.ValpasHakemusTila.Value = Value("PUUTTEELLINEN")
}

case class ValpasHakutoive(hakutoivenumero: Int,
                           hakukohdeOid: String,
                           hakukohdeKoulutuskoodi: String,
                           hakukohdeOrganisaatio: String,
                           hakukohdeNimi: String,
                           koulutusOid: String,
                           koulutusNimi: String,
                           harkinnanvaraisuus: Option[String],



                          ) {

}

case class ValpasHakemus(
  muokattu: String,
  oppijaOid: String, // oppijanumero
  hakemusOid: String, // hakemuksen oid
  hakuOid: String, // haun oid
  hakuNimi: String, // haun nimi
  email: String, //hakemuksella ilmoitettu sähköpostiosoite
  matkapuhelin: String, // hakemuksella ilmoitettu matkapuhelinnumero
  osoite: String, // hakemuksella ilmoitettu postiosoite
  //tila: ValpasHakemusTila.ValpasHakemusTila, //hakemuksen tila
  // Hakemuksella olevat hakutoiveet/niihin liittyvät valintatiedot
  /*
  hakutoivenumero
  hakukohteelle liitetyn koulutuksen koodi
  hakukohteen/koulutuksen nimi
  hakukohteen omistava organisaatio
  hakeeko harkinnanvaraisessa valinnassa + syy
  */ // DONE
/* // TODO
tieto siitä, onko kutsuttu pääsy- ja soveltuvuuskokeeseen
mahdollisen pääsy- ja soveltuvuuskokeen pistemäärä
mahdollinen kielitaidon arviointi
mahdollinen lisänäyttö
yhteispistemäärä
alimman hakukohteeseen hyväksytyn pistemäärä
valinnan tulos
varasijanumero, jos varalla
vastaanottotieto
 */
) {

}
object ValpasHakemus {

  def apply(hakemus: HakijaHakemus): ValpasHakemus = {
    hakemus match {
      case a: AtaruHakemus =>
        a.hakutoiveet.map(b => b.map(c =>

          ValpasHakutoive(
            hakutoivenumero = c.preferenceNumber,
            hakukohdeOid = c.koulutusIdAoIdentifier.get,
            hakukohdeKoulutuskoodi = "", // TODO
            hakukohdeNimi = "", // TODO
            hakukohdeOrganisaatio = c.organizationOid.get,
            koulutusOid = c.koulutusId.get,
            koulutusNimi = "", // TODO
            harkinnanvaraisuus = c.discretionaryFollowUp
          )
        ))


        ValpasHakemus(
          muokattu = "", // TODO
          oppijaOid = a.personOid.get,
          hakemusOid = a.oid,
          hakuOid = a.applicationSystemId,
          hakuNimi = "", // TODO
          matkapuhelin = a.matkapuhelin, // TODO
          osoite = s"${a.lahiosoite}, ${a.postinumero} ${a.postitoimipaikka}",
          email = a.email,

        )
      case h: FullHakemus =>
        ValpasHakemus(
          muokattu = "", // TODO
          oppijaOid = h.personOid.get,
          hakemusOid = h.oid,
          hakuNimi = "", // TODO
          matkapuhelin = h.answers.flatMap(a => a.henkilotiedot)
            .flatMap(h =>
              h.matkapuhelinnumero1.orElse(h.matkapuhelinnumero2)).get,
          hakuOid = h.applicationSystemId,
          email = h.answers.flatMap(a => a.henkilotiedot).flatMap(h => h.Sähköposti).get,
          osoite = h.answers.flatMap(a => a.henkilotiedot).flatMap(a =>
            (a.lahiosoite, a.Postinumero, a.Postitoimipaikka) match {
              case (Some(lahiosoite), Some(postinumero), Some(postitoimipaikka)) =>
                Some(s"${lahiosoite}, ${postinumero} ${postitoimipaikka}")
              case _ =>
                None
            }).get,

        )
    }
  }
}

case class ValpasQuery(oppijanumerot: Set[String])

class ValpasIntergration(hakemusService: IHakemusService) {
  implicit val ec: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))
  implicit val defaultTimeout: Timeout = 120.seconds

  def fetch(query: ValpasQuery): Future[Seq[ValpasHakemus]] = {
    //val masterOids: Future[Map[String, String]] = hakemusService.personOidstoMasterOids(query.oppijanumerot)

    val hakemukset: Future[Seq[HakijaHakemus]] = hakemusService.hakemuksetForPersons(query.oppijanumerot)

    hakemukset.map(a => a.map(ValpasHakemus(_)))
  }
}
