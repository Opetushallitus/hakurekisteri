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

        describe("Tiedoston lähetys", function () {
            before( page.openPage,
                    wait.until(function () { return page.uploadForm().length === 1}),
                    wait.forAngular,
                    wait.until(function () { return page.alerts().length === 0 })
            )

            describe("Aluksi", function() {
                it("Ei näytä virheitä", function () {
                    expect(page.alerts().length).to.equal(0)
                    expect(page.validationErrors().length).to.equal(0)
                })
            })

            describe("Kun tiedoston tyyppiä ja tiedostoa ei ole valittu", function() {
                before(
                  page.submitButton().click,
                  wait.until(function () { return page.alerts().length === 2 })
                )

                it('ilmoittaa, että tyyppiä ja tiedostoa ei ole valittu', function () {
                    expect(page.alerts().text()).to.include('Tiedoston tyyppiä ei ole valittu')
                    expect(page.alerts().text()).to.include('Tiedostoa ei ole valittu')
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

            describe("Arvosanojen validointi", function() {
                describe("Arvosanat puuttuvat, korkeintaan 3 henkilöä", function() {
                    before(function() {
                        testFrame().validateXml(todistukset([{aineet: []},{aineet: []}]))
                    })

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
                        expect(page.validationErrorCounts().text()).to.contain("4 oppilasta")
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