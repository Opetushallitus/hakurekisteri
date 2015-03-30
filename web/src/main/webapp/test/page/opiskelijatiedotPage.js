function opiskelijatiedotPage() {
    function isLocalhost() {
        return location.host.indexOf('localhost') > -1
    }

    var opiskelijatiedotPage = openPage((isLocalhost() ? '' : '/suoritusrekisteri') + "/#/muokkaa-obd", function () {
        return S("#filterForm").length === 1
    })

    var pageFunctions = {
        filterForm: function () {
            return S("#filterForm").first()
        },
        openPage: function (done) {
            return opiskelijatiedotPage()
                .then(wait.until(function () {
                    var pageReady = pageFunctions.filterForm().length === 1
                    if (pageReady) {
                        done()
                    }
                    return pageReady
                }))
        }
    };
    return pageFunctions;
}

opiskelijatiedot = initSelectors({
    organizationSearch: "#organisaatioTerm",
    searchButton: "#filterForm button[type=submit]",
    resultsTable: "#table-scroller tr",
    resultsTableChild: function(n) {return "#table-scroller tr:nth-child("+n+")"},
    typeaheadMenuChild: function(n) {
        return "ul.dropdown-menu > li:nth-child("+n+"):has(a)"
    },
    henkiloSearch: "#henkiloTerm",
    henkiloTiedot: "#henkiloTiedot",
    vuosiSearch: "#vuosiTerm",
    hetu: ".test-hetu",
    etunimi: ".test-etunimi",
    sukunimi: ".test-sukunimi",
    suoritusTiedot: "#suoritusTiedot",
    luokkaTiedot: "#luokkaTiedot",
    hakijanIlmoittamaAlert: ".alert-info",
    suoritusMyontaja: ".test-suoritusMyontaja",
    suoritusKoulutus: ".test-suoritusKoulutus",
    suoritusYksilollistetty: ".test-suoritusYksilollistetty",
    suoritusKieli: ".test-suoritusKieli",
    suoritusValmistuminen: ".test-suoritusValmistuminen",
    suoritusTila: ".test-suoritusTila",
    luokkatietoOppilaitos: ".test-luokkatietoOppilaitos",
    luokkatietoLuokka: ".test-luokkatietoLuokka",
    luokkatietoLuokkaTaso: ".test-luokkatietoLuokkaTaso",
    luokkatietoAlkuPaiva: ".test-luokkatietoAlkuPaiva",
    luokkatietoLoppuPaiva: ".test-luokkatietoLoppuPaiva",
    opiskeluoikeusAlkuPaiva: ".test-opiskeluoikeusAlkuPaiva",
    opiskeluoikeusLoppuPaiva: ".test-opiskeluoikeusLoppuPaiva",
    opiskeluoikeusMyontaja: ".test-opiskeluoikeusMyontaja",
    opiskeluoikeusKoulutus: ".test-opiskeluoikeusKoulutus",
    saveButton: ".test-saveButton",
    arvosanaAineRivi: ".test-aineRivi:visible",
    arvosanaAineNimi: ".test-aineNimi",
    arvosanaMyonnetty: ".test-arvosanaMyonnetty:visible",
    arvosanaLisatieto: ".test-arvosanaLisatieto:visible",
    arvosanaPakollinenArvosana: ".test-arvosanaPakollinenArvosana:visible",
    arvosanaValinnainenArvosana: ".test-arvosanaValinnainenArvosana:visible"
})
