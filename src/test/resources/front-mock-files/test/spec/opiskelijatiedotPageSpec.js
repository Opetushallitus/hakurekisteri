(function () {
    function asyncPrint(s) {
        return function () {
            console.log(s)
        }
    }

    function getDefaultYear() {
        var now = new Date()
        if (now.getMonth() > 6) {
            return now.getFullYear() + 1
        }
        return now.getFullYear()
    }

    function assertArvosanat(aineRiviCount, aineCount, korotusDateCount, pakollisetCount, valinnaisetCount) {
        expect(opiskelijatiedot.arvosanaAineRivi().length).to.equal(aineRiviCount)
        expect(jQuery.unique(textArray(opiskelijatiedot.arvosanaAineNimi)).length).to.equal(aineCount)
        expect(opiskelijatiedot.arvosanaMyonnetty().length).to.equal(korotusDateCount)
        expect(opiskelijatiedot.arvosanaPakollinenArvosana().length).to.equal(pakollisetCount)
        expect(opiskelijatiedot.arvosanaValinnainenArvosana().filter(nonEmpty).length).to.equal(valinnaisetCount)
    }

    function assertOpintopolkuVastaanotot(haut, hakukohteet, tilat, paivamaarat) {
        expect(textArray(opiskelijatiedot.vastaanottoOpintopolkuHaku)).to.deep.equal(haut)
        expect(textArray(opiskelijatiedot.vastaanottoOpintopolkuHakukohde)).to.deep.equal(hakukohteet)
        expect(textArray(opiskelijatiedot.vastaanottoOpintopolkuTila)).to.deep.equal(tilat)
        expect(textArray(opiskelijatiedot.vastaanottoOpintopolkuPaivamaara)).to.deep.equal(paivamaarat)
    }

    function assertVanhatVastaanotot(hakukohteet, paivamaarat) {
        expect(textArray(opiskelijatiedot.vastaanottoVanhaHakukohde)).to.deep.equal(hakukohteet)
        expect(textArray(opiskelijatiedot.vastaanottoVanhaPaivamaara)).to.deep.equal(paivamaarat)
    }

    function nonEmpty(i, e) {
        return jQuery(e).text().trim().length > 0
    }

    function findAineRivi(aineTxt, myonnetty, lisatieto) {
        return opiskelijatiedot.arvosanaAineRivi().filter(function (i, rivi) {
            var aine = jQuery(rivi).find(opiskelijatiedot.arvosanaAineNimi().selector).text();
            var m = jQuery(rivi).find(opiskelijatiedot.arvosanaMyonnetty().selector).text();
            var l = jQuery(rivi).find(opiskelijatiedot.arvosanaLisatieto().selector).text();
            return aine == aineTxt && m == myonnetty && l == lisatieto
        })
    }

    function txtArr(i, e) {
        return jQuery(e).text();
    }

    function assertArvosanaRivi(aineTxt, myonnetty, lisatieto, pakolliset, valinnaiset) {
        if (myonnetty.length > 0) {
            myonnetty = "(" + myonnetty + ")"
        }
        var aineRivit = findAineRivi(aineTxt, myonnetty, lisatieto)
        expect(aineRivit.length).to.equal(1, "found " + aineRivit.length + " matching aineRivi when there was supposed to be 1: " + aineTxt + ": " + myonnetty + ": " + lisatieto)
        var aineRivi = jQuery(aineRivit[0])
        var p = jQuery.makeArray(jQuery(aineRivi).find(opiskelijatiedot.arvosanaPakollinenArvosana().selector).map(txtArr))
        var v = jQuery.makeArray(jQuery(aineRivi).find(opiskelijatiedot.arvosanaValinnainenArvosana().selector).filter(nonEmpty).map(txtArr))
        expect([aineTxt, myonnetty, lisatieto, p, v]).to.deep.equal([aineTxt, myonnetty, lisatieto, pakolliset, valinnaiset])
    }

    describe('Opiskelijatiedot', function () {
        var page = opiskelijatiedotPage()

        beforeEach(function (done) {
            addTestHook(lokalisointiFixtures)()
            addTestHook(koodistoFixtures)()
            addTestHook(vastaanottotiedotFixtures)()
            addTestHook(tarjontaNimiFixtures)()
            page.openPage(done)
        })

        afterEach(function () {
            if (this.currentTest.state == 'failed') {
                takeScreenshot()
            }
        })

        function saveEnabled() {
            return waitJqueryIs(opiskelijatiedot.saveButton, ":disabled", false)
        }

        function saveDisabled() {
            return waitJqueryIs(opiskelijatiedot.saveButton, ":disabled", true)
        }

        describe("Organisaatiohaku", function () {
            it.skip('!! Voi hakea oppilaitoksen numeron perusteella', seqDone(

            ))
            it('Voi hakea oppilaitoksen nimen perusteella', seqDone(
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
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    httpFixtures().rekisteriTiedotLocal.rekisteriTiedot()
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
            it.skip('!! Voi hakea oppilaitoksen nimen ja vuoden perusteella', seqDone(

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
            it('Voi hakea oidin perusteella', seqDone(
                wait.forAngular,
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                    httpFixtures().komoLocal.komoTiedot()
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
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                    httpFixtures().komoLocal.komoTiedot()
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
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                    httpFixtures().suorituksetLocal.tyynenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.tyynenLuokkaTiedotEmpty()
                    httpFixtures().opiskeluOikeudetLocal.tyynenOpiskeluOikeudetEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    httpFixtures().rekisteriTiedotLocal.rekisteriTiedot()
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
            it('Henkilo haku URL parametri', seqDone(
                function () {
                    httpFixtures().organisaatioService.pikkaralaOid()
                    httpFixtures().henkiloPalveluService.aarne()
                    httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                    httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                    httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                },
                input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                wait.forAngular,
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(testFrame().location.hash).to.equal('#/opiskelijat?henkilo=1.2.246.562.24.71944845619&oppilaitos=&vuosi=' + getDefaultYear())
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
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    httpFixtures().rekisteriTiedotLocal.rekisteriTiedot()
                },
                typeaheadInput(opiskelijatiedot.organizationSearch, "Pik", opiskelijatiedot.typeaheadMenuChild(1)),
                wait.forAngular,
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(testFrame().location.hash).to.equal('#/opiskelijat?henkilo=&oppilaitos=06345&vuosi=' + getDefaultYear())
                    assertText(opiskelijatiedot.hetu, "123456-789")
                }
            ))
        })

        describe("Tietojen näyttö", function () {
            function testAlertText(selector, expected) {
                return function () {
                    expect(selector().text().indexOf(expected)).not.to.equal(-1)
                }
            }

            describe('Vahvistetut', function () {
                describe('Opiskelijan peruskoulun suoritukset, ainelista, arvosanat, luokkatiedot, opintooikeudet ja vastaanotot', function() {
                    beforeEach(seqDone(
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
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot(getDefaultYear())
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeus()
                            httpFixtures().komoLocal.komoTiedot()
                        },
                        input(opiskelijatiedot.henkiloSearch, '123456-789'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular
                    ))
                    it('näkyvät oikein',seqDone(
                        function () {
                            expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                            expect(opiskelijatiedot.henkiloTiedot().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.suoritusTiedot().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.luokkaTiedot().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.arvosanaValinnainenHeader().is(":visible")).to.equal(true)
                            assertText(opiskelijatiedot.hetu, "123456-789")
                            assertValue(opiskelijatiedot.suoritusMyontaja, "06345")
                            assertSelectedDropdownItem(opiskelijatiedot.suoritusKoulutus, "Peruskoulu")
                            assertValue(opiskelijatiedot.suoritusYksilollistetty, "0")
                            assertValue(opiskelijatiedot.suoritusKieli, "156")
                            assertValue(opiskelijatiedot.suoritusValmistuminen, "3.6.2015")
                            assertValue(opiskelijatiedot.suoritusTila, "0")
                            assertValue(opiskelijatiedot.luokkatietoOppilaitos, "06345")
                            assertValue(opiskelijatiedot.luokkatietoLuokka, "10A")
                            assertValue(opiskelijatiedot.luokkatietoLuokkaTaso, "3")
                            assertValue(opiskelijatiedot.luokkatietoAlkuPaiva, "18.8.2014")
                            assertValue(opiskelijatiedot.luokkatietoLoppuPaiva, "4.6.2015")
                            assertText(opiskelijatiedot.opiskeluoikeusAlkuPaiva, "01.01.2000")
                            assertText(opiskelijatiedot.opiskeluoikeusLoppuPaiva, "01.01.2014")
                            assertText(opiskelijatiedot.opiskeluoikeusMyontaja, "06345 Pikkaralan ala-aste")
                            assertText(opiskelijatiedot.opiskeluoikeusKoulutus, "Ensihoitaja (AMK)")
                            assertArvosanat(37, 19, 14, 17, 1)
                            assertArvosanaRivi("Äidinkieli ja kirjallisuus", "", "Kieli puuttuu!!", ["10"], [])
                            assertArvosanaRivi("A1-kieli", "", "englanti", ["9"], [])
                            assertArvosanaRivi("Matematiikka", "", "", ["6"], [])
                            assertArvosanaRivi("Matematiikka", "04.06.2015", "", ["10"], ["9"])
                            assertOpintopolkuVastaanotot(['Testihaku', '1.2.246.561.29.00000000002'],
                                                         ['Testi hakukohde', '1.2.246.561.20.00000000002'],
                                                         ['VastaanotaEhdollisesti', 'VastaanotaSitovasti'],
                                                         ['01.07.2015', '01.07.2014'])
                            assertVanhatVastaanotot(["Vanhan hakukohteen nimi:101"], ["19.06.2014"])
                        },
                        saveDisabled()
                    ))
                })
                describe('Opiskelijan lukion suoritukset, ainelista ja arvosanat', function() {
                    beforeEach(seqDone(
                        wait.forAngular,
                        function () {
                            suoritus = {}
                            suoritus = jQuery.extend(suoritus, restData.suoritusRekisteri.suoritukset.aarneLukio)
                            suoritus.komo = restData.komo.lukioKomoOid
                            httpFixtures().organisaatioService.pikkaralaOid()
                            httpFixtures().organisaatioService.pikkaralaKoodi()
                            httpFixtures().henkiloPalveluService.aarne()
                            httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                            httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                            testFrame().httpBackend.when('GET', serviceUrls.suoritukset.henkilo("1.2.246.562.24.71944845619")).respond([suoritus])
                            httpFixtures().arvosanatLocal.aarnenLukioArvosanat()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                            httpFixtures().komoLocal.komoTiedot()
                        },
                        input(opiskelijatiedot.henkiloSearch, '123456-789'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular
                    ))
                    it('näkyvät oikein',seqDone(
                        function () {
                            expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                            expect(opiskelijatiedot.henkiloTiedot().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.suoritusTiedot().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.luokkaTiedot().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.arvosanaValinnainenHeader().is(":visible")).to.equal(false)
                            assertText(opiskelijatiedot.hetu, "123456-789")
                            assertValue(opiskelijatiedot.suoritusMyontaja, "06345")
                            assertSelectedDropdownItem(opiskelijatiedot.suoritusKoulutus, "TODO lukio komo oid")
                            assertValue(opiskelijatiedot.suoritusYksilollistetty, "0")
                            assertValue(opiskelijatiedot.suoritusKieli, "156")
                            assertValue(opiskelijatiedot.suoritusValmistuminen, "4.6.2015")
                            assertValue(opiskelijatiedot.suoritusTila, "0")
                            assertArvosanat(26, 20, 0, 16, 0)
                            assertArvosanaRivi("Äidinkieli ja kirjallisuus", "", "Kieli puuttuu!!", ["10"], [])
                            assertArvosanaRivi("A1-kieli", "", "englanti", ["9"], [])
                            assertArvosanaRivi("Matematiikka", "", "", ["10"], [])
                            assertArvosanaRivi("Psykologia", "", "", ['8'], [])
                            assertArvosanaRivi("Filosofia", "", "", ['6'], [])
                        },
                        saveDisabled()
                    ))
                })
                describe('Opiskelijan amk suoritus: komo = koulutus_*', function() {
                    beforeEach(seqDone(
                        wait.forAngular,
                        function () {
                            suoritus = {}
                            suoritus = jQuery.extend(suoritus, restData.suoritusRekisteri.suoritukset.aarne)
                            suoritus.komo = "koulutus_671116"
                            httpFixtures().organisaatioService.pikkaralaOid()
                            httpFixtures().organisaatioService.pikkaralaKoodi()
                            httpFixtures().henkiloPalveluService.aarne()
                            httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                            httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                            testFrame().httpBackend.when('GET', serviceUrls.suoritukset.henkilo("1.2.246.562.24.71944845619")).respond([suoritus])
                            httpFixtures().arvosanatLocal.aarnenArvosanat()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                            httpFixtures().komoLocal.komoTiedot()
                        },
                        input(opiskelijatiedot.henkiloSearch, '123456-789'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular
                    ))
                    it('näkyy oikein eikä arvosanoja näytetä', seqDone(
                        function () {
                              expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                              expect(opiskelijatiedot.henkiloTiedot().is(':visible')).to.equal(true)
                              expect(opiskelijatiedot.suoritusTiedot().is(':visible')).to.equal(true)
                              expect(opiskelijatiedot.luokkaTiedot().is(':visible')).to.equal(true)
                              assertText(opiskelijatiedot.hetu, "123456-789")
                              assertText(opiskelijatiedot.suoritusMyontaja, '06345 Pikkaralan ala-aste')
                              assertText(opiskelijatiedot.suoritusKoulutus, "Ensihoitaja (AMK)")
                              assertText(opiskelijatiedot.suoritusYksilollistetty, "Ei yksilöllistettyjä oppiaineita")
                              assertText(opiskelijatiedot.suoritusKieli, "suomi")
                              assertText(opiskelijatiedot.suoritusValmistuminen, "3.6.2015")
                              assertText(opiskelijatiedot.suoritusTila, "Suoritus kesken")
                              assertArvosanat(0, 0, 0, 0, 0)
                          },
                          saveDisabled()
                    ))
                })
                describe('YTL:n lähettämää YO-suoritus ja arvosanat', function() {
                    beforeEach(seqDone(
                        wait.forAngular,
                        function () {
                            httpFixtures().henkiloPalveluService.aarne()
                            httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                            httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                            httpFixtures().get(serviceUrls.suoritukset.henkilo("1.2.246.562.24.71944845619"), [restData.suoritusRekisteri.suoritukset.aarneYoYTL])
                            httpFixtures().get(serviceUrls.arvosanat.suoritus(restData.suoritusRekisteri.suoritukset.aarneYoYTL.id), restData.suoritusRekisteri.arvosanat.aarneYoYTL)
                            httpFixtures().get(serviceUrls.organisaatio(restData.organisaatioService.ytl.oid), restData.organisaatioService.ytl)
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                            httpFixtures().komoLocal.komoTiedot()
                        },
                        input(opiskelijatiedot.henkiloSearch, '123456-789'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular
                    ))
                    it('YO-suoritusta ja sen arvosanoja ei voi muokata. Vanhempia arvosanoja voi muokata', seqDone(
                        function () {
                            expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                            expect(opiskelijatiedot.henkiloTiedot().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.suoritusTiedot().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.luokkaTiedot().is(':visible')).to.equal(true)
                            assertText(opiskelijatiedot.hetu, "123456-789")
                            assertText(opiskelijatiedot.suoritusMyontaja, 'Ylioppilastutkintolautakunta')
                            assertText(opiskelijatiedot.suoritusKoulutus, "Ylioppilastutkinto")
                            assertText(opiskelijatiedot.suoritusYksilollistetty, "Ei yksilöllistettyjä oppiaineita")
                            assertText(opiskelijatiedot.suoritusKieli, "suomi")
                            assertText(opiskelijatiedot.suoritusValmistuminen, "1.6.2013")
                            assertText(opiskelijatiedot.suoritusTila, "Suoritus valmis")
                            expect(opiskelijatiedot.arvosanaAineRivi().length).to.equal(3)
                            assertText(opiskelijatiedot.yoTxt, 'Ortodoksiuskonto',
                                'Ainemuotoinen reaali',
                                'C',
                                '4',
                                '01.06.2013',
                                'Pakollinen',
                                'Matematiikka',
                                'Pitkä oppimäärä (MA)',
                                'C',
                                '4',
                                '02.06.2013')
                            expect(opiskelijatiedot.yoArvosanaAddKoe().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.suoritusPoista().is(':visible')).to.equal(false)
                        },
                        saveDisabled(),
                        select(opiskelijatiedot.yoArvosanaArvosana, "1"),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), serviceUrls.arvosanat.arvosana(restData.suoritusRekisteri.arvosanat.aarneYo[0].id)),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({
                                  id: '3ba4b93c-87e8-4e6c-8b2d-704ae88a89af',
                                  suoritus: '64d26b8c-e2c8-4b13-9ac9-51c85e288bc0',
                                  arvio: {arvosana: 'M', asteikko: 'YO', pisteet: 4},
                                  aine: 'AINEREAALI',
                                  source: '1.2.246.562.24.72453542949',
                                  valinnainen: false,
                                  lisatieto: 'UO',
                                  myonnetty: '21.12.1988'
                                }
                            )
                        },
                        saveDisabled()
                    ))
                })
            })
            describe("Vahvistamattomat hakemukselta tulleet suoritukset", function () {
                it.skip('!! Hakemukselta tullut arvosana näkyy oikein', seqDone(
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
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                        httpFixtures().komoLocal.komoTiedot()
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
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                        httpFixtures().komoLocal.komoTiedot()
                    },
                    input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                    click(opiskelijatiedot.searchButton),
                    wait.forAngular,
                    visible(opiskelijatiedot.hakijanIlmoittamaAlert),
                    testAlertText(opiskelijatiedot.hakijanIlmoittamaAlert, "Suoritus hakijan ilmoittama")
                ))
            })
        })
        describe('Tietojen muokkaus', function () {
                describe("Korjaa nykyisiä arvosanoja -painike", function() {
                    function aarnenPeruskouluDatat() {
                        httpFixtures().organisaatioService.pikkaralaOid()
                        httpFixtures().organisaatioService.pikkaralaKoodi()
                        httpFixtures().henkiloPalveluService.aarne()
                        httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                        httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                        httpFixtures().suorituksetLocal.aarnenSuoritus()
                        httpFixtures().arvosanatLocal.aarnenArvosanat()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                        httpFixtures().komoLocal.komoTiedot()

                        httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                        httpFixtures().organisaatioService.pikkoloKoodi()
                        httpFixtures().organisaatioService.pikkoloOid()
                    }

                    it("Näkyy OPH käyttäjälle", seqDone(
                        function() { httpFixtures().restrictionService.opoUpdateGraduation() },

                        aarnenPeruskouluDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        visible(opiskelijatiedot.editArvosanat)
                    ))
                    it("Ei näy lukiokäyttäjälle", seqDone(
                        function() {
                            httpFixtures().casRoles.empty()
                            httpFixtures().restrictionService.opoUpdateGraduation()
                        },
                        aarnenPeruskouluDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        disabled(opiskelijatiedot.editArvosanat),
                        disabled(opiskelijatiedot.suoritusValmistuminen),
                        disabled(opiskelijatiedot.suoritusTila),
                        function() { httpFixtures().casRoles.robotti() }
                    ))
                })
                describe("Valmistuminen ja tila ei-rekisterinpitäjälle", function() {
                    function aarnenDatat() {
                        httpFixtures().organisaatioService.pikkaralaOid()
                        httpFixtures().organisaatioService.pikkaralaKoodi()
                        httpFixtures().henkiloPalveluService.aarne()
                        httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                        httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                        httpFixtures().suorituksetLocal.aarnenSuorituksetEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                        httpFixtures().komoLocal.komoTiedot()

                        httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                        httpFixtures().organisaatioService.pikkoloKoodi()
                        httpFixtures().organisaatioService.pikkoloOid()
                    }
                    it("on muokattavissa lisättäessä uusi suoritus", seqDone(
                        function() { httpFixtures().casRoles.empty() },
                        aarnenDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        click(opiskelijatiedot.suoritusLisaa),
                        wait.forAngular,
                        typeaheadInput(opiskelijatiedot.suoritusMyontaja, "Pik", opiskelijatiedot.typeaheadMenuChild(2)),
                        select(opiskelijatiedot.suoritusKoulutus, '1'),
                        select(opiskelijatiedot.suoritusYksilollistetty, "0"),
                        select(opiskelijatiedot.suoritusKieli, "2"),
                        input(opiskelijatiedot.suoritusValmistuminen, '1.1.2014'),
                        select(opiskelijatiedot.suoritusTila, '1'),
                        saveEnabled(),
                        function() { httpFixtures().casRoles.robotti() }
                    ))
                })
                describe("Peruskoulun suoritus", function () {
                    function aarnenPeruskouluDatat() {
                        httpFixtures().organisaatioService.pikkaralaOid()
                        httpFixtures().organisaatioService.pikkaralaKoodi()
                        httpFixtures().henkiloPalveluService.aarne()
                        httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                        httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                        httpFixtures().suorituksetLocal.aarnenSuoritus()
                        httpFixtures().arvosanatLocal.aarnenArvosanat()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                        httpFixtures().komoLocal.komoTiedot()

                        httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                        httpFixtures().organisaatioService.pikkoloKoodi()
                        httpFixtures().organisaatioService.pikkoloOid()
                    }

                    it("Peruskoulun suoritustiedot ja arvosanat talletetaan vain jos muuttuneita arvoja", seqDone(
                        aarnenPeruskouluDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,

                        saveDisabled(),
                        selectByLabel(opiskelijatiedot.suoritusKoulutus, "Valmentava"),
                        saveEnabled(),
                        selectByLabel(opiskelijatiedot.suoritusKoulutus, "Peruskoulu"),

                        saveDisabled(),
                        select(opiskelijatiedot.suoritusYksilollistetty, "2"),
                        saveEnabled(),
                        select(opiskelijatiedot.suoritusYksilollistetty, "0"),

                        saveDisabled(),
                        select(opiskelijatiedot.suoritusTila, "1"),
                        saveEnabled(),
                        select(opiskelijatiedot.suoritusTila, "0"),

                        saveDisabled(),
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
                                myontaja: "1.2.246.562.10.39644336305",
                                tila: "KESKEN",
                                valmistuminen: "03.06.2015",
                                yksilollistaminen: "Ei",
                                suoritusKieli: "PS",
                                id: "4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9"
                            })
                        },
                        saveDisabled(),
                        click(opiskelijatiedot.editArvosanat),
                        selectInput(opiskelijatiedot.arvosana(1,0), "2"),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/arvosanat\/dc54970c-9cd1-4e8f-8d97-a37af3e99c10$/),
                        function(savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({
                                id: "dc54970c-9cd1-4e8f-8d97-a37af3e99c10",
                                suoritus: "4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9",
                                arvio: {arvosana: "8", asteikko: "4-10"},
                                aine: "A1",
                                lisatieto: "EN",
                                valinnainen: false,
                                source: "Test"
                            })
                        },
                        saveDisabled()
                    ))
                    it.skip("!! Lisää suoritus luo uuden suorituksen", seqDone(
                    ))
                    it("Peruskoulun suoritukselle voi lisätä pakollisen arvosanan", seqDone(
                        aarnenPeruskouluDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        saveDisabled(),
                        click(opiskelijatiedot.editArvosanat),
                        selectInput(opiskelijatiedot.arvosana(2, 0), "2"),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/arvosanat$/),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({ aine: 'A12',
                                suoritus: '4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9',
                                arvio: { arvosana: '8', asteikko: '4-10' },
                                valinnainen: false })
                        },
                        saveDisabled()
                    ))
                    it("Peruskoulun suoritukselle voi lisätä valinnaisen arvosanan", seqDone(
                        aarnenPeruskouluDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        saveDisabled(),
                        click(opiskelijatiedot.editArvosanat),
                        selectInput(opiskelijatiedot.arvosana(1, 1), "3"),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/arvosanat$/),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({
                                aine: 'A1',
                                suoritus: '4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9',
                                arvio: {arvosana: '7', asteikko: '4-10'},
                                valinnainen: true,
                                jarjestys: 0,
                                lisatieto: 'EN'
                            })
                        },
                        saveDisabled()
                    ))
                    it("Peruskoulun suorituksen arvosanan korotukselle voi lisätä toisen valinnaisen arvosanan", seqDone(
                        aarnenPeruskouluDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        saveDisabled(),
                        click(opiskelijatiedot.editArvosanat),
                        selectInput(opiskelijatiedot.arvosana(10, 2), "3"),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/arvosanat$/),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({
                                aine: 'MA',
                                suoritus: '4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9',
                                arvio: {arvosana: '7', asteikko: '4-10'},
                                valinnainen: true,
                                jarjestys: 3,
                                myonnetty: '04.06.2015'
                            })
                        },
                        saveDisabled()
                    ))
                    it.skip("!! Peruskoulun suorituksen arvosanan muuttaminen tallentaa null-päivämäärä jos arvosanalla alunperin null-päivä", seqDone(
                    ))
                    it.skip("!! Suorituksen poistaminen", seqDone(
                    ))
                    it.skip("!! Peruskoulun arvosanan poistaminen", seqDone(
                    ))
                    it("Lisää korotus tallentaa arvosanan", seqDone(
                        function() {
                            testDslDebug = false
                        },
                        aarnenPeruskouluDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        saveDisabled(),
                        click(opiskelijatiedot.showKorotus),
                        selectInput(opiskelijatiedot.arvosana(0, 0), "3"),
                        input(opiskelijatiedot.korotusPvm, "6.6.2015"),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/arvosanat$/),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({ aine: 'AI',
                                suoritus: '4eed24c3-9569-4dd1-b7c7-8e0121f6a2b9',
                                arvio: { arvosana: '6', asteikko: '4-10' },
                                valinnainen: false,
                                myonnetty: "6.6.2015"})
                        },
                        function() {
                            assertArvosanat(38, 19, 15, 18, 2)
                            assertArvosanaRivi("Äidinkieli ja kirjallisuus", "", "Kieli puuttuu!!", ["10"], [])
                            assertArvosanaRivi("Äidinkieli ja kirjallisuus", "6.6.2015", "Kieli puuttuu!!", ["6"], ["Ei arvosanaa"])
                        },
                        saveDisabled()
                    ))
                    it("Korotuksen päivämäärä pitää olla valmistumispäivän jälkeen", seqDone(
                        function() {
                            testDslDebug = false
                        },
                        aarnenPeruskouluDatat,
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        saveDisabled(),
                        click(opiskelijatiedot.showKorotus),
                        selectInput(opiskelijatiedot.arvosana(0, 0), "3"),
                        input(opiskelijatiedot.korotusPvm, "1.6.2015"),
                        saveDisabled()
                    ))
                })
                describe("Yo suoritus", function () {
                    it("Vanhempi YO-suoritus (tehty ennen 1.1.1990) on muokattavissa", seqDone(
                        wait.forAngular,
                        function () {
                            httpFixtures().henkiloPalveluService.aarne()
                            httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                            httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                            httpFixtures().get(serviceUrls.suoritukset.henkilo("1.2.246.562.24.71944845619"), [restData.suoritusRekisteri.suoritukset.aarneYo])
                            httpFixtures().get(serviceUrls.arvosanat.suoritus(restData.suoritusRekisteri.suoritukset.aarneYo.id), restData.suoritusRekisteri.arvosanat.aarneYo)
                            httpFixtures().get(serviceUrls.organisaatio(restData.organisaatioService.ytl.oid), restData.organisaatioService.ytl)
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                            httpFixtures().komoLocal.komoTiedot()
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
                            assertText(opiskelijatiedot.suoritusMyontaja, 'Ylioppilastutkintolautakunta')
                            assertSelectedDropdownItem(opiskelijatiedot.suoritusKoulutus, "Ylioppilastutkinto")
                            assertValue(opiskelijatiedot.suoritusYksilollistetty, "0")
                            assertValue(opiskelijatiedot.suoritusKieli, "156")
                            assertValue(opiskelijatiedot.suoritusValmistuminen, "29.12.1989")
                            assertValue(opiskelijatiedot.suoritusTila, "2")
                            expect(opiskelijatiedot.arvosanaAineRivi().length).to.equal(1)
                            expect(opiskelijatiedot.yoArvosanaAddKoe().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.suoritusPoista().is(':visible')).to.equal(true)
                            assertValue(opiskelijatiedot.yoArvosanaAine, "16")
                            assertValue(opiskelijatiedot.yoArvosanaTaso, "1")
                            assertValue(opiskelijatiedot.yoArvosanaArvosana, "2")
                            assertValue(opiskelijatiedot.yoArvosanaPistemaara, "4")
                            assertValue(opiskelijatiedot.yoArvosanaMyonnetty, "2")
                        },
                        saveDisabled(),
                        select(opiskelijatiedot.yoArvosanaArvosana, "1"),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), serviceUrls.arvosanat.arvosana(restData.suoritusRekisteri.arvosanat.aarneYo[0].id)),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({
                                    id: '3ba4b93c-87e8-4e6c-8b2d-704ae88a89af',
                                    suoritus: '64d26b8c-e2c8-4b13-9ac9-51c85e288bc0',
                                    arvio: {arvosana: 'M', asteikko: 'YO', pisteet: 4},
                                    aine: 'AINEREAALI',
                                    source: '1.2.246.562.24.72453542949',
                                    valinnainen: false,
                                    lisatieto: 'UO',
                                    myonnetty: '21.12.1988'
                                }
                            )
                        },
                        saveDisabled()
                    ))
                    it("Vanhalle YO-suoritukselle voi lisätä arvosanan", seqDone(
                        wait.forAngular,
                        function () {
                            httpFixtures().henkiloPalveluService.aarne()
                            httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                            httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                            httpFixtures().get(serviceUrls.suoritukset.henkilo("1.2.246.562.24.71944845619"), [restData.suoritusRekisteri.suoritukset.aarneYo])
                            httpFixtures().get(serviceUrls.arvosanat.suoritus(restData.suoritusRekisteri.suoritukset.aarneYo.id), [])
                            httpFixtures().get(serviceUrls.organisaatio(restData.organisaatioService.ytl.oid), restData.organisaatioService.ytl)
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                            httpFixtures().komoLocal.komoTiedot()
                        },
                        input(opiskelijatiedot.henkiloSearch, '123456-789'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        function () {
                            expect(opiskelijatiedot.resultsTable().length).to.equal(1)
                            expect(opiskelijatiedot.arvosanaAineRivi().length).to.equal(0)
                            expect(opiskelijatiedot.yoArvosanaAddKoe().is(':visible')).to.equal(true)
                            expect(opiskelijatiedot.suoritusPoista().is(':visible')).to.equal(true)
                        },
                        click(opiskelijatiedot.yoArvosanaAddKoe),
                        saveDisabled(),
                        select(opiskelijatiedot.yoArvosanaAine, "21"),
                        saveDisabled(),
                        select(opiskelijatiedot.yoArvosanaTaso, "2"),
                        saveDisabled(),
                        select(opiskelijatiedot.yoArvosanaMyonnetty, "6"),
                        saveDisabled(),
                        select(opiskelijatiedot.yoArvosanaArvosana, "4"),
                        saveEnabled(),
                        input(opiskelijatiedot.yoArvosanaPistemaara, "10"),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/arvosanat$/),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({
                                    suoritus: 'c33ab9a2-e7b4-4e8d-9447-85637c4bc09d',
                                    valinnainen: false,
                                    arvio: {asteikko: 'YO', arvosana: 'A', pisteet: 10},
                                    lisatieto: 'RU',
                                    aine: 'B',
                                    myonnetty: '21.12.1986'
                                }
                            )
                        },
                        saveDisabled()
                    ))
                    it.skip("!! YO arvosanan poistaminen", seqDone(
                    ))
                })
                describe("Luokkatieto", function () {
                    function aarnenDatat() {
                        httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                        httpFixtures().organisaatioService.pikkaralaOid()
                        httpFixtures().organisaatioService.pikkaralaKoodi()
                        httpFixtures().organisaatioService.pikkaralaLuokkaTieto()
                        httpFixtures().organisaatioService.pikkoloOid()
                        httpFixtures().organisaatioService.pikkoloKoodi()
                        httpFixtures().henkiloPalveluService.aarne()
                        httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                        httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                        httpFixtures().suorituksetLocal.aarnenSuoritus()
                        httpFixtures().arvosanatLocal.aarnenArvosanat()
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeus()
                        httpFixtures().komoLocal.komoTiedot()
                    }
                    it("Lisää uusi luokkatieto toiminto luo uuden luokkatiedon", seqDone(
                        wait.forAngular(),
                        function() {
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(getDefaultYear())
                        },
                        aarnenDatat,
                        input(opiskelijatiedot.henkiloSearch, '123456-789'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        click(opiskelijatiedot.luokkatietoLisaa),
                        wait.forAngular,
                        function() {
                            expect(opiskelijatiedot.luokkatietoLuokkaTasoValma().length).to.equal(1)
                            expect(opiskelijatiedot.luokkatietoLuokkaTasoTelma().length).to.equal(1)
                        },
                        input(opiskelijatiedot.luokkatietoOppilaitos, '06345'),
                        input(opiskelijatiedot.luokkatietoLuokka, '9A'),
                        input(opiskelijatiedot.luokkatietoLuokkaTaso, '9'),
                        input(opiskelijatiedot.luokkatietoAlkuPaiva, '12.8.2014'),
                        input(opiskelijatiedot.luokkatietoLoppuPaiva, '31.5.2015'),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/opiskelijat$/),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({
                                "henkiloOid": "1.2.246.562.24.71944845619",
                                "oppilaitosOid": "1.2.246.562.10.39644336305",
                                "editable": true,
                                "luokka": "9A",
                                "luokkataso": "9",
                                "alkuPaiva": "2014-08-11T21:00:00.000Z",
                                "loppuPaiva": "2015-05-30T21:00:00.000Z"
                            })

                        }
                    ))
                    it.skip("!! Luokkatiedon poistaminen", seqDone(
                    ))
                    it("Luokkatiedot tallennetaan vain jos muuttuneita arvoja", seqDone(
                        function() {
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot(getDefaultYear())
                        },
                        aarnenDatat,
                        input(opiskelijatiedot.henkiloSearch, '123456-789'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        saveDisabled(),
                        typeaheadInput(opiskelijatiedot.luokkatietoOppilaitos, "Pik", opiskelijatiedot.typeaheadMenuChild(2)),
                        saveEnabled(),
                        typeaheadInput(opiskelijatiedot.luokkatietoOppilaitos, "Pik", opiskelijatiedot.typeaheadMenuChild(1)),
                        saveDisabled(),
                        input(opiskelijatiedot.luokkatietoLuokka, "9A"),
                        saveEnabled(),
                        input(opiskelijatiedot.luokkatietoLuokka, "10A"),
                        saveDisabled(),
                        input(opiskelijatiedot.luokkatietoLuokkaTaso, "2"),
                        saveEnabled(),
                        input(opiskelijatiedot.luokkatietoLuokkaTaso, "3"),
                        saveDisabled(),
                        input(opiskelijatiedot.luokkatietoAlkuPaiva, "1.1.2017"),
                        saveEnabled(),
                        input(opiskelijatiedot.luokkatietoAlkuPaiva, "18.8.2014"),
                        saveDisabled(),
                        input(opiskelijatiedot.luokkatietoLoppuPaiva, "2.1.2017"),
                        saveEnabled(),
                        input(opiskelijatiedot.luokkatietoLoppuPaiva, "4.6.2015"),
                        saveDisabled(),
                        typeaheadInput(opiskelijatiedot.luokkatietoOppilaitos, "Pik", opiskelijatiedot.typeaheadMenuChild(2)),
                        saveEnabled(),
                        mockPostReturnData(click(opiskelijatiedot.saveButton), /.*rest\/v1\/opiskelijat\/e86fb63a-607a-48da-b701-4527193e9efc$/),
                        function (savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({
                                "id": "e86fb63a-607a-48da-b701-4527193e9efc",
                                "oppilaitosOid": "1.2.246.562.10.16546622305",
                                "luokkataso": "10",
                                "luokka": "10A",
                                "henkiloOid": "1.2.246.562.24.71944845619",
                                "alkuPaiva": "2014-08-17T21:00:00.000Z",
                                "loppuPaiva": "2015-06-03T21:00:00.000Z",
                                "source": "Test"
                            })
                        },
                        saveDisabled()
                    ))
                })
            }
        )
    })
})()