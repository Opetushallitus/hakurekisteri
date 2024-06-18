function tiedostonSiirtoFixtures() {

    function mockHttpBackEnd() {
        return testFrame().httpBackend
    }

    return {
        perustiedotOpen: function (isOpen) {
            return function () {
                mockHttpBackEnd().when('GET', /.*rest\/v2\/siirto\/perustiedot\/isopen$/).respond({"open": isOpen})
            }
        },

        arvosanatOpen: function (isOpen) {
            return function () {
                mockHttpBackEnd().when('GET', /.*rest\/v2\/siirto\/arvosanat\/isopen$/).respond({"open": isOpen})
            }
        }
    }
}
