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
        {value: "1.2.246.562.13.86722481404", text: getOphMsg("suoritusrekisteri.komo.ulkomainen", "Ulkomainen")},
        {value: "1.2.246.562.13.62959769647", text: getOphMsg("suoritusrekisteri.komo.peruskoulu", "Peruskoulu")},
        {value: "1.2.246.562.5.2013112814572435044876", text: getOphMsg("suoritusrekisteri.komo.lisaopetus", "Perusopetuksen lisäopetus")},
        {value: "1.2.246.562.5.2013112814572438136372", text: getOphMsg("suoritusrekisteri.komo.ammattistartti", "Ammattistartti")},
        {value: "1.2.246.562.5.2013112814572441001730", text: getOphMsg("suoritusrekisteri.komo.maahanmuuttaja", "Maahanmuuttaja")},
        {value: "1.2.246.562.5.2013112814572435755085", text: getOphMsg("suoritusrekisteri.komo.valmentava", "Valmentava")},
        {value: "1.2.246.562.5.2013061010184880799984", text: getOphMsg("suoritusrekisteri.komo.lukio", "Lukio")}
    ];
    $scope.luokkatasot = [
        {value: "9", text: "9"},
        {value: "10", text: "10"},
        {value: "A", text: "A"},
        {value: "M", text: "M"},
        {value: "V", text: "V"}
    ];
    $scope.myRoles = [];
    $scope.messages = [];
    $scope.suoritukset = [];
    $scope.luokkatiedot = [];
    $scope.kielet = [];

    getKoodistoAsOptionArray($http, 'kieli', 'fi', $scope.kielet, 'koodiArvo');

    $rootScope.addToMurupolku({href: "#/opiskelijat", key: "suoritusrekisteri.muokkaa.muru1", text: "Opiskelijoiden haku"}, true);
    $rootScope.addToMurupolku({key: "suoritusrekisteri.muokkaa.muru", text: "Muokkaa opiskelijan tietoja"}, false);

    function getMyRoles() {
        $http.get('/cas/myroles', {cache: true})
            .success(function(data) { $scope.myRoles = angular.fromJson(data) })
            .error(function() { $log.error("cannot connect to CAS") });
    }
    getMyRoles();
    $scope.isOPH = function() {
        return (Array.isArray($scope.myRoles)
            && ($scope.myRoles.indexOf("APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001") > -1
                || $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_READ_UPDATE_1.2.246.562.10.00000000001") > -1));
    };

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
        $scope.messages.length = 0;
        var validations = [];
        function validateOppilaitoskoodit() {
            angular.forEach($scope.luokkatiedot.concat($scope.suoritukset), function(obj) {
                if (!obj.delete) {
                    var d = $q.defer();
                    this.push(d);
                    if (!obj.oppilaitos || !obj.oppilaitos.match(/^\d{5}$/)) {
                        $scope.messages.push({
                            type: "danger",
                            messageKey: "suoritusrekisteri.muokkaa.oppilaitoskoodipuuttuu",
                            message: "Oppilaitoskoodi puuttuu tai se on virheellinen.",
                            descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi",
                            description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                        });
                        d.reject("validationerror");
                    } else {
                        getOrganisaatio($http, obj.oppilaitos, function (organisaatio) {
                            if (obj.myontaja) obj.myontaja = organisaatio.oid;
                            else obj.oppilaitosOid = organisaatio.oid;
                            d.resolve("validated against organisaatio");
                        }, function () {
                            $scope.messages.push({
                                type: "danger",
                                messageKey: "suoritusrekisteri.muokkaa.oppilaitostaeiloytynyt",
                                message: "Oppilaitosta ei löytynyt oppilaitoskoodilla.",
                                descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi",
                                description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                            });
                            d.reject("validationerror in call to organisaatio");
                        });
                    }
                }
            }, validations)
        }
        validateOppilaitoskoodit();

        function deleteFromArray(obj, arr) {
            var index = arr.indexOf(obj);
            if (index !== -1) arr.splice(index, 1);
        }

        var deferreds = [];
        function saveSuoritukset() {
            angular.forEach($scope.suoritukset, function(suoritus) {
                $log.debug("save suoritus: " + suoritus.id);
                var d = $q.defer();
                this.push(d);
                if (suoritus.delete) {
                    if (suoritus.id) {
                        suoritus.$remove(function() {
                            deleteFromArray(suoritus, $scope.suoritukset);
                            $log.debug("suoritus removed");
                            d.resolve("done");
                        }, function() {
                            $scope.messages.push({
                                type: "danger",
                                messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja",
                                message: "Virhe tallennettaessa suoritustietoja.",
                                descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita",
                                description: "Yritä uudelleen."
                            });
                            d.reject("error deleting suoritus: " + suoritus);
                        })
                    } else {
                        deleteFromArray(suoritus, $scope.suoritukset);
                        d.resolve("done");
                    }
                } else {
                    suoritus.$save(function () {
                        getOrganisaatio($http, suoritus.myontaja, function(organisaatio) {
                            suoritus.oppilaitos = organisaatio.oppilaitosKoodi;
                        });
                        d.resolve("done");
                    }, function () {
                        $scope.messages.push({
                            type: "danger",
                            messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja",
                            message: "Virhe tallennettaessa suoritustietoja.",
                            descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita",
                            description: "Yritä uudelleen."
                        });
                        d.reject("error saving suoritus: " + suoritus);
                    })
                }
            }, deferreds)
        }
        function saveLuokkatiedot() {
            angular.forEach($scope.luokkatiedot, function(luokkatieto) {
                $log.debug("save luokkatieto: " + luokkatieto.id);
                var d = $q.defer();
                this.push(d);
                if (luokkatieto.delete) {
                    if (luokkatieto.id) {
                        luokkatieto.$remove(function() {
                            deleteFromArray(luokkatieto, $scope.luokkatiedot);
                            $log.info("luokkatieto removed");
                            d.resolve("done");
                        }, function() {
                            $scope.messages.push({
                                type: "danger",
                                messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja",
                                message: "Virhe tallennettaessa luokkatietoja.",
                                descriptionKey: "suoritusrekisteri.muokkaa.virheluokkatietoyrita",
                                description: "Yritä uudelleen."
                            });
                            d.reject("error deleting luokkatieto: " + luokkatieto);
                        })
                    } else {
                        deleteFromArray(luokkatieto, $scope.luokkatiedot);
                        d.resolve("done");
                    }
                } else {
                    luokkatieto.$save(function () {
                        getOrganisaatio($http, luokkatieto.oppilaitosOid, function(organisaatio) {
                            luokkatieto.oppilaitos = organisaatio.oppilaitosKoodi;
                        });
                        d.resolve("done");
                    }, function () {
                        $scope.messages.push({
                            type: "danger",
                            messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja",
                            message: "Virhe tallennettaessa luokkatietoja.",
                            descriptionKey: "suoritusrekisteri.muokkaa.virheluokkayrita",
                            description: "Yritä uudelleen."
                        });
                        d.reject("error saving luokkatieto: " + luokkatieto);
                    })
                }
            }, deferreds)
        }

        var allValidated = $q.all(validations.map(function(deferred) { return deferred.promise }));
        allValidated.then(function() {
            saveSuoritukset();
            saveLuokkatiedot();

            var allSaved = $q.all(deferreds.map(function(deferred) { return deferred.promise }));
            allSaved.then(function() {
                $log.info("all saved successfully");
                $scope.messages.push({
                    type: "success",
                    messageKey: "suoritusrekisteri.muokkaa.tallennettu",
                    message: "Tiedot tallennettu."
                });
            }, function(errors) {
                $log.error("errors while saving: " + errors);
                $scope.messages.push({
                    type: "danger",
                    messageKey: "suoritusrekisteri.muokkaa.tallennusepaonnistui",
                    message: "Tietojen tallentaminen ei onnistunut. Yritä uudelleen."
                });
            });
        }, function(errors) {
            $log.error("validation errors: " + errors)
        });
    };
    $scope.cancel = function() {
        back()
    };
    $scope.addSuoritus = function() {
        $scope.suoritukset.push(new Suoritukset({ henkiloOid: $scope.henkiloOid, tila: "KESKEN", yksilollistaminen: "Ei", myontaja: "na" }));
    };
    $scope.editArvosana = function(suoritusId) {
        $rootScope.modalInstance = $modal.open({
            templateUrl: 'templates/arvosanat',
            controller: ArvosanaCtrl,
            resolve: {
                suoritusId: function() { return suoritusId }
            }
        });

        $rootScope.modalInstance.result.then(function (message) {
            if (message) $scope.messages.push(message)
        }, function () {
            $log.info("modal closed")
        });
    };
    $scope.addLuokkatieto = function() {
        $scope.luokkatiedot.push(new Opiskelijat({ henkiloOid: $scope.henkiloOid, oppilaitosOid: "na" }))
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

