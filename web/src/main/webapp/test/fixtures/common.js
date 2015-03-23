var httpFixtures = function () {
    var httpBackend = testFrame().httpBackend
    var fixtures = {}
    fixtures.organisaatioService = {
        pikkarala: function() {
            httpBackend.when('GET', /.*\/organisaatio-service\/rest\/organisaatio\/v2\/hae\?aktiiviset=true&lakkautetut=false&organisaatiotyyppi=Oppilaitos&searchStr=Pik&suunnitellut=true$/).respond({"numHits":1,"organisaatiot":[{"oid":"1.2.246.562.10.39644336305","alkuPvm":694216800000,"parentOid":"1.2.246.562.10.80381044462","parentOidPath":"1.2.246.562.10.39644336305/1.2.246.562.10.80381044462/1.2.246.562.10.00000000001","oppilaitosKoodi":"06345","oppilaitostyyppi":"oppilaitostyyppi_11#1","match":true,"nimi":{"fi":"Pikkaralan ala-aste"},"kieletUris":["oppilaitoksenopetuskieli_1#1"],"kotipaikkaUri":"kunta_564","children":[],"organisaatiotyypit":["OPPILAITOS"],"aliOrganisaatioMaara":0}]})
        }
    }
    fixtures.authenticationService = {
        aarne: function() {
            httpBackend.when('GET', /.*\/authentication-service\/resources\/henkilo\/1\.2\.246\.562\.24\.71944845619$/).respond({"id":90176,"etunimet":"Aarne","syntymaaika":"1958-10-12","passinnumero":null,"hetu":"123456-789","kutsumanimi":"aa","oidHenkilo":"1.2.246.562.24.71944845619","oppijanumero":null,"sukunimi":"AA","sukupuoli":"1","turvakielto":null,"henkiloTyyppi":"OPPIJA","eiSuomalaistaHetua":false,"passivoitu":false,"yksiloity":false,"yksiloityVTJ":true,"yksilointiYritetty":false,"duplicate":false,"created":null,"modified":null,"kasittelijaOid":null,"asiointiKieli":null,"aidinkieli":null,"kayttajatiedot":null,"kielisyys":[],"kansalaisuus":[]})
        },
        tyyne: function() {
            httpBackend.when('GET', /.*\/authentication-service\/resources\/henkilo\/1\.2\.246\.562\.24\.98743797763$/).respond({"id":90177,"etunimet":"Tyyne","syntymaaika":"1919-07-01","passinnumero":null,"hetu":"010719-917S","kutsumanimi":"aaa","oidHenkilo":"1.2.246.562.24.98743797763","oppijanumero":null,"sukunimi":"aaa","sukupuoli":"1","turvakielto":null,"henkiloTyyppi":"OPPIJA","eiSuomalaistaHetua":false,"passivoitu":false,"yksiloity":false,"yksiloityVTJ":true,"yksilointiYritetty":false,"duplicate":false,"created":null,"modified":null,"kasittelijaOid":null,"asiointiKieli":null,"aidinkieli":null,"huoltaja":null,"kayttajatiedot":null,"kielisyys":[],"kansalaisuus":[],"yhteystiedotRyhma":[]})
        },
        foobar: function() {
            httpBackend.when('GET', /.*\/authentication-service\/resources\/henkilo\?index=0&count=1&no=true&p=false&s=true&q=FOOBAR$/).respond({"totalCount":0,"results":[]})
        }
    }
    return fixtures
}