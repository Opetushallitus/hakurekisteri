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
    MyRoles.get({cacheKey: getCacheEnvKey()}, function(roles) {
        $scope.myRoles = roles;
    }, function() {
        $log.error("cannot connect to CAS");
    });
    $scope.isOPH = function() {
        if ($scope.myRoles
                && ($scope.myRoles.indexOf("APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001") !== -1
                        || $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_READ_UPDATE_1.2.246.562.10.00000000001") !== -1)) {
            return true;
        }
        return false;
    };

    function fetch() {
        $scope.currentRows = [];
        $scope.loading = true;
        Opiskelijat.get({henkiloOid: ""}, function(opiskelijat) {
            if (opiskelijat && Object.prototype.toString.call(opiskelijat) === "[object Array]") {
                showCurrentProcesses(opiskelijat);
            }
            resetPageNumbers();
            $scope.loading = false;
        }, function() {
            showCurrentProcesses([
                {henkiloOid: "1.2.3", luokka: "9A", luokkataso: "9"}
            ]);
            resetPageNumbers();
            $scope.loading = false;
        });
    }

    function showCurrentProcesses(allRows) {
        $scope.allRows = allRows;
        $scope.currentProcesses = allRows.slice($scope.page * $scope.pageSize, ($scope.page + 1) * $scope.pageSize);
        enrichData();
    }

    function enrichData() {
        angular.forEach($scope.currentRows, function(opiskeluoikeus) {
            if (opiskeluoikeus.oppilaitosOid) {
                Organisaatio.get({organisaatioOid: opiskeluoikeus.oppilaitosOid, cacheKey: getCacheEnvKey()}, function(data) {
                    if (data && data.oid === opiskeluoikeus.oppilaitosOid)
                        opiskeluoikeus.oppilaitoskoodi = data.oppilaitosKoodi + ' ' + data.nimi.fi;
                });
            }
            if (opiskeluoikeus.henkiloOid) {
                Henkilo.get({henkiloOid: opiskeluoikeus.henkiloOid, cacheKey: getCacheEnvKey()}, function(henkilo) {
                    if (henkilo && henkilo.oidHenkilo === opiskeluoikeus.henkiloOid && henkilo.sukunimi && henkilo.etunimet) {
                        opiskeluoikeus.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + (henkilo.hetu ? " (" + henkilo.hetu + ")" : "");
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

    fetch();
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

function getCacheEnvKey() {
    return encodeURIComponent(location.hostname);
}

function MuokkaaCtrl($scope, $routeParams, $location, Henkilo, Organisaatio, Opiskelijat) {
    $scope.errors = [];
    $scope.henkiloOid = $routeParams.henkiloOid;
    Henkilo.get({henkiloOid: $scope.henkiloOid}, function(henkilo) {
        $scope.henkilo = {
            hetu: henkilo.hetu,
            sukupuoli: henkilo.sukupuoli === 'MIES' ? 1 : henkilo.sukupuoli === 'NAINEN' ? 2 : null,
            etunimet: henkilo.etunimet,
            kutsumanimi: henkilo.kutsumanimi,
            sukunimi: henkilo.sukunimi,
            katuosoite: "",
            postinumero: "",
            postitoimipaikka: "",
            maa: "",
            kotikunta: henkilo.kotikunta,
            aidinkieli: "",
            kansalaisuus: "",
            matkapuhelin: "",
            muupuhelin: ""
        };
    }, function(data, status) {
        $scope.errors.push({
            message: "Virhe haettaessa henkilötietoja",
            description: "Status: " + status
        });
    });

    Opiskelijat.get({henkiloOid: $scope.henkiloOid}, function(luokkatiedot) {
        $scope.luokkatiedot = luokkatiedot;
    }, function(data, status) {
        $scope.errors.push({
            message: "Virhe haettaessa luokkatietoja",
            description: "Status: " + status
        });
    });

    $scope.suoritukset = [
        {
            koulutus: "Perusopetus",
            koulutusohjelma: "",
            oppilaitos: "Kannelmäen peruskoulu",
            tila: "KESKEN",
            opetuskieli: "FI",
            suoritukset: []
        }
    ];

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

    // tallennus
    $scope.save = function() {
        $scope.errors.push({
            message: "Not yet wired to the backend",
            description: ""
        });
    };
    $scope.cancel = function() {
        $location.path("/suoritukset")
    };
    $scope.clearError = function() {
        delete $scope.error;
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
