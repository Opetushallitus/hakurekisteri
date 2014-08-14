package fi.vm.sade.hakurekisteri.virta

object TestResponse {

  val xml = <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
    <SOAP-ENV:Header/>
    <SOAP-ENV:Body>
      <virtaluku:OpiskelijanKaikkiTiedotResponse xmlns:virtaluku="http://tietovaranto.csc.fi/luku">
        <virta:Virta xmlns:virta="urn:mace:funet.fi:virta/2013/01/29">
          <virta:Opiskelija avain="1594">
            <virta:Henkilotunnus>100686-162E</virta:Henkilotunnus>
            <virta:KansallinenOppijanumero>fed01ai787a8cj576b76bsd</virta:KansallinenOppijanumero>
            <virta:Opiskeluoikeudet>
              <virta:Opiskeluoikeus avain="16037" opiskelijaAvain="1594">
                <virta:AlkuPvm>2008-01-07</virta:AlkuPvm>
                <virta:LoppuPvm>2011-12-21</virta:LoppuPvm>
                <virta:Tila>
                  <virta:AlkuPvm>2008-01-07</virta:AlkuPvm>
                  <virta:LoppuPvm>2011-12-21</virta:LoppuPvm>
                  <virta:Koodi>3</virta:Koodi>
                </virta:Tila>
                <virta:Tyyppi>1</virta:Tyyppi>
                <virta:Myontaja>
                  <virta:Koodi>XX</virta:Koodi>
                  <virta:Osuus>1.000000</virta:Osuus>
                </virta:Myontaja>
                <virta:Jakso koulutusmoduulitunniste="XX3">
                  <virta:AlkuPvm>2008-01-07</virta:AlkuPvm>
                  <virta:LoppuPvm>2011-12-21</virta:LoppuPvm>
                  <virta:Koulutuskoodi>671116</virta:Koulutuskoodi>
                  <virta:Koulutuskunta>999</virta:Koulutuskunta>
                  <virta:Koulutuskieli>fi</virta:Koulutuskieli>
                  <virta:Rahoituslahde>1</virta:Rahoituslahde>
                </virta:Jakso>
                <virta:Laajuus>
                  <virta:Opintopiste>240.000000</virta:Opintopiste>
                  <virta:Opintoviikko>133.333333</virta:Opintoviikko>
                </virta:Laajuus>
                <virta:Aikuiskoulutus>0</virta:Aikuiskoulutus>
              </virta:Opiskeluoikeus>
            </virta:Opiskeluoikeudet>
            <virta:Opintosuoritukset>
            </virta:Opintosuoritukset>
          </virta:Opiskelija>
        </virta:Virta>
      </virtaluku:OpiskelijanKaikkiTiedotResponse>
    </SOAP-ENV:Body>
  </SOAP-ENV:Envelope>

}
