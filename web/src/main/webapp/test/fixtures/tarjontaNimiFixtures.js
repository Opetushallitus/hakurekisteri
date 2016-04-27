function tarjontaNimiFixtures() {

    return {
        hakuJaHakukohde: function (loytyy) {
            return function () {
                var httpBackend = testFrame().httpBackend
                if(loytyy) {
                    httpBackend.when('GET', /.*tarjonta-service\/rest\/v1\/haku\/.*/).respond(
                        {
                            "result": {
                                "nimi": {"kieli_fi": "Testihaku"}
                            },
                            "status": "OK"
                        }
                    )
                    httpBackend.when('GET', /.*tarjonta-service\/rest\/v1\/hakukohde\/.*/).respond(
                        {
                            "result":{
                                "hakukohteenNimet":{"kieli_fi":"Testi hakukohde"}
                            },
                            "status":"OK"}
                    )
                }
                else {
                    httpBackend.when('GET', /.*tarjonta-service\/rest\/v1\/haku\/.*/).passThrough()
                    httpBackend.when('GET', /.*tarjonta-service\/rest\/v1\/hakukohde\/.*/).passThrough()
                }
            }
        },
    }
}
