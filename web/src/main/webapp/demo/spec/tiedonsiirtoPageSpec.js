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

            describe("Kun validoidaan arvosanatiedosto, josta puuttuvat arvosanat", function() {
                before(function() {
                    testFrame().validateXml("<arvosanat><perusopetus></perusopetus></arvosanat>")
                })

                it("Näyttää validointivirheet", function() {
                    expect(page.validationErrors().length).to.be.above(10)
                })
            })
        });
    });
})();