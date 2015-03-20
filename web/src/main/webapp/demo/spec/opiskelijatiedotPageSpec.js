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
            it('Voi hakea oppilaitoksen perusteella', function (done) {
                exists(page.organizationSearch)()
                    .then(wait.forAngular)
                    .then(function () {
                        page.organizationSearch().val("Pikkaralan ala-aste").change()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        page.dropDownMenu().children().first().click()
                    })
                    .then(function () {
                        page.searchButton().click()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        expect(page.resultsTable().length).to.equal(1)
                    }).then(function () {
                        done()
                    }, function (err) {
                        done(err)
                    })
            })
        })

        describe('Henkilohaku', function () {
            it('Voi hakea hetu perusteella', function (done) {
                exists(page.henkiloSearch)()
                    .then(wait.forAngular)
                    .then(function () {
                        page.henkiloSearch().val('1.2.246.562.24.71944845619').change()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        page.searchButton().click()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        expect(page.resultsTable().length).to.equal(1)
                    }).then(function () {
                        done()
                    }, function (err) {
                        done(err)
                    })
            })
        })
    })
})();