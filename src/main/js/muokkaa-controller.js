'use strict';

function MuokkaaCtrl($scope, $routeParams, $location, $http, $log, $q, Henkilo, Opiskelijat, Suoritukset) {
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
        {value: "keskeytynyt", text: "Keskeytynyt (7)"},
        {value: "lukio", text: "Lukio (9)"}
    ];
    $scope.messages = [];
    /*
    $scope.maat = [];
    $scope.kunnat = [];
    $scope.kielet = [];
    $scope.kansalaisuudet = [];
    getKoodistoAsOptionArray($http, 'maatjavaltiot2', 'FI', $scope.maat);
    getKoodistoAsOptionArray($http, 'kunta', 'FI', $scope.kunnat);
    getKoodistoAsOptionArray($http, 'kieli', 'FI', $scope.kielet);
    getKoodistoAsOptionArray($http, 'maatjavaltiot2', 'FI', $scope.kansalaisuudet);
    */

    function enrichSuoritukset() {
        if ($scope.suoritukset) {
            angular.forEach($scope.suoritukset, function(suoritus) {
                if (suoritus.komoto && suoritus.komoto.tarjoaja) {
                    getOrganisaatio($http, suoritus.komoto.tarjoaja, function(organisaatio) {
                        if (organisaatio.oid === suoritus.komoto.tarjoaja) {
                            suoritus.oppilaitos = organisaatio.oppilaitosKoodi;
                        }
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
                        if (organisaatio.oid === luokkatieto.oppilaitosOid) {
                            luokkatieto.oppilaitos = organisaatio.oppilaitosKoodi;
                        }
                    }, function() {});
                }
            });
        }
    }
    function fetchHenkilotiedot() {
        Henkilo.get({oidHenkilo: $scope.henkiloOid}, function(henkilo) {
            $scope.henkilo = henkilo;
        }, function() {
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

    $scope.getOppilaitos = function(oppilaitosKoodi) {
        if (oppilaitosKoodi && oppilaitosKoodi.trim().match(/^\d{5}$/))
            return $http.get(organisaatioServiceUrl + '/rest/organisaatio/' + oppilaitosKoodi)
                .then(function(organisaatio) {
                    return [organisaatio];
                });
        else
            return [];
    };

    $scope.fetchPostitoimipaikka = function() {
        if ($scope.henkilo.postinumero && $scope.henkilo.postinumero.match(/^\d{5}$/)) {
            $scope.searchingPostinumero = true;
            getPostitoimipaikka($http, $scope.henkilo.postinumero, function(koodi) {
                for (var i = 0; i < koodi.metadata.length; i++) {
                    var meta = koodi.metadata[i];
                    if (meta.kieli === 'FI') {
                        $scope.henkilo.postitoimipaikka = meta.nimi;
                        break;
                    }
                }
                $scope.searchingPostinumero = false;
            }, function() {
                $scope.henkilo.postitoimipaikka = "Postitoimipaikkaa ei löytynyt";
                $scope.searchingPostinumero = false;
            });
        }
    };
    $scope.koulutusChange = function(suoritus) {
        if (suoritus && suoritus.komoto && suoritus.komoto.komo === 'peruskoulu') {

        }
    };
    $scope.save = function() {
        var deferredValidations = [];
        function validateOppilaitoskoodit() {
            angular.forEach($scope.luokkatiedot.concat($scope.suoritukset), function(oppilaitosKoodiHolder) {
                var deferredValidation = $q.defer();
                deferredValidations.push(deferredValidation);
                if (!oppilaitosKoodiHolder.oppilaitos || !oppilaitosKoodiHolder.oppilaitos.match(/^\d{5}$/)) {
                    $scope.messages.push({
                        type: "danger",
                        message: "Oppilaitoskoodi puuttuu tai se on virheellinen.",
                        description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                    });
                    deferredValidation.reject("error");
                } else {
                    getOrganisaatio($http, oppilaitosKoodiHolder.oppilaitos, function (organisaatio) {
                        if (oppilaitosKoodiHolder.komoto && oppilaitosKoodiHolder.komoto.tarjoaja) {
                            oppilaitosKoodiHolder.komoto.tarjoaja = organisaatio.oid;
                        } else {
                            oppilaitosKoodiHolder.oppilaitosOid = organisaatio.oid;
                        }
                        deferredValidation.resolve("done");
                    }, function () {
                        $scope.messages.push({
                            type: "danger",
                            message: "Oppilaitosta ei löytynyt oppilaitoskoodilla: " + oppilaitosKoodiHolder.oppilaitos + ".",
                            description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                        });
                        deferredValidation.reject("error");
                    });
                }
            });
        }
        validateOppilaitoskoodit();

        var deferredSaves = [];
        function saveHenkilo() {
            if ($scope.henkilo) {
                var deferredSave = $q.defer();
                deferredSaves.push(deferredSave);
                $scope.henkilo.$save(function (savedHenkilo) {
                    $log.debug("henkilo saved: " + savedHenkilo);
                    deferredSave.resolve("done");
                }, function () {
                    $scope.messages.push({
                        type: "danger",
                        message: "Virhe tallennettaessa henkilötietoja.",
                        description: "Yritä uudelleen."
                    });
                    deferredSave.reject("error saving henkilo: " + $scope.henkilo);
                });
            }
        }
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
            saveHenkilo();
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

