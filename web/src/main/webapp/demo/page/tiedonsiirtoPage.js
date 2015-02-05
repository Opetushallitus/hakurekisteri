function TiedonsiirtoPage() {
    function isLocalhost() {
        return location.host === 'localhost:8080'
    }
    var tiedonsiirtoPage = openPage((isLocalhost() ? '' : '/suoritusrekisteri') + "/#/tiedonsiirto/lahetys", function() {
        return S("#uploadForm").length === 1
    });

    var pageFunctions = {
        uploadForm: function() {
            return S("#uploadForm").first()
        },
        arvosanatRadio: function () {
            return S("input#tyyppiarvosanat").first()
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
        uploadResult: function() {
            return S("#uploadResult").first()
        },
        openPage: function() {
            return tiedonsiirtoPage()
                .then(wait.until(function() {
                    return pageFunctions.arvosanatRadio().length === 1
                        && pageFunctions.tiedostoInput().length === 1
                }))
        }
    };

    return pageFunctions;
}