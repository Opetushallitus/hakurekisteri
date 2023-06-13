package fi.vm.sade.hakurekisteri.web.kkhakija

import fi.vm.sade.hakurekisteri.integration.tarjonta.Hakukohteenkoulutus
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaHakemusUtil.filterTkKoulutuskoodi
import org.scalatest.{FlatSpec, Matchers}

class KkHakijaHakemusUtilSpec extends FlatSpec with Matchers {

  val tutkintoonJohtamatonKoulutusTuntematonTkKoodi = Hakukohteenkoulutus(
    "",
    "999999",
    None,
    None,
    None,
    None,
    None,
    Some(false)
  )

  val tutkintoonJohtamatonKoulutusTyhjaTkKoodi = Hakukohteenkoulutus(
    "",
    "",
    None,
    None,
    None,
    None,
    None,
    Some(false)
  )

  val tutkintoonJohtamatonKoulutusNullTkKoodi = Hakukohteenkoulutus(
    "",
    "",
    None,
    None,
    None,
    None,
    None,
    Some(false)
  )

  val koulutusJossaTutkintoonJohtamatonTietoTyhja = Hakukohteenkoulutus(
    "",
    "123456",
    None,
    None,
    None,
    None,
    None,
    None
  )

  val tutkintoonJohtavaKoulutusTuntematonTkKoodi = Hakukohteenkoulutus(
    "",
    "999999",
    None,
    None,
    None,
    None,
    None,
    Some(true)
  )

  val tutkintoonJohtavaKoulutusNormaaliTkKoodi = Hakukohteenkoulutus(
    "",
    "123456",
    None,
    None,
    None,
    None,
    None,
    Some(true)
  )

  behavior of "Tutkintoon johtamaton koulutustyyppi"

  it should "return tuntematon tkKoulutuskoodi as tuntematon for tutkintoon johtamaton" in {
    filterTkKoulutuskoodi(tutkintoonJohtamatonKoulutusTuntematonTkKoodi) should be("999999")
  }

  it should "return empty tkKoulutuskoodi as empty for tutkintoon johtamaton" in {
    filterTkKoulutuskoodi(tutkintoonJohtamatonKoulutusTyhjaTkKoodi) should be("")
  }

  it should "return null tkKoulutuskoodi as empty for tutkintoon johtamaton" in {
    filterTkKoulutuskoodi(tutkintoonJohtamatonKoulutusNullTkKoodi) should be("")
  }
  it should "return tkKoulutuskoodi if johtaaTutkintoon is None" in {
    filterTkKoulutuskoodi(koulutusJossaTutkintoonJohtamatonTietoTyhja) should be("123456")
  }

  it should "return tuntematon tkKoulutuskoodi for tutkintoon johtava" in {
    filterTkKoulutuskoodi(tutkintoonJohtavaKoulutusTuntematonTkKoodi) should be("999999")
  }

  it should "return tkKoulutuskoodi for tutkintoon johtava" in {
    filterTkKoulutuskoodi(tutkintoonJohtavaKoulutusNormaaliTkKoodi) should be("123456")
  }
}
