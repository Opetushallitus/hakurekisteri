function TiedonsiirtoPage() {
    function isLocalhost() {
        return location.host.indexOf('localhost') > -1
    }
    var tiedonsiirtoPage = openPage((isLocalhost() ? '' : '/suoritusrekisteri') + "/#/tiedonsiirto/lahetys", function() {
        return S("#uploadForm").length === 1
    });

    var pageFunctions = {
        uploadForm: function() {
            return S("#uploadForm").first()
        },
        arvosanatRadio: function () {
            return Button(function () {
                return S("input#tyyppiarvosanat").first()
            })
        },
        perustiedotRadio: function () {
            return Button(function () {
                return S ("input#tyyppiperustiedot").first()
            })
        },
        tiedostoInput: function() {
            return S("input#tiedosto").first()
        },
        resetButton: function() {
            return Button(function() {
                return S("#uploadForm button[type=button]").first()
            })
        },
        submitButton: function () {
            return Button(function() {
                return S("#uploadForm button[type=submit]").first()
            })
        },
        alerts: function() {
            return S("div.alert.message")
        },
        validationErrors: function() {
            return S(".validation-error")
        },
        validationErrorNames: function() {
            return S(".validation-error .name")
        },
        validationErrorCounts: function() {
            return S(".validation-error .count")
        },
        uploadResult: function() {
            return S("#uploadResult").first()
        },
        openPage: function(done) {
            return tiedonsiirtoPage()
                .then(wait.until(function() {
                    var pageReady = pageFunctions.arvosanatRadio().isVisible() && pageFunctions.tiedostoInput().length === 1
                    if(pageReady) {
                        done()
                    }
                    return pageReady
                }))
        }
    };

    return pageFunctions;
}