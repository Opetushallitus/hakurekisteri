function tarjontaNimiFixtures() {
    var httpBackend = testFrame().httpBackend
    httpBackend.when('GET', /.*tarjonta-service\/rest\/v1\/haku\/1.2.246.561.29.00000000001/).respond(
        {
            "result": {
                "nimi": {"kieli_fi": "Testihaku"}
            },
            "status": "OK"
        }
    )
    httpBackend.when('GET', /.*tarjonta-service\/rest\/v1\/haku\/1.2.246.561.29.00000000002/).respond(
        {
          "status": "NOT_FOUND"
        }
    )
    httpBackend.when('GET', /.*tarjonta-service\/rest\/v1\/hakukohde\/1.2.246.561.20.00000000001/).respond(
        {
            "result":{
                "hakukohteenNimet":{"kieli_fi":"Testi hakukohde"}
            },
            "status":"OK"
        }
    )
  httpBackend.when('GET', /.*tarjonta-service\/rest\/v1\/hakukohde\/1.2.246.561.20.00000000002/).respond(500, "test error")
}
