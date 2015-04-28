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
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                    httpFixtures().komoLocal.komoTiedot()
                    koodistoFixtures()
                },
                input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                wait.forAngular,
                select(opiskelijatiedot.vuosiSearch, '2'),
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(testFrame().location.hash).to.equal('#/opiskelijat?henkilo=1.2.246.562.24.71944845619&oppilaitos=&vuosi='+getCurrentYear())
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
                    httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                    httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
                    expect(testFrame().location.hash).to.equal('#/opiskelijat?henkilo=&oppilaitos=06345&vuosi='+getCurrentYear())
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

            function nonEmpty(i,e) {
                return jQuery(e).text().trim().length > 0
            }

            function assertArvosanat(aineRiviCount, aineCount, korotusDateCount, pakollisetCount, valinnaisetCount) {
                expect(opiskelijatiedot.arvosanaAineRivi().length).to.equal(aineRiviCount)
                expect(jQuery.unique(jQuery.map(opiskelijatiedot.arvosanaAineNimi(),function(e){return jQuery(e).text()})).length).to.equal(aineCount)
                expect(opiskelijatiedot.arvosanaMyonnetty().length).to.equal(korotusDateCount)
                expect(opiskelijatiedot.arvosanaPakollinenArvosana().length).to.equal(pakollisetCount)
                expect(opiskelijatiedot.arvosanaValinnainenArvosana().filter(nonEmpty).length).to.equal(valinnaisetCount)
            }

            function findAineRivi(aineTxt, myonnetty, lisatieto) {
                return opiskelijatiedot.arvosanaAineRivi().filter(function(i, rivi){
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
                if(myonnetty.length > 0) {
                    myonnetty = "(" + myonnetty + ")"
                }
                var aineRivit = findAineRivi(aineTxt, myonnetty, lisatieto)
                expect(aineRivit.length).to.equal(1, "found " + aineRivit.length + " matching aineRivi when there was supposed to be 1: " + aineTxt + ": " + myonnetty + ": " + lisatieto)
                var aineRivi = jQuery(aineRivit[0])
                var p = jQuery.makeArray(jQuery(aineRivi).find(opiskelijatiedot.arvosanaPakollinenArvosana().selector).map(txtArr))
                var v = jQuery.makeArray(jQuery(aineRivi).find(opiskelijatiedot.arvosanaValinnainenArvosana().selector).filter(nonEmpty).map(txtArr))
                expect([aineTxt, myonnetty , lisatieto, p, v]).to.deep.equal([aineTxt, myonnetty, lisatieto, pakolliset, valinnaiset])
            }
            describe('Vahvistetut', function () {
                it('Opiskelijan peruskoulun suoritukset, ainelista, arvosanat, luokkatiedot ja opintooikeudet näkyvät oikein', seqDone(
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
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedot(2015)
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeus()
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
                        assertText(opiskelijatiedot.opiskeluoikeusAlkuPaiva, "01.01.2000")
                        assertText(opiskelijatiedot.opiskeluoikeusLoppuPaiva, "01.01.2014")
                        assertText(opiskelijatiedot.opiskeluoikeusMyontaja, "06345 Pikkaralan ala-aste")
                        assertText(opiskelijatiedot.opiskeluoikeusKoulutus, "Ensihoitaja (AMK)")
                        assertArvosanat(37, 19, 14, 17, 1)
                        assertArvosanaRivi("Äidinkieli ja kirjallisuus", "", "Kieli puuttuu!!", ["10"], [])
                        assertArvosanaRivi("A1-kieli", "", "englanti", ["9"],[])
                        assertArvosanaRivi("Matematiikka", "", "", ["6"], [])
                        assertArvosanaRivi("Matematiikka", "04.06.2015", "", ["10"], ["9"])
                    },
                    saveDisabled()
                ))
                it('Opiskelijan lukion suoritukset, ainelista ja arvosanat näkyvät oikein', seqDone(
                    wait.forAngular,
                    function () {
                        suoritus = {}
                        suoritus = jQuery.extend(suoritus, restData.suoritusRekisteri.suoritukset.aarne)
                        suoritus.komo = restData.komo.lukioKomoOid
                        httpFixtures().organisaatioService.pikkaralaOid()
                        httpFixtures().organisaatioService.pikkaralaKoodi()
                        httpFixtures().henkiloPalveluService.aarne()
                        httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                        httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                        testFrame().httpBackend.when('GET', serviceUrls.suoritukset.henkilo("1.2.246.562.24.71944845619")).respond([suoritus])
                        httpFixtures().arvosanatLocal.aarnenArvosanat()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
                        assertValue(opiskelijatiedot.suoritusKoulutus, "8")
                        assertValue(opiskelijatiedot.suoritusYksilollistetty, "0")
                        assertValue(opiskelijatiedot.suoritusKieli, "156")
                        assertValue(opiskelijatiedot.suoritusValmistuminen, "3.6.2015")
                        assertValue(opiskelijatiedot.suoritusTila, "0")
                        assertArvosanat(38, 20, 12, 15, 1)
                        assertArvosanaRivi("Äidinkieli ja kirjallisuus", "", "Kieli puuttuu!!", ["10"], [])
                        // assertArvosanaRivi("A1-kieli", "", "englanti", ["9"],[])
                        assertArvosanaRivi("Matematiikka", "", "", ["6"], [])
                        assertArvosanaRivi("Matematiikka", "04.06.2015", "", ["10"], ["9"])
                    },
                    saveDisabled()
                ))
                it('Opiskelijan amk suoritus (komo = koulutus_*) näkyy oikein eikä arvosanoja näytetä', seqDone(
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
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
                        assertText(opiskelijatiedot.suoritusMyontaja, '06345 Pikkaralan ala-aste')
                        assertText(opiskelijatiedot.suoritusKoulutus, "Ensihoitaja (AMK)")
                        assertText(opiskelijatiedot.suoritusYksilollistetty, "Ei")
                        assertText(opiskelijatiedot.suoritusKieli, "suomi")
                        assertText(opiskelijatiedot.suoritusValmistuminen, "3.6.2015")
                        assertText(opiskelijatiedot.suoritusTila, "Suoritus kesken")
                        assertArvosanat(0, 0, 0, 0, 0)
                    },
                    saveDisabled()
                ))
                it('YTL:n lähettämää YO-suoritusta ja sen arvosanoja ei voi muokata', seqDone(
                    wait.forAngular,
                    function () {
                        httpFixtures().henkiloPalveluService.aarne()
                        httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                        httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                        httpFixtures().get(serviceUrls.suoritukset.henkilo("1.2.246.562.24.71944845619"), [restData.suoritusRekisteri.suoritukset.aarneYoYTL])
                        httpFixtures().get(serviceUrls.arvosanat.suoritus(restData.suoritusRekisteri.suoritukset.aarneYoYTL.id), restData.suoritusRekisteri.arvosanat.aarneYoYTL)
                        httpFixtures().get(serviceUrls.organisaatio(restData.organisaatioService.ytl.oid), restData.organisaatioService.ytl)
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
                        assertText(opiskelijatiedot.suoritusMyontaja, 'Ylioppilastutkintolautakunta')
                        assertText(opiskelijatiedot.suoritusKoulutus, "Ylioppilastutkinto")
                        assertText(opiskelijatiedot.suoritusYksilollistetty, "Ei")
                        assertText(opiskelijatiedot.suoritusKieli, "suomi")
                        assertText(opiskelijatiedot.suoritusValmistuminen, "1.6.2013")
                        assertText(opiskelijatiedot.suoritusTila, "Suoritus valmis")
                        expect(opiskelijatiedot.arvosanaAineRivi().length).to.equal(1)
                        assertText(opiskelijatiedot.yoTxt, 'Ortodoksiuskonto',
                            'Ainemuotoinen reaali',
                            'C',
                            '4',
                            '01.06.2013',
                            'true' )
                        expect(opiskelijatiedot.yoArvosanaAddKoe().is(':visible')).to.equal(false)
                        expect(opiskelijatiedot.suoritusPoista().is(':visible')).to.equal(false)
                    },
                    saveDisabled()
                ))
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
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
                        assertText(opiskelijatiedot.suoritusMyontaja, 'Ylioppilastutkintolautakunta')
                        assertValue(opiskelijatiedot.suoritusKoulutus, "7")
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
                    saveDisabled()
                ))
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
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                        httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                        httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
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
        })
        describe('Tietojen muokkaus', function () {
                describe("Peruskoulun suoritus", function() {
                    it("Peruskoulun suoritustiedot (ja arvosanat) talletetaan vain jos muuttuneita arvoja", seqDone(
                        function () {
                            httpFixtures().organisaatioService.pikkaralaOid()
                            httpFixtures().organisaatioService.pikkaralaKoodi()
                            httpFixtures().henkiloPalveluService.aarne()
                            httpFixtures().henkiloPalveluService.aarneHenkiloPalvelu()
                            httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                            httpFixtures().suorituksetLocal.aarnenSuoritus()
                            httpFixtures().arvosanatLocal.aarnenArvosanat()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                            httpFixtures().komoLocal.komoTiedot()
                            koodistoFixtures()
                        },
                        function () {
                            console.log("1")
                        },
                        input(opiskelijatiedot.henkiloSearch, '1.2.246.562.24.71944845619'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        function () {
                            console.log("2")
                        },
                        saveDisabled(),
                        select(opiskelijatiedot.suoritusKoulutus, "2"),
                        saveEnabled(),
                        select(opiskelijatiedot.suoritusKoulutus, "1"),
                        function () {
                            console.log("3")
                        },
                        saveDisabled(),
                        select(opiskelijatiedot.suoritusYksilollistetty, "2"),
                        saveEnabled(),
                        select(opiskelijatiedot.suoritusYksilollistetty, "0"),
                        function () {
                            console.log("4")
                        },
                        saveDisabled(),
                        select(opiskelijatiedot.suoritusTila, "1"),
                        saveEnabled(),
                        select(opiskelijatiedot.suoritusTila, "0"),
                        function () {
                            console.log("5")
                        },
                        saveDisabled(),
                        function () {
                            httpFixtures().organisaatioService.pikkaralaPikkoloOrganisaatioLista()
                            httpFixtures().organisaatioService.pikkoloKoodi()
                            httpFixtures().organisaatioService.pikkoloOid()
                        },
                        function () {
                            console.log("6")
                        },
                        typeaheadInput(opiskelijatiedot.suoritusMyontaja, "Pik", opiskelijatiedot.typeaheadMenuChild(2)),
                        saveEnabled(),
                        function () {
                            console.log("7")
                        },
                        typeaheadInput(opiskelijatiedot.suoritusMyontaja, "Pik", opiskelijatiedot.typeaheadMenuChild(1)),
                        saveDisabled(),
                        function () {
                            console.log("8")
                        },
                        select(opiskelijatiedot.suoritusKieli, "2"),
                        saveEnabled(),
                        function () {
                            console.log("9")
                        },
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
                        function () {
                            console.log("10")
                        },
                        saveDisabled(),
                        click(opiskelijatiedot.editArvosanat),
                        selectInput(opiskelijatiedot.arvosana(1,0), "2"),
                        function () {
                            console.log("11")
                        },
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
                        function () {
                            console.log("12")
                        },
                        saveDisabled()
                    ))
                    it.skip("!! Lisää suoritus luo uuden suorituksen", seqDone(

                    ))
                    it.skip("!! Peruskoulun suoritukselle voi lisätä pakollisen arvosanan", seqDone(
                    ))
                    it.skip("!! Peruskoulun suoritukselle voi lisätä valinnaisen arvosanan", seqDone(
                    ))
                    it.skip("!! Peruskoulun suorituksen arvosanan muuttaminen tallentaa null-päivämäärä jos arvosanalla alunperin null-päivä", seqDone(
                    ))
                    it.skip("!! Suorituksen poistaminen", seqDone(

                    ))
                    it.skip("!! Peruskoulun arvosanan poistaminen", seqDone(

                    ))
                    it.skip("!! Lisää korotus tallentaa arvosanan", seqDone(

                    ))
                })
                describe("Yo suoritus", function() {
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
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeudetEmpty()
                            httpFixtures().komoLocal.komoTiedot()
                            koodistoFixtures()
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
                        function(savedData) {
                            expect(JSON.parse(savedData)).to.deep.equal({ suoritus: 'c33ab9a2-e7b4-4e8d-9447-85637c4bc09d',
                                    valinnainen: false,
                                    arvio: { asteikko: 'YO', arvosana: 'A', pisteet: 10 },
                                    lisatieto: 'RU',
                                    aine: 'B',
                                    myonnetty: '21.12.1986' }
                            )
                        },
                        saveDisabled()
                    ))
                    it.skip("!! YO arvosanan poistaminen", seqDone(

                    ))
                })
                describe("Luokkatieto", function() {
                    it("Lisää uusi luokkatieto toiminto luo uuden luokkatiedon", seqDone(
                        wait.forAngular,
                        function () {
                            httpFixtures().organisaatioService.pikkaralaOid()
                            httpFixtures().organisaatioService.pikkaralaKoodi()
                            httpFixtures().organisaatioService.pikkaralaLuokkaTieto()
                            httpFixtures().henkiloPalveluService.aarne()
                            httpFixtures().henkiloPalveluService.aarneHenkiloPalveluHetu()
                            httpFixtures().henkiloPalveluService.aarneHenkiloListana()
                            httpFixtures().suorituksetLocal.aarnenSuoritus()
                            httpFixtures().arvosanatLocal.aarnenArvosanat()
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty(2015)
                            httpFixtures().luokkaTiedotLocal.aarnenLuokkaTiedotEmpty()
                            httpFixtures().opiskeluOikeudetLocal.aarnenOpiskeluOikeus()
                            httpFixtures().komoLocal.komoTiedot()
                            koodistoFixtures()
                        },
                        input(opiskelijatiedot.henkiloSearch, '123456-789'),
                        click(opiskelijatiedot.searchButton),
                        wait.forAngular,
                        click(opiskelijatiedot.luokkatietoLisaa),
                        wait.forAngular,
                        input(opiskelijatiedot.luokkatietoOppilaitos, '06345'),
                        input(opiskelijatiedot.luokkatietoLuokka, '9A'),
                        input(opiskelijatiedot.luokkatietoLuokkaTaso, '7'),
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
                    it.skip("!! Luokkatiedon muokkaaminen", seqDone(

                    ))
                })
            }
        )
    })
})()