(function () {
    function asyncPrint(s) {
        return function () {
            console.log(s)
        }
    }

    describe('Tiedonsiirto', function () {
        var page = TiedonsiirtoPage();

        beforeEach(function (done) {
            page.openPage(done)
        });

        afterEach(function () {
            if (this.currentTest.state == 'failed') {
                takeScreenshot()
            }
        });

        describe("Tiedoston lähetys", function () {
            it('ilmoittaa, että tyyppiä ja tiedostoa ei ole valittu', function (done) {
                wait.until(function () {
                    return page.uploadForm().length === 1
                })()
                    .then(wait.forAngular)
                    .then(page.resetButton().click)
                    .then(wait.until(function () {
                        return page.alerts().length === 0
                    }))
                    .then(wait.forAngular)
                    .then(function () {
                        return page.submitButton().click()
                    })
                    .then(wait.until(function () {
                        return page.alerts().length === 2
                    }))
                    .then(function () {
                        expect(page.alerts().length).to.equal(2)
                        //expect(page.alerts().text()).to.include('Tiedoston tyyppiä ei ole valittu')
                        //expect(page.alerts().text()).to.include('Tiedostoa ei ole valittu')
                    }).then(function () {
                        done()
                    }, function (err) {
                        done(err)
                    })
            });
        });
    });
})();