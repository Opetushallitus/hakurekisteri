(function () {
    function asyncPrint(s) {
        return function () {
            console.log(s)
        }
    }

    describe('Opiskelijatiedot', function () {
        var page = opiskelijatiedotPage()

        beforeEach(function (done) {
            addTestHook(lokalisointiFixtures)()
            page.openPage(done)
        })

        afterEach(function () {
            if (this.currentTest.state == 'failed') {
                takeScreenshot()
            }
        })

        describe("Organisaatiohaku", function () {
            it('Voi hakea oppilaitoksen perusteella - test-dsl', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneJaTyyneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    httpFixtures().rekisteriTiedotLocal.rekisteriTiedot()
                    koodistoFixtures()
                },
                typeaheadInput(opiskelijatiedot.organizationSearch, "Pik", opiskelijatiedot.typeaheadMenuChild(1)),
                wait.forAngular,
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(opiskelijatiedot.resultsTable().length).to.equal(5)
                    assertText(opiskelijatiedot.hetu, "123456-789")
                }
            ))
            it('Virheellinen oppilaitos haku', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.foobarKoulu()
                },
                input(opiskelijatiedot.organizationSearch, "foobar"),
                wait.forAngular,
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(opiskelijatiedot.resultsTable().length).to.equal(0)
                }
            ))
        })

        describe('Henkilohaku', function () {
            it('Voi hakea oidin perusteella - test-dsl', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    koodistoFixtures()
                },
                input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                    assertText(opiskelijatiedot.hetu, "123456-789")
                }
            ))

            it('Voi hakea hetun perusteella', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    koodistoFixtures()
                },
                input(opiskelijatiedot.henkiloSearch, '123456-789'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                    assertText(opiskelijatiedot.hetu, "123456-789")
                }
            ))

            it('Puuttuva hetu perusteella', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.foobar()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().suorituksetLocal.aarnenSuoritus()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
                },
                input(opiskelijatiedot.henkiloSearch, 'foobar'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(opiskelijatiedot.resultsTable().length).to.equal(0)
                    expect(opiskelijatiedot.henkiloTiedot().is(':visible')).to.equal(false)
                    expect(opiskelijatiedot.suoritusTiedot().is(':visible')).to.equal(false)
                    expect(opiskelijatiedot.luokkaTiedot().is(':visible')).to.equal(false)
                }
            ))
        })

        describe('Oppilaiden valitseminen/etsiminen organisaatiossa', function () {

            function areElementsVisible() {
                return opiskelijatiedot.henkiloTiedot().is(':visible') &&
                    opiskelijatiedot.suoritusTiedot().is(':visible') &&
                    opiskelijatiedot.luokkaTiedot().is(':visible')
            }

            it('Vaihtamalla henkilöä organisaatio haun listasta tiedot vaihtuvat', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.tyyne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                    httpFixtures().henkiloPalveluService.aarneJaTyyneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().suorituksetLocal.tyynenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.tyynenLuokkaTiedotEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    httpFixtures().rekisteriTiedotLocal.rekisteriTiedot()
                    koodistoFixtures()
                },
                typeaheadInput(opiskelijatiedot.organizationSearch, "Pik", opiskelijatiedot.typeaheadMenuChild(1)),
                wait.forAngular,
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                click(opiskelijatiedot.resultsTableChild(1)),
                wait.forAngular,
                function () {
                    expect(areElementsVisible()).to.equal(true)
                    assertText(opiskelijatiedot.hetu, "123456-789")
                },
                click(opiskelijatiedot.resultsTableChild(2)),
                wait.forAngular,
                function () {
                    expect(areElementsVisible()).to.equal(true)
                    assertText(opiskelijatiedot.hetu, "010719-917S")
                }
            ))
        })
        
        describe('URL parametrien käsittely', function () {

            function getCurrentYear() {
                return new Date().getFullYear()
            }

            it('Henkilo haku URL parametri', seqDone(
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    koodistoFixtures()
                },
                input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                wait.forAngular,
                select(opiskelijatiedot.vuosiSearch, '2'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(testFrame().location.hash).to.equal('#/muokkaa-obd?henkilo=1.2.246.562.24.71944845619&oppilaitos=&vuosi='+getCurrentYear())
                    assertText(opiskelijatiedot.hetu, "123456-789")
                }
            ))
            it('Organisaatio haku URL parametri', seqDone(
                function () {
                    httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneJaTyyneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    httpFixtures().rekisteriTiedotLocal.rekisteriTiedot()
                    koodistoFixtures()
                },
                typeaheadInput(opiskelijatiedot.organizationSearch, "Pik", opiskelijatiedot.typeaheadMenuChild(1)),
                wait.forAngular,
                select(opiskelijatiedot.vuosiSearch, '2'),
                wait.forAngular,
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(testFrame().location.hash).to.equal('#/muokkaa-obd?henkilo=&oppilaitos=06345&vuosi='+getCurrentYear())
                    assertText(opiskelijatiedot.hetu, "123456-789")
                }
            ))
        })

        describe("Tietojen näyttö", function() {
            function testAlertText (selector, expected) {
                return function() {
                    expect(selector().text().indexOf(expected)).not.to.equal(-1)
                }
            }

            it('Opiskelijan suoritukset, arvosanat, luokkatiedot ja opintooikeudet näkyvät oikein', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuoritus()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
                    httpFixtures().komoLocal.komoTiedot()
                    koodistoFixtures()
                },
                input(opiskelijatiedot.henkiloSearch, '123456-789'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                    expect(opiskelijatiedot.henkiloTiedot().is(':visible')).to.equal(true)
                    expect(opiskelijatiedot.suoritusTiedot().is(':visible')).to.equal(true)
                    expect(opiskelijatiedot.luokkaTiedot().is(':visible')).to.equal(true)
                    assertText(opiskelijatiedot.hetu, "123456-789")
                    assertValue(opiskelijatiedot.suoritusMyontaja, "06345")
                    assertValue(opiskelijatiedot.suoritusKoulutus, "1")
                    assertValue(opiskelijatiedot.suoritusYksilollistetty, "0")
                    assertValue(opiskelijatiedot.suoritusKieli, "156")
                    assertValue(opiskelijatiedot.suoritusValmistuminen, "3.6.2015")
                    assertValue(opiskelijatiedot.suoritusTila, "0")
                    assertValue(opiskelijatiedot.luokkatietoOppilaitos, "06345")
                    assertValue(opiskelijatiedot.luokkatietoLuokka, "10A")
                    assertValue(opiskelijatiedot.luokkatietoLuokkaTaso, "2")
                    assertValue(opiskelijatiedot.luokkatietoAlkuPaiva, "18.8.2014")
                    assertValue(opiskelijatiedot.luokkatietoLoppuPaiva, "4.6.2015")
                }
            ))


            it("Vahvistamattomalle suoritukselle näytetään info-viesti", seqDone(
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().suorituksetLocal.aarnenVahvistamatonSuoritus()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
                    httpFixtures().komoLocal.komoTiedot()
                    koodistoFixtures()
                },
                input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                visible(opiskelijatiedot.hakijanIlmoittamaAlert),
                testAlertText(opiskelijatiedot.hakijanIlmoittamaAlert, "Suoritus ei ole vahvistettu")
            ))
            it("Hakijan ilmoittamalle suoritukselle näytetään info-viesti", seqDone(
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().suorituksetLocal.aarnenVahvistamatonSuoritusHakemukselta()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
                    httpFixtures().komoLocal.komoTiedot()
                    koodistoFixtures()
                },
                input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                visible(opiskelijatiedot.hakijanIlmoittamaAlert),
                testAlertText(opiskelijatiedot.hakijanIlmoittamaAlert, "Suoritus hakijan ilmoittama")
            ))

        })

        describe('Suoritustietojen muokkaus', function () {
                function saveEnabled() {
                    return waitJqueryIs(opiskelijatiedot.saveButton, ":disabled", false)
                }

                function saveDisabled() {
                    return waitJqueryIs(opiskelijatiedot.saveButton, ":disabled", true)
                }

                it("Peruskoulun suoritustiedot ja arvosanat talletetaan vain jos muuttuneita arvoja", seqDone(
                    function () {
                        httpFixtures().organisaatioService.pikkaralaOid()
                        httpFixtures().organisaatioService.pikkaralaKoodi()
                        httpFixtures().henkiloPalveluService.aarne()
                        httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                        httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                        httpFixtures().suorituksetLocal.aarnenSuoritus()
                        httpFixtures().arvosanatLocal.aarnenArvosanat()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().komoLocal.komoTiedot()
                        koodistoFixtures()
                    },
                    input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                    click(opiskelijatiedot.searchButton),
                    wait.forAngular,

                    saveDisabled(),
                    select(opiskelijatiedot.suoritusKoulutus, "2"),
                    saveEnabled(),
                    select(opiskelijatiedot.suoritusKoulutus, "1"),

                    saveDisabled(),
                    select(opiskelijatiedot.suoritusYksilollistetty, "2"),
                    saveEnabled(),
                    select(opiskelijatiedot.suoritusYksilollistetty, "0"),

                    saveDisabled(),
                    select(opiskelijatiedot.suoritusTila, "1"),
                    saveEnabled(),
                    select(opiskelijatiedot.suoritusTila, "0"),

                    saveDisabled(),
                    function () {
                        httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                        httpFixtures().organisaatioService.pikkoloKoodi()
                        httpFixtures().organisaatioService.pikkoloOid()
                    },
                    typeaheadInput(opiskelijatiedot.suoritusMyontaja, "Pik", opiskelijatiedot.typeaheadMenuChild(2)),
                    saveEnabled(),
                    typeaheadInput(opiskelijatiedot.suoritusMyontaja, "Pik", opiskelijatiedot.typeaheadMenuChild(1)),
                    saveDisabled(),

                    select(opiskelijatiedot.suoritusKieli, "2"),
                    saveEnabled(),
                    mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/suoritukset\/4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9$/),
                    function (savedData) {
                        expect(JSON.parse(savedData)).to.deep.equal({
                            henkiloOid: "1.2.246.562.24.71944845619",
                            source: "ophadmin",
                            vahvistettu: true,
                            komo: "1.2.246.562.13.62959769647",
                            myontaja: restData.organisaatioService.pikkarala.oid,
                            tila: "KESKEN",
                            valmistuminen: "03.06.2015",
                            yksilollistaminen: "Ei",
                            suoritusKieli: "PS",
                            id: "4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9"
                        })
                    },
                    saveDisabled()
                ))
            }
        )
    })
})()