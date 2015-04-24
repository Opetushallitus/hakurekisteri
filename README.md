# Haku- ja valintarekisteri #


## Fronttidevaus ja mocha-testit

Setup:

1. Laita fronttikäännös pyörimään: `npm run watch`. Tämä kääntää coffeescriptit aina kun ne muuttuvat.
2. Käynnistä serveri IDEAsta: `HakuRekisteriJetty`, käytä asetusta `-Dhakurekisteri.profile=it` jolloin serveri toimii ilman ulkoisia depsuja
3. Buildaa fronttikamat: `npm run watch`
4. Aja Mocha-testit selaimessa: http://localhost:8080/test/runner.html

Mocha-testit käyttävät tällä hetkellä suurelta osin frontend-mockeja, joten ne eivät juurikaan testaa serverikoodia.

## Kaikki testit

Voit ajaa kaikki testit komentoriviltä komennolla `./sbt test`. Tämä ajaa myös mocha-testit phantomjs:llä.

## Build & Run ##

Näin voit ajaa sovellusta paikallisesti tuotannonkaltaisena setuppina, käyttäen paikallista h2-kantaa.

1. Ihan ensin tarvitset devaukseen soveltuvan `~/oph-configuration`-hakemiston. Kysy devaajilta apua!

2. Luo paikallinen h2-tietokanta: `./sbt createTestDb`. Tämä kopioi datat luokka-ympäristöstä paikalliseen data-nimiseen hakemistoon.

3. Käynnistä paikallinen serveri: `./sbt ~container:start`. Vaihtoehtoisesti aja IDEA:ssa luokka `HakuRekisteriJetty`.

Sovellus on saatavilla osoitteessa http://localhost:8080/

Muutama huomio:

- Katso tunnus ja salasana `~/oph-configuration/security-context-backend.xml`:stä, käyttäjällä pitää olla ainakin `ROLE_APP_SUORITUSREKISTERI` rooli
- Käy kirjautumassa sisään osoittessa https://itest-virkailija.oph.ware.fi, jotta autentikaatio muihin palveluihin toimii

API-dokumentaatio löytyy http://localhost:8080/swagger/index.html

## Arvosanavalidaattori

Arvosanojen tuonnissa käytettävä validaattori on erillisessä [repositoriossa](https://github.com/Opetushallitus/validaattori).

Validaattorin server-side -versio on buildattu sieltä Artifactoryyn ja on käytössä jarrina. Validaattorin client-side -versio on bundlattu samaan jarriin ja serveröidään ValidatorJavasccriptServletin toimesta.
