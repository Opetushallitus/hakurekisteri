(function () {
    function asyncPrint(s) {
        return function () {
            console.log(s)
        }
    }

    describe('Tiedonsiirto', function () {
        var page = TiedonsiirtoPage();

        afterEach(function () {
            if (this.currentTest.state == 'failed') {
                takeScreenshot()
            }
        });

        initPage = function (enablePerustiedot, enableArvosanat) {
            before(
                addTestHook(koodistoFixtures),
                addTestHook(lokalisointiFixtures),
                addTestHook(tiedostonSiirtoFixtures().perustiedotOpen(enablePerustiedot)),
                addTestHook(tiedostonSiirtoFixtures().arvosanatOpen(enableArvosanat)),
                page.openPage,
                wait.until(function () { return page.uploadForm().length === 1}),
                wait.forAngular,
                wait.until(function () { return page.alerts().length === 0 })
            )
        }

        describe("Tiedoston lähetys", function () {
            initPage(true, true)

            describe("Aluksi", function() {
                it("Ei näytä virheitä", function () {
                    expect(page.alerts().length).to.equal(0)
                    expect(page.validationErrors().length).to.equal(0)
                })
            })

            describe("Kun tiedoston tyyppiä ja tiedostoa ei ole valittu", function() {

                it('ilmoittaa, että tyyppiä ja tiedostoa ei ole valittu', function () {
                    expect(page.submitButton().isEnabled()).to.equal(false)
                })
            })

            describe("Kun lomake resetoidaan", function () {
                before(
                  page.resetButton().click,
                  wait.forAngular
                )
                it("Ei näytä virheitä", function () {
                    expect(page.alerts().length).to.equal(0)
                })
            })

            describe("Tiedoston siirron enabloitu", function () {
                it("Perustiedot enabloitu", function () {
                    expect(page.perustiedotRadio().isEnabled()).to.equal(true)
                })

                it("Arvosanat enabloitu", function () {
                    expect(page.arvosanatRadio().isEnabled()).to.equal(true)
                })
            })

            describe("Tiedoston siirron disablointi", function () {
                initPage(false, false)
                it("Perustiedot disabloitu", function () {
                    expect(page.perustiedotRadio().isEnabled()).to.equal(false)
                })

                it("Arvosanat disabloitu", function () {
                    expect(page.arvosanatRadio().isEnabled()).to.equal(false)
                })
            })

            describe("Eri arvot", function () {
                initPage(false, true)
                it("Perustiedot disabloitu ja arvosanat enabloitu", function () {
                    expect(page.perustiedotRadio().isEnabled()).to.equal(false)
                    expect(page.arvosanatRadio().isEnabled()).to.equal(true)
                })
            })

            describe("Arvosanojen validointi", function() {
                describe("Arvosanat puuttuvat, korkeintaan 3 henkilöä", function() {
                    before(
                      function() { testFrame().validateXml(todistukset([{aineet: []},{aineet: []}]))}
                    )
                    it("Näyttää validointivirheet", function() {
                        expect(page.validationErrors().length).to.be.above(10)
                    })

                    it("Näyttää henkilöiden nimet", function() {
                        expect(page.validationErrorNames().text()).to.contain("Pertti Karppinen")
                    })
                })

                describe("Arvosanat puuttuvat, yli 3 henkilöä", function() {
                    before(function() {
                        testFrame().validateXml(todistukset([{aineet: []},{aineet: []},{aineet: []},{aineet: []}]))
                    })

                    it("Näyttää validointivirheet", function() {
                        expect(page.validationErrors().length).to.be.above(10)
                    })

                    it("Ei näytä henkilöiden nimiä", function() {
                        expect(page.validationErrorNames().text()).to.equal("")
                    })

                    it("Näyttää henkilöiden lukumäärät", function() {
                        expect(page.validationErrorCounts().text()).to.contain("4")
                    })
                })

                describe("Kaikki pakolliset arvosanat", function() {
                    before(function() {
                        testFrame().validateXml(todistukset([{aineet: ["TE","KO","BI","MU","LI","A1","KT","GE","KU","A2","KE","MA","FY","KS","YH","HI","AI"]}]))
                    })

                    it("Ei näytä validointivirheitä", function() {
                        expect(page.validationErrors().length).to.equal(0)
                    })
                })

                describe("IE9", function() {
                    it("validointia ei tehdä (testataan manuaalisesti)", function() {
                    })
                })

                function todistukset(datat) {
                    return "<arvosanat>" + datat.map(todistus).join("") + "</arvosanat>"
                }

                function todistus(data) {
                    return "<henkilo><sukunimi>Karppinen</sukunimi><kutsumanimi>Pertti</kutsumanimi><perusopetus>"
                      + data.aineet.map(function(aine) { return "<"+aine.toUpperCase()+"><yhteinen>5</yhteinen></" + aine +">" }).join("")
                      + "</perusopetus></henkilo>"
                }
            })
        });
    });
})();