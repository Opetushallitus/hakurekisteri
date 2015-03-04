(function () {
    function asyncPrint(s) {
        return function () {
            console.log(s)
        }
    }

    describe('Opiskelijatiedot', function () {
        var page = opiskelijatiedotPage()
        var dummyData = [{
            "etunimet": "aa",
            "syntymaaika": "1958-10-12",
            "passinnumero": null,
            "hetu": "123456-789",
            "kutsumanimi": "aa",
            "oidHenkilo": "1.2.246.562.24.71944845619",
            "oppijanumero": null,
            "sukunimi": "AA",
            "sukupuoli": "1",
            "turvakielto": null,
            "henkiloTyyppi": "OPPIJA",
            "eiSuomalaistaHetua": false,
            "passivoitu": false,
            "yksiloity": false,
            "yksiloityVTJ": true,
            "yksilointiYritetty": false,
            "duplicate": false,
            "created": null,
            "modified": null,
            "kasittelijaOid": null,
            "asiointiKieli": null,
            "aidinkieli": null,
            "kayttajatiedot": null,
            "kielisyys": [],
            "kansalaisuus": []
        }, {
            "etunimet": "aaa",
            "syntymaaika": "1900-07-09",
            "passinnumero": null,
            "hetu": "090700-386W",
            "kutsumanimi": "aaa",
            "oidHenkilo": "1.2.246.562.24.49719248091",
            "oppijanumero": null,
            "sukunimi": "aaa",
            "sukupuoli": "2",
            "turvakielto": null,
            "henkiloTyyppi": "OPPIJA",
            "eiSuomalaistaHetua": false,
            "passivoitu": false,
            "yksiloity": false,
            "yksiloityVTJ": true,
            "yksilointiYritetty": false,
            "duplicate": false,
            "created": null,
            "modified": null,
            "kasittelijaOid": null,
            "asiointiKieli": null,
            "aidinkieli": null,
            "kayttajatiedot": null,
            "kielisyys": [],
            "kansalaisuus": []
        }, {
            "etunimet": "aaa",
            "syntymaaika": "1998-03-06",
            "passinnumero": null,
            "hetu": "060398-7570",
            "kutsumanimi": "aaa",
            "oidHenkilo": "1.2.246.562.24.76359038731",
            "oppijanumero": null,
            "sukunimi": "aaa",
            "sukupuoli": "1",
            "turvakielto": null,
            "henkiloTyyppi": "OPPIJA",
            "eiSuomalaistaHetua": false,
            "passivoitu": false,
            "yksiloity": false,
            "yksiloityVTJ": true,
            "yksilointiYritetty": false,
            "duplicate": false,
            "created": null,
            "modified": null,
            "kasittelijaOid": null,
            "asiointiKieli": null,
            "aidinkieli": null,
            "kayttajatiedot": null,
            "kielisyys": [],
            "kansalaisuus": []
        }, {
            "etunimet": "aaa",
            "syntymaaika": "1920-04-26",
            "passinnumero": null,
            "hetu": "260420-382F",
            "kutsumanimi": "aaa",
            "oidHenkilo": "1.2.246.562.24.87951154293",
            "oppijanumero": null,
            "sukunimi": "aaa",
            "sukupuoli": "2",
            "turvakielto": null,
            "henkiloTyyppi": "OPPIJA",
            "eiSuomalaistaHetua": false,
            "passivoitu": false,
            "yksiloity": false,
            "yksiloityVTJ": true,
            "yksilointiYritetty": false,
            "duplicate": false,
            "created": null,
            "modified": null,
            "kasittelijaOid": null,
            "asiointiKieli": null,
            "aidinkieli": null,
            "kayttajatiedot": null,
            "kielisyys": [],
            "kansalaisuus": []
        }, {
            "etunimet": "aaa",
            "syntymaaika": "1919-07-01",
            "passinnumero": null,
            "hetu": "010719-917S",
            "kutsumanimi": "aaa",
            "oidHenkilo": "1.2.246.562.24.98743797763",
            "oppijanumero": null,
            "sukunimi": "aaa",
            "sukupuoli": "1",
            "turvakielto": null,
            "henkiloTyyppi": "OPPIJA",
            "eiSuomalaistaHetua": false,
            "passivoitu": false,
            "yksiloity": false,
            "yksiloityVTJ": true,
            "yksilointiYritetty": false,
            "duplicate": false,
            "created": null,
            "modified": null,
            "kasittelijaOid": null,
            "asiointiKieli": null,
            "aidinkieli": null,
            "kayttajatiedot": null,
            "kielisyys": [],
            "kansalaisuus": []
        }]
        beforeEach(function () {
            page.openPage()
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
                        testFrame().httpBackend.when('POST', /.*\/resources\/henkilo\/henkilotByHenkiloOidList/).respond(dummyData);
                    })
                    .then(function () {
                        page.organizationSearch().val("Pikkaralan ala-aste").change()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        page.dropDownMenu().children().first().click()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        page.searchButton().click()
                    })
                    .then(wait.forAngular)
                    .then(function () {
                        expect(page.resultsTable().length).to.equal(5)
                        done()
                    })
            })
        })
    })
})();