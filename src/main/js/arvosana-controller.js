'use strict';

function ArvosanaCtrl($scope, $rootScope, $http, $q, $log, Arvosanat, Suoritukset, suoritusId) {
    $scope.arvosanataulukko = [];
    $scope.oppiaineet = [];
    $scope.valinnaisuudet = [
        {value: false, text: getOphMsg("suoritusrekisteri.valinnaisuus.ei", "Ei")},
        {value: true, text: getOphMsg("suoritusrekisteri.valinnaisuus.kylla", "Kyllä")}
    ];
    $scope.arvosanat = [
        {value: "", text: "Ei arvosanaa"},
        {value: "4", text: "4"},
        {value: "5", text: "5"},
        {value: "6", text: "6"},
        {value: "7", text: "7"},
        {value: "8", text: "8"},
        {value: "9", text: "9"},
        {value: "10", text: "10"}
    ];
    $scope.kielet = [];
    $scope.aidinkieli = [];
    getKoodistoAsOptionArray($http, 'kielivalikoima', 'fi', $scope.kielet, 'koodiArvo');
    getKoodistoAsOptionArray($http, 'aidinkielijakirjallisuus', 'fi', $scope.aidinkieli, 'koodiArvo');

    var arvosanaSort = {
        AI: 10, A1: 20, A12: 21, A2: 30, A22: 31, B1: 40, B2: 50, B22: 51,
        B23: 52, B3: 53, B32: 54, B33: 55, MA: 60, BI: 70, GE: 80, FY: 90,
        KE: 100, TE: 110, KT: 120, HI: 130, YH: 140, MU: 150, KU: 160, KS: 170,
        LI: 180, KO: 190, PS: 200, FI: 210
    };

    Suoritukset.get({ suoritusId: suoritusId }, function(suoritus) {
        var pohjakoulutusFilter = "onperusasteenoppiaine_1";
        if (suoritus.komo === komo.ylioppilastutkinto) {
            pohjakoulutusFilter = "onlukionoppiaine_1";
        }

        var koodistoPromises = [];

        $http.get(koodistoServiceUrl + '/rest/json/oppiaineetyleissivistava/koodi/', { cache: true })
            .success(function(koodit) {
                angular.forEach(koodit, function(koodi) {
                    var p = $http.get(koodistoServiceUrl + '/rest/json/relaatio/sisaltyy-alakoodit/' + koodi.koodiUri, { cache: true })
                        .success(function (alaKoodit) {
                            $scope.oppiaineet.push({ koodi: koodi, alaKoodit: alaKoodit });
                        });
                    koodistoPromises.push(p);
                });
                while (koodistoPromises.length < koodit.length) {
                    setTimeout(function() { /* wait */ }, 100);
                }
                var allDone = $q.all(koodistoPromises);
                allDone.then(function() {
                    function findArvosana(aine, lisatieto, arvosanat, valinnainen) {
                        for (var i = 0; i < arvosanat.length; i++) {
                            if (!arvosanat[i].taken && arvosanat[i].aine === aine && arvosanat[i].lisatieto === lisatieto && arvosanat[i].valinnainen === valinnainen) {
                                arvosanat[i].taken = true;
                                return arvosanat[i];
                            }
                        }
                        return null;
                    }
                    function getOppiaineNimi(oppiainekoodi) {
                        return oppiainekoodi.koodi.metadata.sort(function(a, b) { return (a.kieli < b.kieli ? -1 : 1) })[0].nimi;
                    }
                    function fetchArvosanat() {
                        Arvosanat.query({ suoritus: suoritusId }, function(arvosanat) {
                            var kouluArvosanat = arvosanat.filter(function(a) { return a.arvio.asteikko === "4-10" });
                            var oppiainekoodit = $scope.oppiaineet.filter(function(o) {
                                return o.alaKoodit.some(function(alakoodi) {
                                    return alakoodi.koodiUri === pohjakoulutusFilter;
                                })
                            });
                            var arvosanataulukko = {};
                            opp: for (var j = 0; j < oppiainekoodit.length; j++) {
                                var oppiainekoodi = oppiainekoodit[j];
                                var aine = oppiainekoodi.koodi.koodiArvo;
                                for (var i = 0; i < kouluArvosanat.length; i++) {
                                    if (kouluArvosanat[i].aine === aine) {
                                        var lisatieto = kouluArvosanat[i].lisatieto;
                                        var a = arvosanataulukko[aine + ';' + lisatieto];
                                        if (!a) a = {};

                                        a.aine = aine;
                                        a.aineNimi = getOppiaineNimi(oppiainekoodi);
                                        a.lisatieto = lisatieto;

                                        var arvosana = findArvosana(aine, lisatieto, kouluArvosanat, false);
                                        a.arvosana = arvosana ? arvosana.arvio.arvosana : null;
                                        a.arvosanaId = arvosana ? arvosana.id : null;

                                        var valinnainen = findArvosana(aine, lisatieto, kouluArvosanat, true);
                                        a.arvosanaValinnainen = valinnainen ? valinnainen.arvio.arvosana : null;
                                        a.valinnainenId = valinnainen ? valinnainen.id : null;

                                        var toinenValinnainen = findArvosana(aine, lisatieto, kouluArvosanat, true);
                                        a.arvosanaToinenValinnainen = toinenValinnainen ? toinenValinnainen.arvio.arvosana : null;
                                        a.toinenValinnainenId = toinenValinnainen ? toinenValinnainen.id : null;

                                        arvosanataulukko[aine + ';' + lisatieto] = a;
                                        continue opp;
                                    }
                                }

                                arvosanataulukko[aine + ';'] = {
                                    aine: aine,
                                    aineNimi: getOppiaineNimi(oppiainekoodi),
                                    arvosana: ''
                                }
                            }

                            function hasRedundantArvosana(kouluArvosanat) {
                                return kouluArvosanat.some(function(a) { return !a.taken })
                            }

                            if (hasRedundantArvosana(kouluArvosanat)) {
                                $rootScope.modalInstance.close(kouluArvosanat);
                                return;
                            }
                            $scope.arvosanataulukko = Object.keys(arvosanataulukko).map(function(key) {
                                return arvosanataulukko[key];
                            }).sort(function(a, b) {
                                if (a.aine === b.aine) return 0;
                                return (arvosanaSort[a.aine] < arvosanaSort[b.aine] ? -1 : 1);
                            });
                        }, function() {
                            $rootScope.modalInstance.close({
                                type: "danger",
                                messageKey: "suoritusrekisteri.muokkaa.arvosanat.arvosanapalveluongelma",
                                message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
                            })
                        });
                    }

                    fetchArvosanat();
                }, function() {
                    $log.error("some of the calls to koodisto service failed");
                    $rootScope.modalInstance.close({
                        type: "danger",
                        messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma",
                        message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
                    })
                })
            })
            .error(function() {
                $rootScope.modalInstance.close({
                    type: "danger",
                    messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma",
                    message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
                })
            });
    }, function() {
        $rootScope.modalInstance.close({
            type: "danger",
            messageKey: "suoritusrekisteri.muokkaa.arvosanat.taustapalveluongelma",
            message: "Taustapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
        })
    });

    $scope.isValinnainen = function(aine) {
        return $scope.oppiaineet.some(function(o) {
            return o.koodi.koodiArvo === aine && o.alaKoodit.some(function(alakoodi) { return alakoodi.koodiUri === 'oppiaineenvalinnaisuus_1' })
        })
    };

    $scope.isKielisyys = function(aine) {
        return $scope.oppiaineet.some(function(o) {
            return o.koodi.koodiArvo === aine && o.alaKoodit.some(function(alakoodi) { return alakoodi.koodiUri === 'oppiaineenkielisyys_1' })
        })
    };

    $scope.save = function() {
        var arvosanat = [];
        for (var i = 0; i < $scope.arvosanataulukko.length; i++) {
            var a = $scope.arvosanataulukko[i];
            if (a.aine && (a.arvosana || a.arvosanaId)) arvosanat.push(new Arvosanat({ id: a.arvosanaId, aine: a.aine, lisatieto: a.lisatieto, suoritus: suoritusId, arvio: { arvosana: a.arvosana, asteikko: "4-10" } }));
            if (a.aine && (a.arvosanaValinnainen || a.valinnainenId)) arvosanat.push(new Arvosanat({ id: a.valinnainenId, aine: a.aine, lisatieto: a.lisatieto, suoritus: suoritusId, arvio: { arvosana: a.arvosanaValinnainen, asteikko: "4-10" }, valinnainen: true }));
            if (a.aine && (a.arvosanaToinenValinnainen || a.toinenValinnainenId)) arvosanat.push(new Arvosanat({ id: a.toinenValinnainenId, aine: a.aine, lisatieto: a.lisatieto, suoritus: suoritusId, arvio: { arvosana: a.arvosanaToinenValinnainen, asteikko: "4-10" }, valinnainen: true }));
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
                if (arvosana.id && !arvosana.arvio.arvosana) {
                    removeArvosana(arvosana, d);
                } else {
                    saveArvosana(arvosana, d);
                }
            }, deferreds);
        }
        saveArvosanat();

        var allSaved = $q.all(deferreds.map(function(d) { return d.promise }));
        allSaved.then(function() {
            $log.debug("all saved");
            $rootScope.modalInstance.close({
                type: "success",
                messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennettu",
                message: "Arvosanat tallennettu."
            });
        }, function() {
            $log.error("saving failed");
            $rootScope.modalInstance.close({
                type: "danger",
                messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennuseionnistunut",
                message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen."
            });
        });
    };

    $scope.cancel = function() {
        $rootScope.modalInstance.close()
    };

}