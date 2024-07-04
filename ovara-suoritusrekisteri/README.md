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

Lisäksi kerran vuorokaudessa muodostetaan siirtotiedostot kaikkien aktiivisten kk-hakujen päätellyille
ensikertalaisuustiedoille, jokainen haku erilliseen tiedostoon.

Muodostetut tiedostot tallennetaan sovellukselle konffattuun s3-ämpäriin seuraavien konffiarvojen perusteella:
suoritusrekisteri.ovara.s3.region
suoritusrekisteri.ovara.s3.bucket
suoritusrekisteri.ovara.s3.target-role-arn

Sovelluksen ajoympäristö kts. cloud-base -> ovara-generic-stack.ts.

## Ajoympäristöjen versiot

- Java 11
- Scala 2.12

Käynnistetään ajamalla SiirtotiedostoApp-luokka. 

 