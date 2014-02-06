'use strict';

var msgCategory = "suoritusrekisteri";

function SuorituksetCtrl($scope, $routeParams, $log, Henkilo, Organisaatio, MyRoles, Opiskelijat) {
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

    // roles
    MyRoles.getCached({}, function(roles) {
        $scope.myRoles = roles;
    }, function() {
        if (location.hostname === 'localhost') {
            $scope.myRoles = ["APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001"];
        }
        $log.error("cannot connect to CAS");
    });
    $scope.isOPH = function() {
        return ($scope.myRoles
                && ($scope.myRoles.indexOf("APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001") !== -1
                        || $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_READ_UPDATE_1.2.246.562.10.00000000001") !== -1));
    };

    $scope.fetch = function() {
        $scope.currentRows = [];
        $scope.loading = true;
        if (location.hostname === 'localhost') {
            showCurrentProcesses([
                {henkiloOid: "1.2.3", luokka: "9A", luokkataso: "9", oppilaitosOid: "1.2.4"}
            ]);
            resetPageNumbers();
            $scope.loading = false;
        } else {
            Opiskelijat.get({}, function(opiskelijat) {
                if (Array.isArray(opiskelijat)) {
                    showCurrentProcesses(opiskelijat);
                }
                resetPageNumbers();
                $scope.loading = false;
            }, function() {
                $scope.loading = false;
            });
        }
    };

    function showCurrentProcesses(allRows) {
        $scope.allRows = allRows;
        $scope.currentRows = allRows.slice($scope.page * $scope.pageSize, ($scope.page + 1) * $scope.pageSize);
        enrichData();
    }

    function enrichData() {
        for (var i = 0; i < $scope.currentRows.length; i++) {
            var opiskeluoikeus = $scope.currentRows[i];
            if (opiskeluoikeus.oppilaitosOid) {
                Organisaatio.getCached({organisaatioOid: opiskeluoikeus.oppilaitosOid}, function(data) {
                    if (data && data.oid === opiskeluoikeus.oppilaitosOid)
                        opiskeluoikeus.oppilaitoskoodi = data.oppilaitosKoodi + ' ' + data.nimi.fi;
                });
            }
            if (opiskeluoikeus.henkiloOid) {
                Henkilo.getCached({henkiloOid: opiskeluoikeus.henkiloOid}, function(henkilo) {
                    if (henkilo && henkilo.oidHenkilo === opiskeluoikeus.henkiloOid && henkilo.sukunimi && henkilo.etunimet) {
                        opiskeluoikeus.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + (henkilo.hetu ? " (" + henkilo.hetu + ")" : "");
                    }
                });
            }
        }
    }

    $scope.nextPage = function() {
        if (($scope.page + 1) * $scope.pageSize < $scope.allRows.length) {
            $scope.page++;
        } else {
            $scope.page = 0;
        }
        showCurrentProcesses($scope.allRows);
    };
    $scope.prevPage = function() {
        if ($scope.page > 0 && ($scope.page - 1) * $scope.pageSize < $scope.allRows.length) {
            $scope.page--;
        } else {
            $scope.page = Math.floor($scope.allRows.length / $scope.pageSize);
        }
        showCurrentProcesses($scope.allRows);
    };
    $scope.showPageWithNumber = function(pageNum) {
        $scope.page = pageNum > 0 ? (pageNum - 1) : 0;
        showCurrentProcesses($scope.allRows);
    };
    $scope.setPageSize = function(newSize) {
        $scope.pageSize = newSize;
        $scope.page = 0;
        resetPageNumbers();
        showCurrentProcesses($scope.allRows);
    };
    $scope.sort = function(field, direction) {
        $scope.sorting.field = field;
        $scope.sorting.direction = direction.match(/asc|desc/) ? direction : 'asc';
        $scope.page = 0;
        showCurrentProcesses($scope.allRows);
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

function getKoodi(koodiArray, koodiArvo) {
    for (var i = 0; i < koodiArray.length; i++) {
        var koodi = koodiArray[i];
        if (koodi.koodiArvo == koodiArvo) {
            for (var m = 0; m < koodi.metadata.length; m++) {
                var metadata = koodi.metadata[m];
                if (metadata.kieli == "FI") {
                    return metadata.nimi;
                }
            }
        }
    }
    return koodiArvo;
}


function MuokkaaCtrl($scope, $routeParams, $location, Henkilo, Organisaatio, Koodisto, Opiskelijat, Suoritukset) {
    $scope.errors = [];
    $scope.henkiloOid = $routeParams.henkiloOid;

    function fetchHenkilotiedot() {
        Henkilo.get({henkiloOid: $scope.henkiloOid}, function(henkilo) {
            $scope.henkilo = henkilo;
        }, function() {
            confirm("Henkilötietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchHenkilotiedot() : $location.path("/suoritukset");
        });
    }
    fetchHenkilotiedot();

    function fetchOpiskelijatiedot() {
        Opiskelijat.get({henkiloOid: $scope.henkiloOid}, function(luokkatiedot) {
            $scope.luokkatiedot = luokkatiedot;
        }, function() {
            confirm("Luokkatietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchOpiskelijatiedot() : $location.path("/suoritukset");
        });
    }
    fetchOpiskelijatiedot();

    function fetchSuoritukset() {
        Suoritukset.get({henkiloOid: $scope.henkiloOid}, function(suoritukset) {
            $scope.suoritukset = suoritukset;
            enrichSuoritukset();
        }, function() {
            confirm("Suoritustietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchSuoritukset() : $location.path("/suoritukset");
        });
    }
    fetchSuoritukset();

    function enrichSuoritukset() {
        if ($scope.suoritukset) {
            for (var i = 0; i < $scope.suoritukset.length; i++) {
                var suoritus = $scope.suoritukset[i];
                if (suoritus.komoto && suoritus.komoto.tarjoaja) {
                    Organisaatio.getCached({organisaatioOid: suoritus.komoto.tarjoaja}, function(organisaatio) {
                        if (organisaatio.oid === suoritus.komoto.tarjoaja) {
                            suoritus.oppilaitos = organisaatio.nimi.fi ? organisaatio.nimi.fi : organisaatio.nimi.sv;
                        }
                    });
                }
            }
        }
    }

    $scope.yksilollistamiset = ["Ei", "Osittain", "Kokonaan", "Alueittain"];

    // TODO hae koodistosta
    $scope.maat = [
        { value: "246", text: "Suomi" }
    ];
    $scope.kunnat = [
        { value: "091", text: "Helsinki" }
    ];
    $scope.kielet = [
        { value: "FI", text: "Suomi" }
    ];
    $scope.kansalaisuudet = [
        { value: "246", text: "Suomi" }
    ];
    $scope.fetchPostitoimipaikka = function() {
        if ($scope.henkilo.postinumero && $scope.henkilo.postinumero.match(/^\d{5}$/)) {
            $scope.searchingPostinumero = true;
            Koodisto.getCached({koodisto: "posti", koodiUri: "posti_" + $scope.henkilo.postinumero}, function(koodi) {
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

    // tallennus
    $scope.save = function() {
        $scope.errors.push({
            message: "Tallennusta ei vielä toteutettu.",
            description: ""
        });
    };
    $scope.cancel = function() {
        $location.path("/suoritukset")
    };
    $scope.removeError = function(error) {
        var index = $scope.errors.indexOf(error);
        if (index !== -1) {
            $scope.errors.splice(index, 1);
        }
    };

    // datepicker
    $scope.showWeeks = false;
    $scope.pickDate = function($event, openedKey) {
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
