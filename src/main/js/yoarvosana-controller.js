'use strict';

function YoarvosanaCtrl($scope, $rootScope, $q, $log, Arvosanat, suoritusId) {
    $scope.koetaulukko = [];
    $scope.loading = true;

    function isEditable(myonnetty) {
        return !myonnetty || myonnetty.match(/^[0-9.]*\.19[0-8][0-9]$/)
    }

    Arvosanat.query({ suoritus: suoritusId }, function(arvosanat) {
        $scope.koetaulukko = arvosanat.filter(function(a) { return a.arvio.asteikko === "YO" }).map(function(a) {
            return {
                id: a.id,
                aine: a.aine,
                lisatieto: a.lisatieto,
                pakollinen: !a.valinnainen,
                myonnetty: a.myonnetty,
                arvosana: a.arvio.arvosana,
                pisteet: a.arvio.pisteet,
                editable: isEditable(a.myonnetty)
            }
        });
        $scope.loading = false;
    }, function() {
        $scope.loading = false;
        $rootScope.modalInstance.close({
            type: "danger",
            messageKey: "suoritusrekisteri.muokkaa.yoarvosanat.arvosanapalveluongelma",
            message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
        });
    });

    $scope.addKoe = function() {
        $scope.koetaulukko.push({ pakollinen: true, editable: true })
    };

    $scope.save = function() {
        var arvosanat = [];
        for (var i = 0; i < $scope.koetaulukko.length; i++) {
            var k = $scope.koetaulukko[i];
            if (k.lisatieto && k.aine && k.arvosana && k.myonnetty) {
                arvosanat.push(new Arvosanat({
                    id: k.id,
                    aine: k.aine,
                    lisatieto: k.lisatieto,
                    suoritus: suoritusId,
                    valinnainen: !k.pakollinen,
                    myonnetty: k.myonnetty,
                    "delete": k.delete,
                    arvio: {
                        arvosana: k.arvosana,
                        asteikko: "YO",
                        pisteet: (k.pisteet === '' ? null : k.pisteet)
                    }
                }))
            }
        }

        function removeArvosana(arvosana, d) {
            arvosana.$remove(function () {
                d.resolve("remove ok")
            }, function (err) {
                $log.error("error removing, retrying to remove: " + err);
                arvosana.$remove(function () {
                    d.resolve("retry remove ok");
                }, function (retryErr) {
                    $log.error("retry remove failed: " + retryErr);
                    d.reject("retry save failed");
                });
            })
        }
        function saveArvosana(arvosana, d) {
            arvosana.$save(function (saved) {
                d.resolve("save ok: " + saved.id)
            }, function (err) {
                $log.error("error saving, retrying to save: " + err);
                arvosana.$save(function (retriedSave) {
                    d.resolve("retry save ok: " + retriedSave.id);
                }, function (retryErr) {
                    $log.error("retry save failed: " + retryErr);
                    d.reject("retry save failed");
                });
            })
        }
        var deferreds = [];
        function saveArvosanat() {
            angular.forEach(arvosanat, function(arvosana) {
                var d = $q.defer();
                this.push(d);
                if (arvosana.delete) {
                    if (arvosana.id) removeArvosana(arvosana, d)
                } else saveArvosana(arvosana, d);

            }, deferreds);
        }
        saveArvosanat();

        var allSaved = $q.all(deferreds.map(function(d) { return d.promise }));
        allSaved.then(function() {
            $log.debug("all saved");
            $rootScope.modalInstance.close({
                type: "success",
                messageKey: "suoritusrekisteri.muokkaa.yoarvosanat.tallennettu",
                message: "Arvosanat tallennettu."
            });
        }, function() {
            $log.error("saving failed");
            $rootScope.modalInstance.close({
                type: "danger",
                messageKey: "suoritusrekisteri.muokkaa.yoarvosanat.tallennuseionnistunut",
                message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen."
            });
        });
    };

    $scope.cancel = function() {
        $rootScope.modalInstance.close()
    };

    $scope.tutkintokerrat = tutkintokerrat();

    function tutkintokerrat() {
        var kerrat = [];
        for (var i = 1989; i > 1899; i--) {
            kerrat.push({value: "21.12." + i, text: "21.12." + i + " (" + i + "S)"});
            kerrat.push({value: "01.06." + i, text: "01.06." + i + " (" + i + "K)"});
        }
        return kerrat;
    }

    $scope.getText = function(value, values) {
        values.some(function(v) { return v.value === value })
    };

    var aineet = {
        "SA": [
            {"value": "SAKSALKOUL", "text": "Saksalaisen koulun oppimäärä"},
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "IT": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "PS": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "HI": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "LA": [
            {"value": "D", "text": "Lyhyt oppimäärä (LATINA)"},
            {"value": "C", "text": "Laajempi oppimäärä"}
        ],
        "UN": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"}
        ],
        "FY": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "MA": [
            {"value": "PITKA", "text": "Pitkä oppimäärä (MA)"},
            {"value": "LYHYT", "text": "Lyhyt oppimäärä (MA)"}
        ],
        "IS": [
            {"value": "AI", "text": "Äidinkieli"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "EN": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"},
            {"value": "KYPSYYS", "text": "Kypsyyskoe (VAIN EN)"}
        ],
        "KE": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "VE": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "YH": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "BI": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "RU": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "AI", "text": "Äidinkieli"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "VI2", "text": "toisena kielenä (FI/RU)"}
        ],
        "FF": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "ES": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "ZA": [
            {"value": "AI", "text": "Äidinkieli"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "GE": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "UO": [
            {"value": "REAALI", "text": "Reaalikoe (VANHA)"},
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "FI": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "AI", "text": "Äidinkieli"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"},
            {"value": "VI2", "text": "toisena kielenä (FI/RU)"}
        ],
        "ET": [
            {"value": "REAALI", "text": "Reaalikoe (VANHA)"},
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "QS": [
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "KR": [
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "PG": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "TE": [
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ],
        "RA": [
            {"value": "A", "text": "Pitkä oppimäärä (KIELI)"},
            {"value": "B", "text": "Keskipitkä oppimäärä (KIELI)"},
            {"value": "C", "text": "Lyhyt oppimäärä (KIELI)"}
        ],
        "UE": [
            {"value": "REAALI", "text": "Reaalikoe (VANHA)"},
            {"value": "AINEREAALI", "text": "Ainemuotoinen reaali"}
        ]
    };

    var aineKielistykset = {
        "RU": "Ruotsi",
        "FI": "Suomi",
        "ZA": "Pohjoissaame",
        "EN": "Englanti",
        "RA": "Ranska",
        "PG": "Portugali",
        "UN": "Unkari",
        "IS": "Inarinsaame",
        "KR": "Kreikka",
        "LA": "Latina",
        "MA": "Matematiikka",
        "ES": "Espanja",
        "SA": "Saksa",
        "IT": "Italia",
        "VE": "Venäjä",
        "QS": "Koltansaame",
        "RR": "Reaali",
        "UE": "Ev.lut. uskonto",
        "UO": "Ortodoksiuskonto",
        "ET": "Elämänkatsomustieto",
        "FF": "Filosofia",
        "PS": "Psykologia",
        "HI": "Historia",
        "YH": "Yhteiskuntaoppi",
        "FY": "Fysiikka",
        "KE": "Kemia",
        "BI": "Biologia",
        "GE": "Maantiede",
        "TE": "Terveystieto"
    };

    function getAineet() {
        return Object.keys(aineet).map(function(k) {
            return {value: k, text: aineKielistykset[k]}
        }).sort(function(a, b) {
            return a.text === b.text ? 0 : a.text < b.text ? -1 : 1;
        })
    }
    
    $scope.aineet = getAineet();

    $scope.getTasot = function(yoAine) {
        return aineet[yoAine] ? aineet[yoAine] : []
    };

    $scope.arvosanat = [
        {value: "L", text: "(L) Laudatur"},
        {value: "M", text: "(M) Magna cum laude approbatur"},
        {value: "C", text: "(C) Cum laude approbatur"},
        {value: "B", text: "(B) Lubenter approbatur"},
        {value: "A", text: "(A) Approbatur"},
        {value: "I", text: "(I) Improbatur"}
    ];
}

