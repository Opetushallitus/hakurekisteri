'use strict';

var msgCategory = "suoritusrekisteri";

var henkiloServiceUrl = "/authentication-service";
var organisaatioServiceUrl = "/organisaatio-service";
var koodistoServiceUrl = "/koodisto-service";

function getOrganisaatio($http, organisaatioOid, successCallback, errorCallback) {
    $http.get(organisaatioServiceUrl + '/rest/organisaatio/' + organisaatioOid, {cache: true})
        .success(successCallback)
        .error(errorCallback);
}

function getPostitoimipaikka($http, postinumero, successCallback, errorCallback) {
    $http.get(koodistoServiceUrl + '/rest/json/posti/koodi/posti_' + postinumero, {cache: true})
        .success(successCallback)
        .error(errorCallback);
}

function getKoodistoAsOptionArray($http, koodisto, kielikoodi, options) {
    options = [];
    $http.get(koodistoServiceUrl + '/rest/json/' + koodisto + '/koodi', {cache: true})
        .success(function(koodisto) {
            angular.forEach(koodisto, function(koodi) {
                metas: for (var j = 0; j < koodi.metadata.length; j++) {
                    var meta = koodi.metadata[j];
                    if (meta.kieli.toLowerCase() === kielikoodi.toLowerCase()) {
                        options.push({
                            value: koodi.koodiArvo,
                            text: meta.nimi
                        });
                        break metas;
                    }
                }
            });
            options.sort(function(a, b) {
                if (a.text === b.text) return 0;
                return a.text < b.text ? -1 : 1;
            });
        });
}




