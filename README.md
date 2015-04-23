# Haku- ja valintarekisteri #


## Fronttidevaus ja mocha-testit

Setup:

1. Aja `./sbt compile` joka myös buildaa frontin.
2. Käynnistä mock-serveri IDEAsta: `JettyTestLauncher`.
3. Buildaa fronttikamat: `npm run watch`

Mocha-testit selaimessa: http://localhost:8080/test/runner.html

Alternative setup:

1. Hanki "~/oph-configuration"-hakemisto (kysy devaajilta apua)
2. Buildaa fronttikamat: `npm run watch`
3. Käynnistä "oikea" serveri IDEAsta: `HakuRekisteriTomcat`.

## Build & Run ##

Näin voit ajaa sovellusta paikallisesti tuotannonkaltaisena setuppina, käyttäen paikallista h2-kantaa.

1. Ihan ensin tarvitset devaukseen soveltuvan `~/oph-configuration`-hakemiston. Kysy devaajilta apua!

2. Luo paikallinen h2-tietokanta: `./sbt createTestDb`. Tämä kopioi datat luokka-ympäristöstä paikalliseen data-nimiseen hakemistoon.

3. Käynnistä paikallinen serveri: `./sbt ~container:start`

Sovellus on saatavilla osoitteessa http://localhost:8080/

Muutama huomio:

- Tarvitset selaimen, jossa cors-turvallisuuscheckit on pois päältä. Esim. voit käyttää tällaista: `alias chrometest="/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --user-data-dir=/tmp/chrometest2 --incognito --disable-web-security --disable-popup-blocking&"`
- Salasanat devaajilta
- Käy kirjautumassa sisään osoittessa https://itest-virkailija.oph.ware.fi että autentikaatio toimii

API-dokumentaatio löytyy http://localhost:8080//swagger/index.html

## Arvosanavalidaattori

Arvosanojen tuonnissa käytettävä validaattori on erillisessä [repositoriossa](https://github.com/Opetushallitus/validaattori).

Validaattorin server-side -versio on buildattu sieltä Artifactoryyn ja on käytössä jarrina. Validaattorin client-side -versio on bundlattu samaan jarriin ja serveröidään ValidatorJavasccriptServletin toimesta.
