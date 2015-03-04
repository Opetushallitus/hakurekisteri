(function () {
  function asyncPrint(s) { return function() { console.log(s) } }

  describe('Opiskelijatiedot', function() {
    var page = opiskelijatiedotPage()

    beforeEach(function() {
      page.openPage()
    })

    afterEach(function() {
      if (this.currentTest.state == 'failed') {
        takeScreenshot()
      }
    })

    describe("Haku", function() {
      it('Voi hakea oppilaitoksen perusteella', function(done) {
        exists(page.organizationSearch)()
          .then(wait.forAngular)
          .then(function() {
            page.organizationSearch().val("Pikkaralan ala-aste").change()
          })
          .then(wait.forAngular)
          .then(function() {
            page.dropDownMenu().children().first().click()
          })
          .then(wait.forAngular)
          .then(function() {
            page.searchButton().click()
          })
          .then(wait.forAngular)
          .then(function() {
            expect(page.resultsTable().length).to.equal(5)
            done()
          })
      })
    })
  })
})();