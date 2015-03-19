# Haku- ja valintarekisteri #


## Run mocha tests

1. Aja `./sbt compile` joka myös buildaa frontin.
2. Käynnistä mock-serveri IDEAsta: `JettyTestLauncher`.
3. Avaa testisivu selaimessa: http://localhost:8080/demo/runner.html

## Fronttidevaus

    npm run watch

## Build & Run ##

```sh
$ git clone https://github.com/Opetushallitus/hakurekisteri.git
$ cd hakurekisteri
$ ./sbt
> ~container:start or container:start
> browse
```

## Populate local H2 DB (requires: oph-configuration set up)

```
./sbt createTestDb
```

If `browse` doesn't launch your browser, manually open [http://localhost:8080/](http://localhost:8080/) in your browser.

API Documentation is available in URI /swagger/index.html after starting the container.

If you want sbt to build project automatically on changes, use

```
> ~container:start
```

## Arvosanavalidaattori

Arvosanojen tuonnissa käytettävä validaattori on erillisessä [repositoriossa](https://github.com/Opetushallitus/validaattori).

Validaattorin server-side -versio on buildattu sieltä Artifactoryyn ja on käytössä jarrina.

Validaattorin client-side -versio on buildattu ja kopioitu tämän [js-hakemistoon](web/src/main/webapp/static/js). Tämä päivitetään ajamalla skripti

    sbin/update_validator.sh

Edellyttäen että validaattori-repositorystä on klooni hakemistossa `../validator`.
