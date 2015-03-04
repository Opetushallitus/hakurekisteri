# Haku- ja valintarekisteri #

## Build & Run ##

```sh
$ git clone https://github.com/Opetushallitus/hakurekisteri.git
$ cd hakurekisteri
$ ./sbt
> ~container:start or container:start
> browse
```

##### Populate local H2 DB (requires: oph-configuration set up)
```
./sbt createTestDb
```

If `browse` doesn't launch your browser, manually open [http://localhost:8080/](http://localhost:8080/) in your browser.

API Documentation is available in URI /swagger/index.html after starting the container.

If you want sbt to build project automatically on changes, use

```
> ~container:start
```
