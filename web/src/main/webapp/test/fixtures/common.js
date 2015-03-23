var httpFixtures = function () {
    var httpBackend = testFrame().httpBackend
    var fixtures = {}
    fixtures.organisaatioService = {
        pikkarala: function() {
            httpBackend.when('GET', /.*\/organisaatio-service\/rest\/organisaatio\/v2\/hae\?aktiiviset=true&lakkautetut=false&organisaatiotyyppi=Oppilaitos&searchStr=Pik&suunnitellut=true$/).respond({"numHits":1,"organisaatiot":[{"oid":"1.2.246.562.10.39644336305","alkuPvm":694216800000,"parentOid":"1.2.246.562.10.80381044462","parentOidPath":"1.2.246.562.10.39644336305/1.2.246.562.10.80381044462/1.2.246.562.10.00000000001","oppilaitosKoodi":"06345","oppilaitostyyppi":"oppilaitostyyppi_11#1","match":true,"nimi":{"fi":"Pikkaralan ala-aste"},"kieletUris":["oppilaitoksenopetuskieli_1#1"],"kotipaikkaUri":"kunta_564","children":[],"organisaatiotyypit":["OPPILAITOS"],"aliOrganisaatioMaara":0}]})
        return fixtures;
        }
    }
    return fixtures
}