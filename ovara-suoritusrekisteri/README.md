# ovara-suoritusrekisteri #

Erillinen moduuli siirtotiedostojen ajastetulle luomiselle. Main-luokka SiirtotiedostoApp etsii käynnistyessään
sovelluksen kannasta viimeisimmän onnistuneen siirtotiedostojen muodostuksen aikaikkunan loppuhetken.
Uusi aikaikkuna on edellisen aikaikkunan lopusta nykyhetkeen.

Muodostaa erilliset siirtotiedostot aikaikkunassa muuttuneille tiedoille:
-Suoritukset
-Arvosanat
-Opiskelijat (luokkatiedot)
-Opiskeluoikeudet

Jos muuttuneita tietoja on paljon (konffiarvo suoritusrekisteri.ovara.pagesize), muodostuu useita tiedostoja per tyyppi.

Lisäksi kerran vuorokaudessa muodostetaan siirtotiedostot kaikkien seuraaville, jokainen haku tarpeen mukaan useaan erilliseen tiedostoon:
-aktiivisten hakujen päätellyt ensikertalaisuustiedot
-aktiivisten toisen asteen hakujen proxysuoritustiedot valintalaskentakoostepalvelusta
-aktiivisten toisen asteen yhteishakujen päätellyt harkinnanvaraisuustiedot valintalaskentakoostepalvelusta

Muodostetut tiedostot tallennetaan sovellukselle konffattuun s3-ämpäriin seuraavien konffiarvojen perusteella:
suoritusrekisteri.ovara.s3.region
suoritusrekisteri.ovara.s3.bucket
suoritusrekisteri.ovara.s3.target-role-arn

Sovelluksen ajoympäristö kts. cloud-base -> ovara-generic-stack.ts.

## Ajoympäristöjen versiot

- Java 11
- Scala 2.12

Käynnistetään ajamalla SiirtotiedostoApp-luokka. 

 