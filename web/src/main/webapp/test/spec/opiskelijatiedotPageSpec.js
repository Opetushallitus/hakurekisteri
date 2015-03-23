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
                autocomplete(opiskelijatiedot.organizationSearch, "Pik", opiskelijatiedot.organizationDropDownMenuChild(1)),
                wait.forAngular,
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                function () {
                    expect(opiskelijatiedot.resultsTable().length).to.equal(5)
                }
            ))
        })

        describe('Henkilohaku', function () {
            it('Voi hakea oidin perusteella - test-dsl', seqDone(
                wait.forAngular,
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

            function areElementsVisible() {
                return opiskelijatiedot.henkiloTiedot().is(':visible') &&
                    opiskelijatiedot.suoritusTiedot().is(':visible') &&
                    opiskelijatiedot.luokkaTiedot().is(':visible')
            }

            it('Etsi organisaatiosta henkiloita - test.dsl', seqDone(
                wait.forAngular,
                autocomplete(opiskelijatiedot.organizationSearch, "Pik", opiskelijatiedot.organizationDropDownMenuChild(1)),
                wait.forAngular,
                click(opiskelijatiedot.searchButton),
                wait.forAngular,
                click(opiskelijatiedot.resultsTableChild(1)),
                wait.forAngular,
                function () {
                    expect(areElementsVisible()).to.equal(true)
                    assertText(opiskelijatiedot.hetuTieto, "123456-789")
                },
                click(opiskelijatiedot.resultsTableChild(2)),
                wait.forAngular,
                function () {
                    expect(areElementsVisible()).to.equal(true)
                    assertText(opiskelijatiedot.hetuTieto, "010719-917S")
                },
                click(opiskelijatiedot.resultsTableChild(3)),
                wait.forAngular,
                function () {
                    expect(areElementsVisible()).to.equal(true)
                    assertText(opiskelijatiedot.hetuTieto, "060398-7570")
                },
                click(opiskelijatiedot.resultsTableChild(4)),
                wait.forAngular,
                function () {
                    expect(areElementsVisible()).to.equal(true)
                    assertText(opiskelijatiedot.hetuTieto, "090700-386W")
                },
                click(opiskelijatiedot.resultsTableChild(5)),
                wait.forAngular,
                function () {
                    expect(areElementsVisible()).to.equal(true)
                    assertText(opiskelijatiedot.hetuTieto, "260420-382F")
                }
            ))
        })
    })
})();