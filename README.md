# Suoritusrekisteri #


## Fronttidevaus ja mocha-testit

Setup:

Projekti ei näytä toimivan Node 6:tta uudemmilla versioilla (tulee `Error: Cannot find module 'internal/fs'`).
Asenna Node 6 [N-työkalulla](https://www.npmjs.com/package/n) (asenna se globaalisti npm:llä). 

1. Laita fronttikäännös pyörimään: `npm run watch`. Tämä kääntää coffeescriptit aina kun ne muuttuvat.
2. Käynnistä serveri IDEAsta: `SureTestJettyWithMocks`, jolloin serveri toimii ilman ulkoisia depsuja
3. Aja Mocha-testit selaimessa: http://localhost:8080/test/runner.html

Mocha-testit käyttävät tällä hetkellä suurelta osin frontend-mockeja, joten ne eivät juurikaan testaa serverikoodia.

Tarvittaessa voit päivittää front-riippuvuudet komennolla

    bower update

## Testit

Voit ajaa kaikki testit komentoriviltä komennolla `mvn clean test`

Voit ajaa yksittäisen test suiten komennolla `mvn test -Dsuites=fi.vm.sade.hakurekisteri.rest.OppilaitosResourceSpec`

## Build & Run ##

Näin voit ajaa sovellusta paikallisesti tuotannonkaltaisena setuppina, käyttäen paikallista h2-kantaa.

1. Ihan ensin tarvitset devaukseen soveltuvan `~/oph-configuration`-hakemiston, kopioi luokalta tai pyydä kaverilta.

2. Lisää/muokkaa `~/oph-configuration/common.properties`-tiedostoon seuraavat propertyt (jotta cas-ohjaukset toimivat oikein):
```
cas.callback.suoritusrekisteri=http://localhost:8080/suoritusrekisteri
cas.service.suoritusrekisteri=http://localhost:8080/suoritusrekisteri
```
Kommentoi pois ValintaTulosActor-luokan getSijoittelut-metodin ensimmäinen haara jotta käynnistyminen tapahtuu järkevässä ajassa:
```
private def getSijoittelu(q: ValintaTulosQuery): Future[SijoitteluTulos] = {
    //if (!initialLoadingDone) {
    //  Future.failed(InitialLoadingNotDone())
    //} else {
      if (q.cachedOk && cache.contains(q.hakuOid))
        cache.get(q.hakuOid)
      else {
        if (q.hakemusOid.isEmpty) {
          queueForResult(q.hakuOid)
        } else {
          callBackend(q.hakuOid, q.hakemusOid)
        }
      }
    //}
  }
```

3. Käynnistä paikallinen serveri ajamalla IDEA:ssa luokka `SureTestJetty`.

Sovellus on saatavilla osoitteessa http://localhost:8080/suoritusrekisteri

Muutama huomio:

- Katso tunnus ja salasana `~/oph-configuration/security-context-backend.xml`:stä, käyttäjällä pitää olla ainakin `ROLE_APP_SUORITUSREKISTERI` rooli

API-dokumentaatio löytyy http://localhost:8080/suoritusrekisteri/swagger/index.html

## Kehitystietokannat
Sovellusta paikallisesti ajettaessa se käyttää properties-tiedostossa määriteltyä Postgres endpointtia (suoritusrekisteri.db.url).

Paikallisesti ajettaessa käynnistä Postgres-palvelinprosessi esim. Dockerilla: `docker run -p 5432:5432 postgres`

Testeissä sovellus käyttää sisäistä Postgres-kantaa (ItPostgres.scala).

## Arvosanavalidaattori

Arvosanojen tuonnissa käytettävä validaattori on erillisessä [repositoriossa](https://github.com/Opetushallitus/validaattori).

Validaattorin server-side -versio on buildattu sieltä Artifactoryyn ja on käytössä jarrina. Validaattorin client-side -versio on bundlattu samaan jarriin ja serveröidään ValidatorJavasccriptServletin toimesta.


## Admin / Testauksen huomioita

* Hakemusten synkronoinnin voi pakottaa kutsumalla GET /suoritusrekisteri/rest/v1/haut/refresh/hakemukset
(synkronointi ajetaan automaattisesti parin tunnin välein)

## Formatointi

Formatoinnin tarkistus Scala-koodille saa ajettua suorittamalla 

`mvn spotless:check`

Korjattua nämä saa ajamalla

`mvn spotless:apply`

Lisätietoa https://github.com/diffplug/spotless/tree/master/plugin-maven

## Java-versio

Käytössä oleva Java-versio on 11
