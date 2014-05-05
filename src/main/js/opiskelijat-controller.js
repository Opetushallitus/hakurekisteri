'use strict';

function OpiskelijatCtrl($scope, $rootScope, $routeParams, $location, $log, $http, Opiskelijat) {
    $scope.loading = false;
    $scope.currentRows = [];
    $scope.allRows = [];
    $scope.sorting = { field: "", direction: "desc" };
    $scope.pageNumbers = [];
    $scope.page = 0;
    $scope.pageSize = 10;
    $scope.targetOrg = "";
    $scope.myRoles = [];
    $scope.searchTerm = $routeParams.q;

    $rootScope.addToMurupolku({text: "Opiskelijoiden haku"}, true);

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

    $scope.search = function() {
        $location.path("/opiskelijat").search({q: $scope.searchTerm});
    };

    $scope.fetch = function() {
        $scope.currentRows = [];
        $scope.allRows = [];
        $scope.loading = true;
        $scope.hakuehto = "";

        if ($scope.searchTerm && $scope.searchTerm.match(/^\d{6}[+-AB]\d{3}[0-9a-zA-Z]$/)) {
            $http.get(henkiloServiceUrl + '/resources/henkilo/byHetu/' + encodeURIComponent($scope.searchTerm), {cache: true})
                .success(function(henkilo) {
                    $scope.hakuehto = henkilo.hetu + ' (' + henkilo.etunimet + ' ' + henkilo.sukunimi + ')';
                    search({henkilo: henkilo.oidHenkilo});
                })
                .error(function() {
                    $scope.hakuehto = $scope.searchTerm;
                    $scope.loading = false;
                });
        } else if ($scope.searchTerm && $scope.searchTerm.match(/^\d{5}$/)) {
            getOrganisaatio($http, $scope.searchTerm, function(organisaatio) {
                $scope.hakuehto = organisaatio.oppilaitosKoodi + ' (' + (organisaatio.nimi.fi ? organisaatio.nimi.fi : organisaatio.nimi.sv) + ')';
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
                getOrganisaatio($http, opiskelija.oppilaitosOid, function(organisaatio) {
                    if (organisaatio)
                        opiskelija.oppilaitos = organisaatio.oppilaitosKoodi + ' ' + (organisaatio.nimi.fi ? organisaatio.nimi.fi : organisaatio.nimi.sv);
                }, function() {});
            }
            if (opiskelija.henkiloOid) {
                $http.get(henkiloServiceUrl + '/resources/henkilo/' + encodeURIComponent(opiskelija.henkiloOid), {cache: false})
                    .success(function(henkilo) {
                        if (henkilo) {
                            if (henkilo.duplicate === false) {
                                opiskelija.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + (henkilo.hetu ? " (" + henkilo.hetu + ")" : "");
                            } else {
                                $http.get(henkiloServiceUrl + '/resources/s2s/' + encodeURIComponent(opiskelija.henkiloOid), {cache: false})
                                    .success(function(masterHenkilo) {
                                        opiskelija.henkilo = masterHenkilo.sukunimi + ", " + masterHenkilo.etunimet + (masterHenkilo.hetu ? " (" + masterHenkilo.hetu + ")" : "");
                                    });
                            }
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
        resetPageNumbers();
        showCurrentRows($scope.allRows);
    };
    $scope.prevPage = function() {
        if ($scope.page > 0 && ($scope.page - 1) * $scope.pageSize < $scope.allRows.length) {
            $scope.page--;
        } else {
            $scope.page = Math.floor($scope.allRows.length / $scope.pageSize);
        }
        resetPageNumbers();
        showCurrentRows($scope.allRows);
    };
    $scope.showPageWithNumber = function(pageNum) {
        $scope.page = pageNum > 0 ? (pageNum - 1) : 0;
        resetPageNumbers();
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
            if (i === 0 || (i >= ($scope.page - 3) && i <= ($scope.page + 3)) || i === (Math.ceil($scope.allRows.length / $scope.pageSize) - 1))
                $scope.pageNumbers.push(i + 1);
        }
    }

    authenticateToAuthenticationService($http, $scope.fetch, function() {});
}