function OpiskelijatCtrl($scope, $routeParams, $log, $http, Opiskelijat) {
    $scope.loading = false;
    $scope.currentRows = [];
    $scope.allRows = [];
    $scope.sorting = { field: "", direction: "desc" };
    $scope.pageNumbers = [];
    $scope.page = 0;
    $scope.pageSize = 10;
    $scope.filter = {star: $routeParams.star ? $routeParams.star : ""};
    $scope.targetOrg = "";
    $scope.myRoles = [];

    function getMyRoles() {
        $http.get('/cas/myroles', {cache: true})
            .success(function(data) {
                $scope.myRoles = angular.fromJson(data);
            })
            .error(function() {
                if (location.hostname === 'localhost') {
                    $scope.myRoles = ["APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001"];
                }
                $log.error("cannot connect to CAS");
            });
    }
    getMyRoles();
    $scope.isOPH = function() {
        return (Array.isArray($scope.myRoles)
                && ($scope.myRoles.indexOf("APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001") > -1
                        || $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_READ_UPDATE_1.2.246.562.10.00000000001") > -1));
    };

    $scope.fetch = function() {
        $scope.currentRows = [];
        $scope.allRows = [];
        $scope.loading = true;
        $scope.hakuehto = "";

        if ($scope.searchTerm && $scope.searchTerm.match(/^\d{6}[+-AB]\d{3}[0-9a-zA-Z]$/)) {
            $http.get(henkiloServiceUrl + '/resources/henkilo/byHetu/' + $scope.searchTerm, {cache: true})
                .success(function(henkilo) {
                    $scope.hakuehto = henkilo.hetu + ' (' + henkilo.etunimet + ' ' + henkilo.sukunimi + ')';
                    search({henkiloOid: henkilo.oidHenkilo});
                })
                .error(function() {
                    $scope.hakuehto = $scope.searchTerm;
                    $scope.loading = false;
                });
        } else if ($scope.searchTerm && $scope.searchTerm.match(/^\d{5}$/)) {
            getOrganisaatio($http, $scope.searchTerm, function(organisaatio) {
                $scope.hakuehto = organisaatio.oppilaitosKoodi + ' (' + (organisaatio.nimi.fi ? organisaatio.nimi.fi : (organisaatio.nimi.sv ? organisaatio.nimi.sv : 'Oppilaitoksen nimeä ei löytynyt')) + ')';
                search({oppilaitosOid: organisaatio.oid});
            }, function() {
                $scope.hakuehto = $scope.searchTerm;
                $scope.loading = false;
            });
        } else {
            search({});
        }
        function search(query) {
            Opiskelijat.query(query, function(opiskelijat) {
                if (Array.isArray(opiskelijat)) {
                    showCurrentRows(opiskelijat);
                }
                resetPageNumbers();
                $scope.loading = false;
            }, function() {
                $scope.loading = false;
            });
        }
    };

    function showCurrentRows(allRows) {
        $scope.allRows = allRows;
        $scope.currentRows = allRows.slice($scope.page * $scope.pageSize, ($scope.page + 1) * $scope.pageSize);
        enrichData();
    }

    function enrichData() {
        angular.forEach($scope.currentRows, function(opiskelija) {
            if (opiskelija.oppilaitosOid) {
                getOrganisaatio($http, opiskelija.oppilaitosOid, function(data) {
                    if (data && data.oid === opiskelija.oppilaitosOid)
                        opiskelija.oppilaitos = data.oppilaitosKoodi + ' ' + data.nimi.fi;
                }, function() {});
            }
            if (opiskelija.henkiloOid) {
                $http.get(henkiloServiceUrl + '/resources/henkilo/' + opiskelija.henkiloOid, {cache: true})
                    .success(function(henkilo) {
                        if (henkilo && henkilo.oidHenkilo === opiskelija.henkiloOid && henkilo.sukunimi && henkilo.etunimet) {
                            opiskelija.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + (henkilo.hetu ? " (" + henkilo.hetu + ")" : "");
                        }
                    });
            }
        });
    }

    $scope.nextPage = function() {
        if (($scope.page + 1) * $scope.pageSize < $scope.allRows.length) {
            $scope.page++;
        } else {
            $scope.page = 0;
        }
        showCurrentRows($scope.allRows);
    };
    $scope.prevPage = function() {
        if ($scope.page > 0 && ($scope.page - 1) * $scope.pageSize < $scope.allRows.length) {
            $scope.page--;
        } else {
            $scope.page = Math.floor($scope.allRows.length / $scope.pageSize);
        }
        showCurrentRows($scope.allRows);
    };
    $scope.showPageWithNumber = function(pageNum) {
        $scope.page = pageNum > 0 ? (pageNum - 1) : 0;
        showCurrentRows($scope.allRows);
    };
    $scope.setPageSize = function(newSize) {
        $scope.pageSize = newSize;
        $scope.page = 0;
        resetPageNumbers();
        showCurrentRows($scope.allRows);
    };
    $scope.sort = function(field, direction) {
        $scope.sorting.field = field;
        $scope.sorting.direction = direction.match(/asc|desc/) ? direction : 'asc';
        $scope.page = 0;
        showCurrentRows($scope.allRows);
    };
    $scope.isDirectionIconVisible = function(field) {
        return $scope.sorting.field === field;
    };

    function resetPageNumbers() {
        $scope.pageNumbers = [];
        for (var i = 0; i < Math.ceil($scope.allRows.length / $scope.pageSize); i++) {
            $scope.pageNumbers.push(i + 1);
        }
    }

    $scope.fetch();
}




function MuokkaaCtrl($scope, $routeParams, $location, $http, $log, Henkilo, Opiskelijat, Suoritukset) {
    $scope.henkiloOid = $routeParams.henkiloOid;
    $scope.messages = [];
    $scope.yksilollistamiset = ["Ei", "Osittain", "Kokonaan", "Alueittain"];
    getKoodistoAsOptionArray($http, 'maatjavaltiot2', 'FI', $scope.maat);
    getKoodistoAsOptionArray($http, 'kunta', 'FI', $scope.kunnat);
    getKoodistoAsOptionArray($http, 'kieli', 'FI', $scope.kielet);
    getKoodistoAsOptionArray($http, 'maatjavaltiot2', 'FI', $scope.kansalaisuudet);


    function enrichSuoritukset() {
        if ($scope.suoritukset) {
            angular.forEach($scope.suoritukset, function(suoritus) {
                if (suoritus.komoto && suoritus.komoto.tarjoaja) {
                    getOrganisaatio($http, suoritus.komoto.tarjoaja, function(organisaatio) {
                        if (organisaatio.oid === suoritus.komoto.tarjoaja) {
                            suoritus.oppilaitos = organisaatio.oppilaitosKoodi + ' ' + organisaatio.nimi.fi ? organisaatio.nimi.fi : organisaatio.nimi.sv;
                        }
                    }, function() {});
                }
            });
        }
    }
    function enrichLuokkatiedot() {
        if ($scope.luokkatiedot) {
            angular.forEach($scope.luokkatiedot, function(luokkatieto) {
                var luokkatieto = $scope.luokkatiedot[i];
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
        Henkilo.get({henkiloOid: $scope.henkiloOid}, function(henkilo) {
            $scope.henkilo = henkilo;
        }, function() {
            confirm("Henkilötietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchHenkilotiedot() : $location.path("/opiskelijat");
        });
    }
    function fetchLuokkatiedot() {
        Opiskelijat.query({henkiloOid: $scope.henkiloOid}, function(luokkatiedot) {
            $scope.luokkatiedot = luokkatiedot;
            enrichLuokkatiedot();
        }, function() {
            confirm("Luokkatietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchLuokkatiedot() : $location.path("/opiskelijat");
        });
    }
    function fetchSuoritukset() {
        Suoritukset.query({henkiloOid: $scope.henkiloOid}, function(suoritukset) {
            $scope.suoritukset = suoritukset;
            enrichSuoritukset();
        }, function() {
            confirm("Suoritustietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchSuoritukset() : $location.path("/opiskelijat");
        });
    }

    function fetchData() {
        fetchHenkilotiedot();
        fetchLuokkatiedot();
        fetchSuoritukset();
    }
    fetchData();

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
    $scope.save = function() {
        function validateOppilaitoskoodit() {
            angular.forEach($scope.luokkatiedot, function(luokkatieto) {
                if (!luokkatieto.oppilaitos || !luokkatieto.oppilaitos.match(/^\d{5}$/)) {
                    $scope.messages.push({
                        type: "danger",
                        message: "Oppilaitoskoodi puuttuu tai se on virheellinen.",
                        description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                    });
                } else {
                    getOrganisaatio($http, luokkatieto.oppilaitos, function (organisaatio) {
                        luokkatieto.oppilaitosOid = organisaatio.oid;
                    }, function () {
                        $scope.messages.push({
                            type: "danger",
                            message: "Oppilaitosta ei löytynyt oppilaitoskoodilla: " + luokkatieto.oppilaitos + ".",
                            description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                        });
                    });
                }
            });
        }
        validateOppilaitoskoodit();
        if ($scope.messages.length > 0) {
            return;
        }

        function saveHenkilo() {
            if ($scope.henkilo) {
                $scope.henkilo.$save(function (savedHenkilo) {
                    $log.debug("henkilo saved: " + savedHenkilo);
                }, function () {
                    $scope.messages.push({
                        type: "danger",
                        message: "Virhe tallennettaessa henkilötietoja.",
                        description: "Yritä uudelleen."
                    });
                });
            }
        }
        saveHenkilo();

        function saveSuoritukset() {
            angular.forEach($scope.suoritukset, function(suoritus) {
                suoritus.$save(function (savedSuoritus) {
                    $log.debug("suoritus saved: " + savedSuoritus);
                }, function () {
                    $scope.messages.push({
                        type: "danger",
                        message: "Virhe tallennettaessa suoritustietoja.",
                        description: "Yritä uudelleen."
                    });
                });
            });
        }
        saveSuoritukset();

        function saveLuokkatiedot() {
            angular.forEach($scope.luokkatiedot, function(luokkatieto) {
                luokkatieto.$save(function (savedLuokkatieto) {
                    $log.debug("opiskelija saved: " + savedLuokkatieto);
                }, function () {
                    $scope.messages.push({
                        type: "danger",
                        message: "Virhe tallennettaessa luokkatietoja.",
                        description: "Yritä uudelleen."
                    });
                });
            });
        }
        saveLuokkatiedot();
    };
    $scope.cancel = function() {
        $location.path("/opiskelijat")
    };
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

