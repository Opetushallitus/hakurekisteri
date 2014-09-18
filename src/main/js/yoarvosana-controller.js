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
                koe: a.aine,
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
            if (k.koe && k.arvosana && k.myonnetty) {
                arvosanat.push(new Arvosanat({
                    id: k.id,
                    aine: k.koe,
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
            kerrat.push({value: "01.12." + i, text: "01.12." + i + " (" + i + "S)"});
            kerrat.push({value: "01.06." + i, text: "01.06." + i + " (" + i + "K)"});
        }
        return kerrat;
    }

    $scope.getText = function(value, values) {
        values.some(function(v) { return v.value === value })
    };

    $scope.kokeet = [
        {value: "A", text: "Äidinkielen koe, suomi"},
        {value: "A5", text: "Suomi toisena kielenä"},
        {value: "O", text: "Äidinkielen koe, ruotsi"},
        {value: "O5", text: "Ruotsi toisena kielenä"},
        {value: "BA", text: "Ruotsi, pitkä oppimäärä"},
        {value: "BB", text: "Ruotsi, keskipitkä oppimäärä"},
        {value: "CA", text: "Suomi, pitkä oppimäärä"},
        {value: "CB", text: "Suomi, keskipitkä oppimäärä"},
        {value: "CC", text: "Suomi, lyhyt oppimäärä"},
        {value: "DC", text: "Pohjoissaame, lyhyt oppimäärä"},
        {value: "EA", text: "Englanti, pitkä oppimäärä"},
        {value: "EB", text: "Englanti, keskipitkä oppimäärä"},
        {value: "EC", text: "Englanti, lyhyt oppimäärä"},
        {value: "FA", text: "Ranska, pitkä oppimäärä"},
        {value: "FB", text: "Ranska, keskipitkä oppimäärä"},
        {value: "FC", text: "Ranska, lyhyt oppimäärä"},
        {value: "GA", text: "Portugali, pitkä oppimäärä"},
        {value: "GB", text: "Portugali, keskipitkä oppimäärä"},
        {value: "GC", text: "Portugali, lyhyt oppimäärä"},
        {value: "HA", text: "Unkari, pitkä oppimäärä"},
        {value: "HB", text: "Unkari, keskipitkä oppimäärä"},
        {value: "I", text: "Äidinkielen koe, inarinsaame"},
        {value: "IC", text: "Inarinsaame, lyhyt oppimäärä"},
        {value: "QC", text: "Koltan saame, lyhyt oppimäärä"},
        {value: "J", text: "Englanninkielinen kypsyyskoe"},
        {value: "KC", text: "Kreikka, lyhyt oppimäärä"},
        {value: "L1", text: "Latina, lyhyt oppimäärä"},
        {value: "L7", text: "Latina, laajempi oppimäärä"},
        {value: "M", text: "Matematiikan koe, pitkä oppimäärä"},
        {value: "N", text: "Matematiikan koe, lyhyt oppimäärä"},
        {value: "PA", text: "Espanja, pitkä oppimäärä"},
        {value: "PB", text: "Espanja, keskipitkä oppimäärä"},
        {value: "PC", text: "Espanja, lyhyt oppimäärä"},
        {value: "RR", text: "Reaali, ev lut uskonnon kysymykset"},
        {value: "RO", text: "Reaali, ortod.uskonnon kysymykset"},
        {value: "RY", text: "Reaali, elämänkatsomustiedon kysymykset"},
        {value: "SA", text: "Saksa, pitkä oppimäärä"},
        {value: "SB", text: "Saksa, keskipitkä oppimäärä"},
        {value: "SC", text: "Saksa, lyhyt oppimäärä"},
        {value: "S9", text: "Saksalaisen koulun saksan kielen koe"},
        {value: "TA", text: "Italia, pitkä oppimäärä"},
        {value: "TB", text: "Italia, keskipitkä oppimäärä"},
        {value: "TC", text: "Italia, lyhyt oppimäärä"},
        {value: "VA", text: "Venäjä, pitkä oppimäärä"},
        {value: "VB", text: "Venäjä, keskipitkä oppimäärä"},
        {value: "VC", text: "Venäjä, lyhyt oppimäärä"},
        {value: "Z", text: "Äidinkielen koe, pohjoissaame"},
        {value: "UE", text: "Ev.lut. Uskonto"},
        {value: "UO", text: "Ortodoksiuskonto"},
        {value: "ET", text: "Elämänkatsomustieto"},
        {value: "FF", text: "Filosofia"},
        {value: "PS", text: "Psykologia"},
        {value: "HI", text: "Historia"},
        {value: "FY", text: "Fysiikka"},
        {value: "KE", text: "Kemia"},
        {value: "BI", text: "Biologia"},
        {value: "GE", text: "Maantiede"},
        {value: "TE", text: "Terveystieto"},
        {value: "YH", text: "Yhteiskuntaoppi"},
        {value: "E1", text: "Englanti, pitkä oppimäärä"},
        {value: "E2", text: "Englanti, keskipitkä oppimäärä"},
        {value: "F1", text: "Ranska, pitkä oppimäärä"},
        {value: "F2", text: "Ranska, keskipitkä oppimäärä"},
        {value: "G1", text: "Portugali, pitkä oppimäärä"},
        {value: "G2", text: "Portugali, keskipitkä oppimäärä"},
        {value: "H1", text: "Unkari, pitkä oppimäärä"},
        {value: "H2", text: "Unkari, keskipitkä oppimäärä"},
        {value: "P1", text: "Espanja, pitkä oppimäärä"},
        {value: "P2", text: "Espanja, keskipitkä oppimäärä"},
        {value: "S1", text: "Saksa, pitkä oppimäärä"},
        {value: "S2", text: "Saksa, keskipitkä oppimäärä"},
        {value: "T1", text: "Italia, pitkä oppimäärä"},
        {value: "T2", text: "Italia, keskipitkä oppimäärä"},
        {value: "V1", text: "Venäjä, pitkä oppimäärä"},
        {value: "V2", text: "Venäjä, keskipitkä oppimäärä"}
    ];

    $scope.arvosanat = [
        {value: "L", text: "(L) Laudatur"},
        {value: "M", text: "(M) Magna cum laude approbatur"},
        {value: "C", text: "(C) Cum laude approbatur"},
        {value: "B", text: "(B) Lubenter approbatur"},
        {value: "A", text: "(A) Approbatur"},
        {value: "I", text: "(I) Improbatur"}
    ];
}

