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
        organizationSearch: function () {
            return S("#organisaatioTerm")
        },
        henkiloSearch: function () {
            return S('#henkiloTerm')
        },
        searchButton: function () {
            return Button(function () {
                return S("#filterForm button[type=submit]").first()
            })
        },
        dropDownMenu: function () {
            return S("#filterForm ul.dropdown-menu").first()
        },
        henkiloTiedot: function () {
            return S('#henkiloTiedot')
        },
        suoritusTiedot: function () {
            return S('#suoritusTiedot')
        },
        luokkaTiedot: function () {
            return S('#luokkaTiedot')
        },
        resultsTable: function () {
            return S("#table-scroller").find("tr")
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
    organizationDropDownMenuChild: function(n) {
        return "#filterForm ul.dropdown-menu li:nth-child("+n+")"
    }
})