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
    hetuTieto: "#hetuTieto",
    suoritusTiedot: "#suoritusTiedot",
    luokkaTiedot: "#luokkaTiedot",
    luokkaTaso: ".test-luokkaTaso",
    suoritusMyontaja: ".test-suoritusMyontaja",
    suoritusKoulutus: ".test-suoritusKoulutus",
    suoritusYksilollistetty: ".test-suoritusYksilollistetty",
    suoritusKieli: ".test-suoritusKieli",
    suoritusTila: ".test-suoritusTila",
    saveButton: ".test-saveButton"

})
