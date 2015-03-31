function tiedostonSiirtoFixtures() {

    function mockHttpBackEnd() {
        return testFrame().httpBackend
    }

    return {
        perustiedotOpen: function (isOpen) {
            return function () {
                console.log('perustiedotOpen='+JSON.stringify({"open": isOpen}))
                mockHttpBackEnd().when('GET', /.*rest\/v1\/siirto\/perustiedot\/isopen$/).respond({"open": isOpen})
            }
        },

        arvosanatOpen: function (isOpen) {
            return function () {
                console.log('arvosanatOpen='+JSON.stringify({"open": isOpen}))
                mockHttpBackEnd().when('GET', /.*rest\/v1\/siirto\/arvosanat\/isopen$/).respond({"open": isOpen})
            }
        }
    }
}
