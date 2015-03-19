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
                    wait.forAngular
            )

            describe("Kun tiedoston tyyppiä ja tiedostoa ei ole valittu", function() {
                before(
                  page.resetButton().click,
                  wait.until(function () { return page.alerts().length === 0 }),
                  wait.forAngular,
                  page.submitButton().click,
                  wait.until(function () { return page.alerts().length === 2 })
                )

                it('ilmoittaa, että tyyppiä ja tiedostoa ei ole valittu', function () {
                    expect(page.alerts().text()).to.include('Tiedoston tyyppiä ei ole valittu')
                    expect(page.alerts().text()).to.include('Tiedostoa ei ole valittu')
                })
            })
        });
    });
})();