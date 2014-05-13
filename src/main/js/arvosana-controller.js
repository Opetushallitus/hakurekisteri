'use strict';

function ArvosanaCtrl($scope, $rootScope, $http, $q, $log, Arvosanat, Suoritukset, suoritusId) {
    $scope.oppiaineet = [];
    $scope.valinnaisuudet = [
        {value: false, text: getOphMsg("suoritusrekisteri.valinnaisuus.ei", "Ei")},
        {value: true, text: getOphMsg("suoritusrekisteri.valinnaisuus.kylla", "Kyllä")}
    ];
    $scope.arvosanat = [];
    $scope.kielet = [];
    getKoodistoAsOptionArray($http, 'arvosanat', 'fi', $scope.arvosanat, 'nimi');
    getKoodistoAsOptionArray($http, 'kielivalikoima', 'fi', $scope.kielet, 'nimi');

    Suoritukset.get({ suoritusId: suoritusId }, function(suoritus) {
        var pohjakoulutusFilter = "onperusasteenoppiaine_1";
        if (suoritus.komo === "lukio") {
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
                                return arvosanat[i].arvio.arvosana;
                            }
                        }
                        return null;
                    }

                    function getOppiaineNimi(oppiainekoodi) {
                        return oppiainekoodi.koodi.metadata.sort(function(a, b) { return (a.kieli < b.kieli ? -1 : 1) })[0].nimi;
                    }
                    function fetchArvosanat() {
                        Arvosanat.query({ suoritus: suoritusId }, function(arvosanat) {
                            var oppiainekoodit = $scope.oppiaineet.filter(function(o) {
                                return o.alaKoodit.filter(function(alakoodi) {
                                    return alakoodi.koodiUri === pohjakoulutusFilter;
                                }).length > 0
                            });
                            var arvosanataulukko = {};
                            for (var j = 0; j < oppiainekoodit.length; j++) {
                                var oppiainekoodi = oppiainekoodit[j];
                                var aine = oppiainekoodi.koodi.koodiArvo;
                                for (var i = 0; i < arvosanat.length; i++) {
                                    var lisatieto = arvosanat[i].lisatieto;
                                    if (arvosanat[i].aine === aine) {
                                        var a = arvosanataulukko[aine + ';' + lisatieto];
                                        if (!a) a = {};

                                        a.aine = aine;
                                        a.aineNimi = getOppiaineNimi(oppiainekoodi);
                                        a.lisatieto = lisatieto;
                                        a.arvosana = findArvosana(aine, lisatieto, arvosanat, false);
                                        a.arvosanaValinnainen = findArvosana(aine, lisatieto, arvosanat, true);
                                        a.arvosanaToinenValinnainen = findArvosana(aine, lisatieto, arvosanat, true);

                                        arvosanataulukko[aine + ';' + lisatieto] = a;
                                    }
                                }
                                if (!arvosanataulukko[aine + ';' + lisatieto]) arvosanataulukko[aine + ';' + lisatieto] = { aine: aine }
                            }
                            $scope.arvosanataulukko = Object.keys(arvosanataulukko).map(function(key) {
                                return arvosanataulukko[key];
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
        return $scope.oppiaineet.filter(function(o) {
            return o.koodi.koodiArvo === aine && o.alaKoodit.filter(function(alakoodi) {
                return alakoodi.koodiUri === 'oppiaineenvalinnaisuus_1'
            }).length > 0
        }).length > 0
    };

    $scope.isKielisyys = function(aine) {
        return $scope.oppiaineet.filter(function(o) {
            return o.koodi.koodiArvo === aine && o.alaKoodit.filter(function(alakoodi) {
                return alakoodi.koodiUri === 'oppiaineenkielisyys_1'
            }).length > 0
        }).length > 0
    };

    $scope.save = function() {
        var arvosanat = [];
        for (var i = 0; i < $scope.arvosanataulukko.length; i++) {
            var a = $scope.arvosanataulukko[i];
            if (a.aine && a.arvosana) arvosanat.push(new Arvosanat({ aine: a.aine, lisatieto: a.lisatieto, suoritus: suoritusId, arvio: { arvosana: a.arvosana, asteikko: "4-10" } }));
            if (a.aine && a.arvosanaValinnainen) arvosanat.push(new Arvosanat({ aine: a.aine, lisatieto: a.lisatieto, suoritus: suoritusId, arvio: { arvosana: a.arvosanaValinnainen, asteikko: "4-10" }, valinnainen: true }));
            if (a.aine && a.arvosanaToinenValinnainen) arvosanat.push(new Arvosanat({ aine: a.aine, lisatieto: a.lisatieto, suoritus: suoritusId, arvio: { arvosana: a.arvosanaToinenValinnainen, asteikko: "4-10" }, valinnainen: true }));
        }
        var deferreds = [];
        for (var i = 0; i < arvosanat.length; i++) {
            var d = $q.defer();
            deferreds.push(d);
            var arvosana = arvosanat[i];
            arvosana.$save(function(saved) {
                d.resolve("saved: " + saved.id);
            }, function() {
                d.reject("save failed");
            });
        }

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
        $rootScope.modalInstance.close();
    };

}