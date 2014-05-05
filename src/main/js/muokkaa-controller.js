'use strict';

function MuokkaaCtrl($scope, $rootScope, $routeParams, $location, $http, $log, $q, Opiskelijat, Suoritukset) {
    $scope.henkiloOid = $routeParams.henkiloOid;
    $scope.yksilollistamiset = [
        {value: "Ei", text: "Ei (1)"},
        {value: "Osittain", text: "Osittain (2)"},
        {value: "Alueittain", text: "Alueittain (3)"},
        {value: "Kokonaan", text: "Kokonaan (6)"}
    ];
    $scope.koulutukset = [
        {value: "ulkomainen", text: "Ulkomainen (0)"},
        {value: "peruskoulu", text: "Peruskoulu"},
        {value: "ammattistartti", text: "Ammattistartti"},
        {value: "maahanmuuttaja", text: "Maahanmuuttaja"},
        {value: "valmentava", text: "Valmentava"},
        // {value: "keskeytynyt", text: "Keskeytynyt (7)"}, //TODO keskeytynyt ei vielä käytössä
        {value: "lukio", text: "Lukio (9)"}
    ];
    $scope.messages = [];

    $rootScope.addToMurupolku({href: "#/opiskelijat", text: "Opiskelijoiden haku"}, true);
    $rootScope.addToMurupolku({text: "Muokkaa opiskelijan tietoja"}, false);

    function enrichSuoritukset() {
        if ($scope.suoritukset) {
            angular.forEach($scope.suoritukset, function(suoritus) {
                if (suoritus.myontaja) {
                    getOrganisaatio($http, suoritus.myontaja, function(organisaatio) {
                        suoritus.oppilaitos = organisaatio.oppilaitosKoodi;
                    }, function() {});
                }
            });
        }
    }
    function enrichLuokkatiedot() {
        if ($scope.luokkatiedot) {
            angular.forEach($scope.luokkatiedot, function(luokkatieto) {
                if (luokkatieto.oppilaitosOid) {
                    getOrganisaatio($http, luokkatieto.oppilaitosOid, function(organisaatio) {
                        luokkatieto.oppilaitos = organisaatio.oppilaitosKoodi;
                    }, function() {});
                }
            });
        }
    }
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
                                confirm("Henkilötietojen hakeminen ei onnistunut. Yritä uudelleen?") ? fetchHenkilotiedot() : back();
                            });
                    }
                }
            })
            .error(function() {
                confirm("Henkilötietojen hakeminen ei onnistunut. Yritä uudelleen?") ? fetchHenkilotiedot() : back();
            });
    }
    function fetchLuokkatiedot() {
        Opiskelijat.query({henkilo: $scope.henkiloOid}, function(luokkatiedot) {
            $scope.luokkatiedot = luokkatiedot;
            enrichLuokkatiedot();
        }, function() {
            confirm("Luokkatietojen hakeminen ei onnistunut. Yritä uudelleen?") ? fetchLuokkatiedot() : back();
        });
    }
    function fetchSuoritukset() {
        Suoritukset.query({henkilo: $scope.henkiloOid}, function(suoritukset) {
            $scope.suoritukset = suoritukset;
            enrichSuoritukset();
        }, function() {
            confirm("Suoritustietojen hakeminen ei onnistunut. Yritä uudelleen?") ? fetchSuoritukset() : back();
        });
    }

    function fetchData() {
        fetchHenkilotiedot();
        fetchLuokkatiedot();
        fetchSuoritukset();
    }
    fetchData();

    $scope.getOppilaitos = function(searchStr) {
        if (searchStr && searchStr.trim().match(/^\d{5}$/))
            return $http.get(organisaatioServiceUrl + '/rest/organisaatio/' + searchStr)
                .then(function(result) {
                    return [result.data];
                }, function() {
                    return [];
                });
        else if (searchStr && searchStr.length > 3)
            return $http.get(organisaatioServiceUrl + '/rest/organisaatio/hae', {
                    params: {
                        searchstr: searchStr,
                        organisaatioTyyppi: "Oppilaitos"
                    }
                })
                .then(function(result) {
                    if (result.data && result.data.numHits > 0)
                        return result.data.organisaatiot;
                    else
                        return [];
                }, function() {
                    return [];
                });
        else
            return [];
    };

    $scope.save = function() {
        var deferredValidations = [];
        function validateOppilaitoskoodit() {
            angular.forEach($scope.luokkatiedot.concat($scope.suoritukset), function(obj) {
                var deferredValidation = $q.defer();
                deferredValidations.push(deferredValidation);
                if (!obj.oppilaitos || !obj.oppilaitos.match(/^\d{5}$/)) {
                    $scope.messages.push({
                        type: "danger",
                        message: "Oppilaitoskoodi puuttuu tai se on virheellinen.",
                        description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                    });
                    deferredValidation.reject("error");
                } else {
                    getOrganisaatio($http, obj.oppilaitos, function (organisaatio) {
                        if (obj.myontaja) {
                            obj.myontaja = organisaatio.oid;
                        } else {
                            obj.oppilaitosOid = organisaatio.oid;
                        }
                        deferredValidation.resolve("done");
                    }, function () {
                        $scope.messages.push({
                            type: "danger",
                            message: "Oppilaitosta ei löytynyt oppilaitoskoodilla: " + obj.oppilaitos + ".",
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
                        message: "Virhe tallennettaessa suoritustietoja.",
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
                    $log.debug("opiskelija saved: " + savedLuokkatieto);
                    deferredSave.resolve("done");
                }, function () {
                    $scope.messages.push({
                        type: "danger",
                        message: "Virhe tallennettaessa luokkatietoja.",
                        description: "Yritä uudelleen."
                    });
                    deferredSave.reject("error saving luokkatieto: " + luokkatieto);
                });
            });
        }

        var validationPromise = $q.all(deferredValidations.map(function(deferred) { return deferred.promise; }));
        validationPromise.then(function() {
            saveSuoritukset();
            saveLuokkatiedot();
        });

        var savePromise = $q.all(deferredSaves.map(function(deferred) { return deferred.promise; }));
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
    function back() {
        if (history && history.back) {
            history.back();
        } else {
            $location.path("/opiskelijat")
        }
    }
    $scope.removeMessage = function(message) {
        var index = $scope.messages.indexOf(message);
        if (index !== -1) {
            $scope.messages.splice(index, 1);
        }
    };

    function initDatepicker() {
        $scope.showWeeks = false;
        $scope.pickDate = function ($event, openedKey) {
            $event.preventDefault();
            $event.stopPropagation();
            $scope[openedKey] = true;
        };
        $scope.dateOptions = {
            'year-format': "'yyyy'",
            'starting-day': 1
        };
        $scope.format = 'dd.MM.yyyy';
    }
    initDatepicker();
}

