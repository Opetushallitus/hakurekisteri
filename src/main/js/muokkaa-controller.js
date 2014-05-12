'use strict';

function MuokkaaCtrl($scope, $rootScope, $routeParams, $location, $http, $log, $q, $modal, Opiskelijat, Suoritukset) {
    $scope.henkiloOid = $routeParams.henkiloOid;
    $scope.yksilollistamiset = [
        {value: "Ei", text: getOphMsg("suoritusrekisteri.yks.ei", "Ei")},
        {value: "Osittain", text: getOphMsg("suoritusrekisteri.yks.osittain", "Osittain")},
        {value: "Alueittain", text: getOphMsg("suoritusrekisteri.yks.alueittain", "Alueittain")},
        {value: "Kokonaan", text: getOphMsg("suoritusrekisteri.yks.kokonaan", "Kokonaan")}
    ];
    $scope.koulutukset = [
        {value: "ulkomainen", text: getOphMsg("suoritusrekisteri.komo.ulkomainen", "Ulkomainen")},
        {value: "peruskoulu", text: getOphMsg("suoritusrekisteri.komo.peruskoulu", "Peruskoulu")},
        {value: "lisaopetus", text: getOphMsg("suoritusrekisteri.komo.", "Perusopetuksen lisäopetus")},
        {value: "ammattistartti", text: getOphMsg("suoritusrekisteri.komo.ammattistartti", "Ammattistartti")},
        {value: "maahanmuuttaja", text: getOphMsg("suoritusrekisteri.komo.maahanmuuttaja", "Maahanmuuttaja")},
        {value: "valmentava", text: getOphMsg("suoritusrekisteri.komo.valmentava", "Valmentava")},
        // {value: "keskeytynyt", text: getOphMsg("suoritusrekisteri.komo.keskeytynyt", "Keskeytynyt")}, //TODO keskeytynyt ei käytössä
        {value: "lukio", text: getOphMsg("suoritusrekisteri.komo.lukio", "Lukio")}
    ];
    $scope.messages = [];
    $scope.suoritukset = [];
    $scope.luokkatiedot = [];

    $rootScope.addToMurupolku({href: "#/opiskelijat", key: "suoritusrekisteri.muokkaa.muru1", text: "Opiskelijoiden haku"}, true);
    $rootScope.addToMurupolku({key: "suoritusrekisteri.muokkaa.muru", text: "Muokkaa opiskelijan tietoja"}, false);

    function fetchHenkilotiedot() {
        $http.get(henkiloServiceUrl + '/resources/henkilo/' + encodeURIComponent($scope.henkiloOid), {cache: false})
            .success(function(henkilo) {
                if (henkilo) {
                    if (henkilo.duplicate === false) {
                        $scope.henkilo = henkilo;
                    } else {
                        $http.get(henkiloServiceUrl + '/resources/s2s/' + encodeURIComponent($scope.henkiloOid), {cache: false})
                            .success(function(masterHenkilo) {
                                $scope.henkilo = masterHenkilo;
                            })
                            .error(function() {
                                confirm(getOphMsg("suoritusrekisteri.muokkaa.henkilotietojenhakeminen", "Henkilötietojen hakeminen ei onnistunut. Yritä uudelleen?")) ? fetchHenkilotiedot() : back();
                            });
                    }
                }
            })
            .error(function() {
                confirm(getOphMsg("suoritusrekisteri.muokkaa.henkilotietojenhakeminen", "Henkilötietojen hakeminen ei onnistunut. Yritä uudelleen?")) ? fetchHenkilotiedot() : back();
            });
    }
    function fetchLuokkatiedot() {
        function enrich() {
            if ($scope.luokkatiedot) {
                angular.forEach($scope.luokkatiedot, function(luokkatieto) {
                    if (luokkatieto.oppilaitosOid) {
                        getOrganisaatio($http, luokkatieto.oppilaitosOid, function(organisaatio) {
                            luokkatieto.oppilaitos = organisaatio.oppilaitosKoodi;
                        });
                    }
                });
            }
        }

        Opiskelijat.query({henkilo: $scope.henkiloOid}, function(luokkatiedot) {
            $scope.luokkatiedot = luokkatiedot;
            enrich();
        }, function() {
            confirm(getOphMsg("suoritusrekisteri.muokkaa.luokkatietojenhakeminen", "Luokkatietojen hakeminen ei onnistunut. Yritä uudelleen?")) ? fetchLuokkatiedot() : back();
        });
    }
    function fetchSuoritukset() {
        function enrich() {
            if ($scope.suoritukset) {
                angular.forEach($scope.suoritukset, function(suoritus) {
                    if (suoritus.myontaja) {
                        getOrganisaatio($http, suoritus.myontaja, function(organisaatio) {
                            suoritus.oppilaitos = organisaatio.oppilaitosKoodi;
                        });
                    }
                });
            }
        }

        Suoritukset.query({henkilo: $scope.henkiloOid}, function(suoritukset) {
            $scope.suoritukset = suoritukset;
            enrich();
        }, function() {
            confirm(getOphMsg("suoritusrekisteri.muokkaa.suoritustietojenhakeminen", "Suoritustietojen hakeminen ei onnistunut. Yritä uudelleen?")) ? fetchSuoritukset() : back();
        });
    }

    fetchHenkilotiedot();
    fetchLuokkatiedot();
    fetchSuoritukset();

    $scope.getOppilaitos = function(searchStr) {
        if (searchStr && searchStr.trim().match(/^\d{5}$/))
            return $http.get(organisaatioServiceUrl + '/rest/organisaatio/' + searchStr)
                .then(function(result) {
                    return [result.data];
                }, function() { return [] });
        else if (searchStr && searchStr.length > 3)
            return $http.get(organisaatioServiceUrl + '/rest/organisaatio/hae',
                { params: { searchstr: searchStr, organisaatioTyyppi: "Oppilaitos" } })
                .then(function(result) {
                    if (result.data && result.data.numHits > 0) return result.data.organisaatiot;
                    else return [];
                }, function() { return [] });
        else return [];
    };

    function back() {
        if (history && history.back) history.back();
        else $location.path("/opiskelijat");
    }

    $scope.save = function() {
        var deferredValidations = [];
        function validateOppilaitoskoodit() {
            angular.forEach($scope.luokkatiedot.concat($scope.suoritukset), function(obj) {
                var deferredValidation = $q.defer();
                deferredValidations.push(deferredValidation);
                if (!obj.oppilaitos || !obj.oppilaitos.match(/^\d{5}$/)) {
                    $scope.messages.push({
                        type: "danger",
                        messageKey: "suoritusrekisteri.muokkaa.oppilaitoskoodipuuttuu",
                        message: "Oppilaitoskoodi puuttuu tai se on virheellinen.",
                        descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi",
                        description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                    });
                    deferredValidation.reject("error");
                } else {
                    getOrganisaatio($http, obj.oppilaitos, function (organisaatio) {
                        if (obj.myontaja) obj.myontaja = organisaatio.oid;
                        else obj.oppilaitosOid = organisaatio.oid;
                        deferredValidation.resolve("done");
                    }, function () {
                        $scope.messages.push({
                            type: "danger",
                            messageKey: "suoritusrekisteri.muokkaa.oppilaitostaeiloytynyt",
                            message: "Oppilaitosta ei löytynyt oppilaitoskoodilla.",
                            descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi",
                            description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                        });
                        deferredValidation.reject("error");
                    });
                }
            });
        }
        validateOppilaitoskoodit();

        var deferredSaves = [];
        function saveSuoritukset() {
            angular.forEach($scope.suoritukset, function(suoritus) {
                var deferredSave = $q.defer();
                deferredSaves.push(deferredSave);
                suoritus.$save(function (savedSuoritus) {
                    $log.debug("suoritus saved: " + savedSuoritus);
                    deferredSave.resolve("done");
                }, function () {
                    $scope.messages.push({
                        type: "danger",
                        messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja",
                        message: "Virhe tallennettaessa suoritustietoja.",
                        descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita",
                        description: "Yritä uudelleen."
                    });
                    deferredSave.reject("error saving suoritus: " + suoritus);
                });
            });
        }
        function saveLuokkatiedot() {
            angular.forEach($scope.luokkatiedot, function(luokkatieto) {
                var deferredSave = $q.defer();
                deferredSaves.push(deferredSave);
                luokkatieto.$save(function (savedLuokkatieto) {
                    $log.debug("luokkatieto saved: " + savedLuokkatieto);
                    deferredSave.resolve("done");
                }, function () {
                    $scope.messages.push({
                        type: "danger",
                        messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja",
                        message: "Virhe tallennettaessa luokkatietoja.",
                        descriptionKey: "suoritusrekisteri.muokkaa.virheluokkayrita",
                        description: "Yritä uudelleen."
                    });
                    deferredSave.reject("error saving luokkatieto: " + luokkatieto);
                });
            });
        }

        var validationPromise = $q.all(deferredValidations.map(function(deferred) { return deferred.promise }));
        validationPromise.then(function() {
            saveSuoritukset();
            saveLuokkatiedot();
        });

        var savePromise = $q.all(deferredSaves.map(function(deferred) { return deferred.promise }));
        savePromise.then(function() {
            $log.info("saved successfully");
            back();
        }, function(errors) {
            $log.error("error while saving: " + errors);
        });
    };
    $scope.cancel = function() {
        back();
    };
    $scope.addSuoritus = function() {
        $scope.suoritukset.push(new Suoritukset({ henkiloOid: $scope.henkiloOid, tila: "KESKEN", yksilollistaminen: "Ei", myontaja: "na" }));
    };
    $scope.editArvosana = function(suoritusId) {
        $rootScope.modalInstance = $modal.open({
            templateUrl: 'arvosanaedit.html',
            controller: ArvosanaCtrl,
            resolve: {
                suoritusId: function() { return suoritusId }
            }
        });

        $rootScope.modalInstance.result.then(function (message) {
            if (message) $scope.messages.push(message);
        }, function () {
            // error
        });
    };
    $scope.addLuokkatieto = function() {
        $scope.luokkatiedot.push(new Opiskelijat({ henkiloOid: $scope.henkiloOid, oppilaitosOid: "na" }));
    };
    $scope.removeMessage = function(message) {
        var index = $scope.messages.indexOf(message);
        if (index !== -1) $scope.messages.splice(index, 1);
    };

    function initDatepicker() {
        $scope.showWeeks = false;
        $scope.pickDate = function ($event, openedKey) {
            $event.preventDefault();
            $event.stopPropagation();
            $scope[openedKey] = true;
        };
        $scope.dateOptions = { 'year-format': "'yyyy'", 'starting-day': 1 };
        $scope.format = 'dd.MM.yyyy';
    }
    initDatepicker();
}

