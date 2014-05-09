'use strict';

function OpiskelijatCtrl($scope, $rootScope, $routeParams, $location, $log, $http, Opiskelijat, Suoritukset) {
    $scope.loading = false;
    $scope.currentRows = [];
    $scope.allRows = [];
    $scope.pageNumbers = [];
    $scope.page = 0;
    $scope.pageSize = 10;
    $scope.targetOrg = "";
    $scope.myRoles = [];
    $scope.henkiloTerm = $routeParams.henkilo;
    $scope.organisaatioTerm = {
        oid: ($routeParams.oppilaitosOid ? $routeParams.oppilaitosOid : ''),
        oppilaitosKoodi: ($routeParams.oppilaitosKoodi ? $routeParams.oppilaitosKoodi : '')
    };
    $scope.messages = [];

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
                    searchstr: searchStr.trim(),
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

    $scope.search = function() {
        $location.path("/opiskelijat").search({
            henkilo: ($scope.henkiloTerm ? $scope.henkiloTerm : ''),
            oppilaitosOid: ($scope.organisaatioTerm ? $scope.organisaatioTerm.oid : ''),
            oppilaitosKoodi: ($scope.organisaatioTerm ? $scope.organisaatioTerm.oppilaitosKoodi : '')
        });
    };

    $scope.fetch = function() {
        $scope.currentRows = [];
        $scope.allRows = [];
        $scope.loading = true;
        $scope.henkilo = null;
        $scope.organisaatio = null;

        function filterUniqueHenkiloOids(oids, filterOid) {
            return oids
                .filter(function(o) {
                    return (!filterOid || (filterOid && o.henkiloOid === filterOid));
                })
                .map(function(o) {
                    return o.henkiloOid;
                })
                .getUnique()
                .map(function(o) {
                    return { henkiloOid: o };
                });
        }

        function doSearch(query) {
            if (query.oppilaitosOid) {
                Opiskelijat.query(query, function(opiskelijat) {
                    if (Array.isArray(opiskelijat)) {
                        showCurrentRows(filterUniqueHenkiloOids(opiskelijat, query.henkilo));
                    }
                    resetPageNumbers();
                    $scope.loading = false;
                }, function() {
                    $scope.loading = false;
                });
            } else if (query.henkilo) {
                showCurrentRows([ { henkiloOid: query.henkilo } ]);
                resetPageNumbers();
                $scope.loading = false;
            }
        }

        if ($scope.henkiloTerm) {
            var henkiloSearchUrl = null;
            if ($scope.henkiloTerm.trim().match(/^\d{6}[+-AB]\d{3}[0-9a-zA-Z]$/)) {
                henkiloSearchUrl = henkiloServiceUrl + '/resources/s2s/byHetu/' + encodeURIComponent($scope.henkiloTerm.trim().toUpperCase());
            } else if ($scope.henkiloTerm.trim().match(/^[0-9.]{14,30}$/)) {
                henkiloSearchUrl = henkiloServiceUrl + '/resources/s2s/' + encodeURIComponent($scope.henkiloTerm.trim());
            } else {
                $scope.messages.push({
                    type: "danger",
                    message: "Henkilön hakuehto ei ole hetu eikä oid.",
                    description: "Korjaa hakuehto."
                });
                $scope.loading = false;
                return;
            }
            if (henkiloSearchUrl) {
                $http.get(henkiloSearchUrl, {cache: true})
                    .success(function (henkilo) {
                        $scope.henkilo = henkilo;
                        doSearch({ henkilo: henkilo.oidHenkilo });
                    });
            }
        } else if ($scope.organisaatioTerm && $scope.organisaatioTerm.oid) {
            doSearch({ oppilaitosOid: $scope.organisaatioTerm.oid });
        } else {
            $scope.loading = false;
        }
    };

    function showCurrentRows(allRows) {
        $scope.allRows = allRows;
        $scope.currentRows = allRows.slice($scope.page * $scope.pageSize, ($scope.page + 1) * $scope.pageSize);
        enrichData();
    }

    function enrichData() {
        angular.forEach($scope.currentRows, function(row) {
            if (row.henkiloOid) {
                $http.get(henkiloServiceUrl + '/resources/henkilo/' + encodeURIComponent(row.henkiloOid), {cache: false})
                    .success(function(henkilo) {
                        if (henkilo) {
                            if (henkilo.duplicate === false) {
                                row.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + " (" + (henkilo.hetu ? henkilo.hetu : henkilo.syntymaaika) + ")";
                            } else {
                                $http.get(henkiloServiceUrl + '/resources/s2s/' + encodeURIComponent(row.henkiloOid), {cache: false})
                                    .success(function(masterHenkilo) {
                                        row.henkilo = masterHenkilo.sukunimi + ", " + masterHenkilo.etunimet + " (" + (masterHenkilo.hetu ? masterHenkilo.hetu : masterHenkilo.syntymaaika) + ")";
                                    });
                            }
                        }
                    });
                Opiskelijat.query({henkilo: row.henkiloOid}, function(opiskelijat) {
                    angular.forEach(opiskelijat, function(o) {
                        getOrganisaatio($http, o.oppilaitosOid, function(oppilaitos) {
                            o.oppilaitos = oppilaitos.oppilaitosKoodi + ' ' + (oppilaitos.nimi.fi ? oppilaitos.nimi.fi : oppilaitos.nimi.sv);
                        });
                    });
                    row.opiskelijatiedot = opiskelijat;
                });
                Suoritukset.query({henkilo: row.henkiloOid}, function(suoritukset) {
                    angular.forEach(suoritukset, function(o) {
                        getOrganisaatio($http, o.myontaja, function(oppilaitos) {
                            o.oppilaitos = oppilaitos.oppilaitosKoodi + ' ' + (oppilaitos.nimi.fi ? oppilaitos.nimi.fi : oppilaitos.nimi.sv);
                        });
                    });
                    row.suoritustiedot = suoritukset
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
    $scope.removeMessage = function(message) {
        var index = $scope.messages.indexOf(message);
        if (index !== -1) {
            $scope.messages.splice(index, 1);
        }
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
