(function () {
    function asyncPrint(s) {
        return function () {
            console.log(s)
        }
    }

    describe('Opiskelijatiedot', function () {
        var page = opiskelijatiedotPage()

        beforeEach(function (done) {
            page.openPage(done)
        })

        afterEach(function () {
            if (this.currentTest.state == 'failed') {
                takeScreenshot()
            }
        })

        describe("Haku", function () {
            it('Voi hakea oppilaitoksen perusteella - test-dsl', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaGeneric()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneJaTyyneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuoritukset()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
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
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().suorituksetLocal.aarnenSuoritukset()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
                    httpFixtures().komoLocal.komoTiedot()
                    koodistoFixtures()
                },
                input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                    expect(opiskelijatiedot.henkiloTiedot().is(':visible')).to.equal(true)
                    expect(opiskelijatiedot.suoritusTiedot().is(':visible')).to.equal(true)
                    expect(opiskelijatiedot.luokkaTiedot().is(':visible')).to.equal(true)
                }
            ))

            it('Voi hakea hetun perusteella - test.dsl', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuoritukset()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
                    httpFixtures().luokkaTiedotLocal.tyynenLuokkaTiedotHetulla()
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
                }
            ))

            it('Puuttuva hetu perusteella - test.dsl', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.foobar()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().suorituksetLocal.aarnenSuoritukset()
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

            function assertText(selector, val) {
                expect(selector().text().trim()).to.equal(val)
            }

            function assertValue(selector, val) {
                expect(selector().val().trim()).to.equal(val)
            }

            function areElementsVisible() {
                return opiskelijatiedot.henkiloTiedot().is(':visible') &&
                    opiskelijatiedot.suoritusTiedot().is(':visible') &&
                    opiskelijatiedot.luokkaTiedot().is(':visible')
            }

            it('Vaihtamalla henkilöä organisaatio haun listasta tiedot vaihtuvat - test.dsl', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaGeneric()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.tyyne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                    httpFixtures().henkiloPalveluService.aarneJaTyyneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuoritukset()
                    httpFixtures().suorituksetLocal.tyynenSuoritukset()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().arvosanatLocal.tyynenArvosanat()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
                    httpFixtures().luokkaTiedotLocal.tyynenLuokkaTiedot()
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
                    assertText(opiskelijatiedot.hetuTieto, "123456-789")
                    assertValue(opiskelijatiedot.suoritusMyontaja, "06345")
                    assertValue(opiskelijatiedot.luokkaTaso, "10A")
                },
                click(opiskelijatiedot.resultsTableChild(2)),
                wait.forAngular,
                function () {
                    expect(areElementsVisible()).to.equal(true)
                    assertText(opiskelijatiedot.hetuTieto, "010719-917S")
                    assertValue(opiskelijatiedot.suoritusMyontaja, "06345")
                    assertValue(opiskelijatiedot.luokkaTaso, "9A")
                }
            ))
        })
        
        describe('URL parametrin vaihto', function () {

            function getCurrentYear() {
                return new Date().getFullYear()
            }

            it('Henkilo haku URL parametri', seqDone(
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuoritukset()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
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
                }
            ))
            it('Organisaatio haku URL parametri', seqDone(
                function () {
                    httpFixtures().organisaatioService.pikkaralaGeneric()
                    httpFixtures().organisaatioService.pikkaralaKoodi()
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneJaTyyneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuoritukset()
                    httpFixtures().arvosanatLocal.aarnenArvosanat()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
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
                }
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
                        httpFixtures().henkiloPalveluService.aarne()
                        httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                        httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                        httpFixtures().suorituksetLocal.aarnenSuoritukset()
                        httpFixtures().arvosanatLocal.aarnenArvosanat()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
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

                    select(opiskelijatiedot.suoritusKieli, "2"),
                    function () {
                        httpFixtures().organisaatioService.pikkaralaGeneric()
                        httpFixtures().organisaatioService.pikkaralaKoodi()
                    },
                    typeaheadInput(opiskelijatiedot.suoritusMyontaja, "Pik", opiskelijatiedot.typeaheadMenuChild(1)),
                    saveEnabled(),
                    mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/suoritukset\/4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9$/),
                    function (savedData) {
                        expect(JSON.parse(savedData)).to.deep.equal({
                            henkiloOid: "1.2.246.562.24.71944845619",
                            source: "ophadmin",
                            vahvistettu: true,
                            komo: "1.2.246.562.13.62959769647",
                            myontaja: "1.2.246.562.10.39644336305",
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