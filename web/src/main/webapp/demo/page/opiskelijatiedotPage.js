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