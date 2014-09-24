'use strict';

function MuokkaaCtrl($scope, $rootScope, $routeParams, $location, $http, $log, $q, $modal, Opiskelijat, Suoritukset, Opiskeluoikeudet, LokalisointiService) {
    $scope.henkiloOid = $routeParams.henkiloOid;
    $scope.myRoles = [];
    $scope.messages = [];
    $scope.suoritukset = [];
    $scope.luokkatiedot = [];
    $scope.kielet = [];
    $scope.luokkatasot = [
        {value: "9", text: "9"},
        {value: "10", text: "10"},
        {value: "A", text: "A"},
        {value: "M", text: "M"},
        {value: "V", text: "V"}
    ];
    $scope.komo = komo;
    function loadMenuTexts() {
        $scope.yksilollistamiset = [
            {value: "Ei", text: getOphMsg("suoritusrekisteri.yks.ei", "Ei")},
            {value: "Osittain", text: getOphMsg("suoritusrekisteri.yks.osittain", "Osittain")},
            {value: "Alueittain", text: getOphMsg("suoritusrekisteri.yks.alueittain", "Alueittain")},
            {value: "Kokonaan", text: getOphMsg("suoritusrekisteri.yks.kokonaan", "Kokonaan")}
        ];
        $scope.koulutukset = [
            {value: komo.ulkomainen, text: getOphMsg("suoritusrekisteri.komo." + komo.ulkomainen, "Ulkomainen")},
            {value: komo.peruskoulu, text: getOphMsg("suoritusrekisteri.komo." + komo.peruskoulu, "Peruskoulu")},
            {value: komo.lisaopetus, text: getOphMsg("suoritusrekisteri.komo." + komo.lisaopetus, "Perusopetuksen lisäopetus")},
            {value: komo.ammattistartti, text: getOphMsg("suoritusrekisteri.komo." + komo.ammattistartti, "Ammattistartti")},
            {value: komo.maahanmuuttaja, text: getOphMsg("suoritusrekisteri.komo." + komo.maahanmuuttaja, "Maahanmuuttaja")},
            {value: komo.valmentava, text: getOphMsg("suoritusrekisteri.komo." + komo.valmentava, "Valmentava")},
            {value: komo.ylioppilastutkinto, text: getOphMsg("suoritusrekisteri.komo." + komo.ylioppilastutkinto, "Ylioppilastutkinto")}
        ];
        $scope.tilat = [
            {value: "KESKEN", text: getOphMsg("suoritusrekisteri.tila.KESKEN", "Kesken")},
            {value: "KESKEYTYNYT", text: getOphMsg("suoritusrekisteri.tila.KESKEYTYNYT", "Keskeytynyt")},
            {value: "VALMIS", text: getOphMsg("suoritusrekisteri.tila.VALMIS", "Valmis")}
        ];
    }
    LokalisointiService.loadMessages(loadMenuTexts);

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
                        $scope.henkilo = henkilo
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
    function enrichLuokkatieto(luokkatieto) {
        if (luokkatieto.oppilaitosOid) {
            getOrganisaatio($http, luokkatieto.oppilaitosOid, function(organisaatio) {
                luokkatieto.oppilaitos = organisaatio.oppilaitosKoodi
            })
        }
        luokkatieto.editable = true;
    }
    function fetchLuokkatiedot() {
        function enrich() {
            if ($scope.luokkatiedot) {
                angular.forEach($scope.luokkatiedot, enrichLuokkatieto)
            }
        }

        Opiskelijat.query({henkilo: $scope.henkiloOid}, function(luokkatiedot) {
            $scope.luokkatiedot = luokkatiedot;
            enrich();
        }, function() {
            confirm(getOphMsg("suoritusrekisteri.muokkaa.luokkatietojenhakeminen", "Luokkatietojen hakeminen ei onnistunut. Yritä uudelleen?")) ? fetchLuokkatiedot() : back()
        });
    }
    function enrichSuoritus(suoritus) {
        if (suoritus.myontaja) {
            getOrganisaatio($http, suoritus.myontaja, function(organisaatio) {
                suoritus.oppilaitos = organisaatio.oppilaitosKoodi;
                suoritus.organisaatio = organisaatio;
            })
        }
        if (suoritus.komo && suoritus.komo.match(/^koulutus_\d*$/)) {
            getKoulutusNimi($http, suoritus.komo, function(koulutusNimi) {
                suoritus.koulutus = koulutusNimi
            })
        } else {
            suoritus.editable = true
        }
    }
    function fetchSuoritukset() {
        function enrich() {
            if ($scope.suoritukset) angular.forEach($scope.suoritukset, enrichSuoritus)
        }

        Suoritukset.query({henkilo: $scope.henkiloOid}, function(suoritukset) {
            suoritukset.sort(function(a, b) {
                return sortByFinDateDesc(a.valmistuminen, b.valmistuminen)
            });
            $scope.suoritukset = suoritukset;
            enrich();
        }, function() {
            confirm(getOphMsg("suoritusrekisteri.muokkaa.suoritustietojenhakeminen", "Suoritustietojen hakeminen ei onnistunut. Yritä uudelleen?")) ? fetchSuoritukset() : back();
        });
    }
    function fetchOpiskeluoikeudet() {
        function enrich() {
            if ($scope.opiskeluoikeudet) {
                angular.forEach($scope.opiskeluoikeudet, function(opiskeluoikeus) {
                    if (opiskeluoikeus.myontaja) {
                        getOrganisaatio($http, opiskeluoikeus.myontaja, function(organisaatio) {
                            opiskeluoikeus.oppilaitos = organisaatio.oppilaitosKoodi;
                            opiskeluoikeus.organisaatio = organisaatio;
                        })
                    }
                    if (opiskeluoikeus.komo && opiskeluoikeus.komo.match(/^koulutus_\d*$/)) {
                        getKoulutusNimi($http, opiskeluoikeus.komo, function(koulutusNimi) {
                            opiskeluoikeus.koulutus = koulutusNimi
                        })
                    }
                })
            }
        }

        Opiskeluoikeudet.query({henkilo: $scope.henkiloOid}, function(opiskeluoikeudet) {
            $scope.opiskeluoikeudet = opiskeluoikeudet;
            enrich();
        })
    }

    fetchHenkilotiedot();
    fetchLuokkatiedot();
    fetchSuoritukset();
    fetchOpiskeluoikeudet();

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
                if (!obj.delete && obj.editable && !(obj.komo && obj.komo === komo.ylioppilastutkinto)) {
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
                var d = $q.defer();
                this.push(d);
                if (suoritus.editable)
                    $log.debug("save suoritus: " + suoritus.id);
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
                            enrichSuoritus(suoritus);
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
                        enrichLuokkatieto(luokkatieto);
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
    $scope.checkYlioppilastutkinto = function(suoritus) {
        if (suoritus.komo === komo.ylioppilastutkinto) {
            suoritus.myontaja = ylioppilastutkintolautakunta;
            getOrganisaatio($http, ylioppilastutkintolautakunta, function(org) {
                suoritus.organisaatio = org;
            });
        }
    };
    $scope.addSuoritus = function() {
        $scope.suoritukset.push(new Suoritukset({
            henkiloOid: $scope.henkiloOid,
            tila: "KESKEN",
            yksilollistaminen: "Ei",
            myontaja: "na",
            editable: true }));
    };
    $scope.editArvosana = function(suoritusId) {
        function openModal(template, controller) {
            $rootScope.modalInstance = $modal.open({
                templateUrl: template,
                controller: controller,
                resolve: {
                    suoritusId: function() { return suoritusId }
                }
            });
        }
        openModal('templates/arvosanat', ArvosanaCtrl);

        $rootScope.modalInstance.result.then(function (arvosanaRet) {
            if (Array.isArray(arvosanaRet)) {
                $rootScope.modalInstance = $modal.open({
                    templateUrl: 'templates/duplikaatti',
                    controller: DuplikaattiCtrl,
                    resolve: {
                        arvosanat: function() { return arvosanaRet }
                    }
                });
                $rootScope.modalInstance.result.then(function(ret) {
                    if (ret) $scope.messages.push(ret)
                }, function() {
                    $log.info("duplicate modal closed")
                });
            } else if (arvosanaRet) $scope.messages.push(arvosanaRet)
        }, function () {
            $log.info("modal closed")
        });
    };
    $scope.editYoarvosana = function(suoritusId) {
        function openModal(template, controller) {
            $rootScope.modalInstance = $modal.open({
                templateUrl: template,
                controller: controller,
                resolve: {
                    suoritusId: function() { return suoritusId }
                }
            });
        }
        openModal('templates/yoarvosanat', YoarvosanaCtrl);

        $rootScope.modalInstance.result.then(function (yoarvosanaRet) {
            if (yoarvosanaRet) $scope.messages.push(yoarvosanaRet)
        }, function () {
            $log.info("yo modal closed")
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

