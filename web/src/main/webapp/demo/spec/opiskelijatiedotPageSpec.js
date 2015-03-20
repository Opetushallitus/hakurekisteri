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
                        page.organizationSearch().val("Pikkaralan+ala-aste").change()
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
                        expect(page.resultsTable().length).to.equal(5)
                    }).then(function () {
                        done()
                    }, function (err) {
                        done(err)
                    })
            })
        })

        describe('Henkilohaku', function () {
            it('Voi hakea oidin perusteella', function (done) {
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
                        expect(page.henkiloTiedot().is(':visible')).to.equal(true)
                        expect(page.suoritusTiedot().is(':visible')).to.equal(true)
                        expect(page.luokkaTiedot().is(':visible')).to.equal(true)
                    }).then(function () {
                        done()
                    }, function (err) {
                        done(err)
                    })
            })

            it('Voi hakea hetun perusteella', function (done) {
                exists(page.henkiloSearch)()
                    .then(wait.forAngular)
                    .then(function () {
                        page.henkiloSearch().val('123456-789').change()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        page.searchButton().click()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        expect(page.resultsTable().length).to.equal(1)
                        expect(page.henkiloTiedot().is(':visible')).to.equal(true)
                        expect(page.suoritusTiedot().is(':visible')).to.equal(true)
                        expect(page.luokkaTiedot().is(':visible')).to.equal(true)
                    }).then(function () {
                        done()
                    }, function (err) {
                        done(err)
                    })
            })

            it('Puuttuva hetu perusteella', function (done) {
                exists(page.henkiloSearch)()
                    .then(wait.forAngular)
                    .then(function () {
                        page.henkiloSearch().val('foobar').change()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        page.searchButton().click()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        expect(page.resultsTable().length).to.equal(0)
                        expect(page.henkiloTiedot().is(':visible')).to.equal(false)
                        expect(page.suoritusTiedot().is(':visible')).to.equal(false)
                        expect(page.luokkaTiedot().is(':visible')).to.equal(false)
                    }).then(function () {
                        done()
                    }, function (err) {
                        done(err)
                    })
            })
        })
    })
})();