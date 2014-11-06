'use strict';

function OpiskelijatCtrl($scope, $rootScope, $routeParams, $location, $log, $http, $q, Opiskelijat, Suoritukset, Arvosanat) {
    $scope.messages = [];
    $scope.loading = false;
    $scope.currentRows = [];
    $scope.allRows = [];
    $scope.pageNumbers = [];
    $scope.page = 0;
    $scope.pageSize = 10;
    $scope.targetOrg = "";
    $scope.myRoles = [];
    $scope.henkiloTerm = $routeParams.henkilo;
    $scope.organisaatioTerm = { oppilaitosKoodi: ($routeParams.oppilaitos ? $routeParams.oppilaitos : '') };

    $rootScope.addToMurupolku({key: "suoritusrekisteri.opiskelijat.muru", text: "Opiskelijoiden haku"}, true);

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

    $scope.getOppilaitos = function(searchStr) {
        if (searchStr && searchStr.length >= 3)
            return $http.get(organisaatioServiceUrl + '/rest/organisaatio/hae',
                { params: { searchstr: searchStr.trim(), organisaatioTyyppi: "Oppilaitos" } })
                .then(function(result) {
                    if (result.data && result.data.numHits > 0) return result.data.organisaatiot;
                    else return [];
                }, function() { return [] });
        else return [];
    };

    $scope.reset = function() {
        $location.path("/opiskelijat").search({});
    };
    $scope.search = function() {
        $location.path("/opiskelijat").search({
            henkilo: ($scope.henkiloTerm ? $scope.henkiloTerm : ''),
            oppilaitos: ($scope.organisaatioTerm ? $scope.organisaatioTerm.oppilaitosKoodi : '')
        });
    };

    $scope.fetch = function() {
        function startLoading() { $scope.loading = true; }
        function stopLoading() { $scope.loading = false; }
        $scope.currentRows = [];
        $scope.allRows = [];
        $scope.henkilo = null;
        $scope.organisaatio = null;

        startLoading();

        function getUniqueHenkiloOids(oids) {
            return oids.map(function(o) {
                    return o.henkiloOid;
                }).getUnique().map(function(o) {
                    return { henkiloOid: o };
                });
        }

        function doSearch(query) {
            $scope.messages.length = 0;
            function searchOpiskelijat(o) {
                Opiskelijat.query(query, function (result) {
                    o.resolve(result);
                }, function () {
                    o.reject("opiskelija query failed");
                });
            }

            function searchSuoritukset(s) {
                var suoritusQuery = { myontaja: query.oppilaitosOid, henkilo: (query.henkilo ? query.henkilo : null) };
                Suoritukset.query(suoritusQuery, function (result) {
                    s.resolve(result);
                }, function () {
                    s.reject("suoritus query failed");
                });
            }

            if (query.oppilaitosOid) {
                var o = $q.defer();
                searchOpiskelijat(o);
                var s = $q.defer();
                searchSuoritukset(s);
                $q.all([ o.promise, s.promise ]).then(function(resultArrays) {
                    showCurrentRows(getUniqueHenkiloOids(resultArrays.reduce(function(a, b) { return a.concat(b) })));
                    resetPageNumbers();
                    stopLoading();
                }, function(errors) {
                    $log.error(errors);
                    $scope.messages.push({
                        type: "danger",
                        messageKey: "suoritusrekisteri.opiskelijat.virhehaussa",
                        message: "Haussa tapahtui virhe. Yritä uudelleen."
                    });
                    stopLoading();
                });
            } else if (query.henkilo) {
                showCurrentRows([ { henkiloOid: query.henkilo } ]);
                resetPageNumbers();
                stopLoading();
            }
        }

        var searchTerms = [];
        if ($scope.henkiloTerm) {
            var henkiloTerm = $q.defer();
            searchTerms.push(henkiloTerm);
            var henkiloSearchUrl = henkiloServiceUrl + '/resources/henkilo?index=0&count=1&no=true&p=false&s=true&q=' + encodeURIComponent($scope.henkiloTerm.trim().toUpperCase());
            $http.get(henkiloSearchUrl, {cache: false})
                .success(function (henkilo) {
                    if (henkilo.results && henkilo.results.length === 1) {
                        $scope.henkilo = henkilo.results[0];
                        henkiloTerm.resolve();
                    } else {
                        henkiloTerm.reject();
                    }
                })
                .error(function() { henkiloTerm.reject() });
        }
        if ($scope.organisaatioTerm && $scope.organisaatioTerm.oppilaitosKoodi && !$scope.organisaatioTerm.oid) {
            var organisaatioTerm = $q.defer();
            searchTerms.push(organisaatioTerm);
            if ($scope.organisaatioTerm.oppilaitosKoodi) {
                getOrganisaatio($http, $scope.organisaatioTerm.oppilaitosKoodi, function(organisaatio) {
                    $scope.organisaatioTerm = organisaatio;
                    organisaatioTerm.resolve();
                }, function() { organisaatioTerm.reject() });
            }
        }
        if (searchTerms.length > 0) {
            var allTermsReady = $q.all(searchTerms.map(function(d) { return d.promise; }));
            allTermsReady.then(function() {
                doSearch({
                    henkilo: ($scope.henkilo ? $scope.henkilo.oidHenkilo : null),
                    oppilaitosOid: ($scope.organisaatioTerm ? $scope.organisaatioTerm.oid : null)
                });
            }, function() { stopLoading() });
        } else stopLoading();
    };

    function showCurrentRows(allRows) {
        $scope.allRows = allRows;
        $scope.currentRows = allRows.slice($scope.page * $scope.pageSize, ($scope.page + 1) * $scope.pageSize);
        enrichData();
    }

    function enrichData() {
        function enrichHenkilo(row) {
            $http.get(henkiloServiceUrl + '/resources/henkilo/' + encodeURIComponent(row.henkiloOid), {cache: false})
                .success(function(henkilo) {
                    if (henkilo) {
                        row.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + " (" + (henkilo.hetu ? henkilo.hetu : henkilo.syntymaaika) + ")"
                    }
                });
        }
        function getAndEnrichOpiskelijat(row) {
            Opiskelijat.query({henkilo: row.henkiloOid}, function(opiskelijat) {
                angular.forEach(opiskelijat, function(o) {
                    getOrganisaatio($http, o.oppilaitosOid, function(oppilaitos) {
                        o.oppilaitos = (oppilaitos.oppilaitosKoodi ? oppilaitos.oppilaitosKoodi + ' ' : '') + (oppilaitos.nimi.fi ? oppilaitos.nimi.fi : oppilaitos.nimi.sv)
                    })
                });
                row.opiskelijatiedot = opiskelijat;
            });
        }
        function getAndEnrichSuoritukset(row) {
            Suoritukset.query({henkilo: row.henkiloOid}, function(suoritukset) {
                angular.forEach(suoritukset, function(o) {
                    getOrganisaatio($http, o.myontaja, function(oppilaitos) {
                        o.oppilaitos = (oppilaitos.oppilaitosKoodi ? oppilaitos.oppilaitosKoodi + ' ' : '') + ' ' + (oppilaitos.nimi.fi ? oppilaitos.nimi.fi : oppilaitos.nimi.sv)
                    });
                    if (o.komo.match(/^koulutus_\d*$/))
                        getKoulutusNimi($http, o.komo, function(koulutusNimi) {
                            o.koulutus = koulutusNimi
                        });
                });
                row.suoritustiedot = suoritukset;
                angular.forEach(suoritukset, function(s) {
                    Arvosanat.query({ suoritus: s.id }, function(arvosanat) {
                        if (arvosanat.length > 0)
                            s.hasArvosanat = true;
                        else
                            s.noArvosanat = true;
                    })
                })
            })
        }
        angular.forEach($scope.currentRows, function(row) {
            if (row.henkiloOid) {
                enrichHenkilo(row);
                getAndEnrichOpiskelijat(row);
                getAndEnrichSuoritukset(row);
            }
        });
    }

    $scope.nextPage = function() {
        if (($scope.page + 1) * $scope.pageSize < $scope.allRows.length) $scope.page++;
        else $scope.page = 0;
        resetPageNumbers();
        showCurrentRows($scope.allRows);
    };
    $scope.prevPage = function() {
        if ($scope.page > 0 && ($scope.page - 1) * $scope.pageSize < $scope.allRows.length) $scope.page--;
        else $scope.page = Math.floor($scope.allRows.length / $scope.pageSize);
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
        if (index !== -1) $scope.messages.splice(index, 1);
    };

    function resetPageNumbers() {
        $scope.pageNumbers = [];
        for (var i = 0; i < Math.ceil($scope.allRows.length / $scope.pageSize); i++) {
            if (i === 0 || (i >= ($scope.page - 3) && i <= ($scope.page + 3)) || i === (Math.ceil($scope.allRows.length / $scope.pageSize) - 1))
                $scope.pageNumbers.push(i + 1)
        }
    }

    function cannotAuthenticate() {
        $scope.messages.push({
            type: "danger",
            messageKey: "suoritusrekisteri.opiskelijat.henkiloeiyhteytta",
            message: "Henkilöpalveluun ei juuri nyt saada yhteyttä.",
            descriptionKey: "suoritusrekisteri.opiskelijat.henkiloyrita",
            description: "Yritä hetken kuluttua uudelleen."
        })
    }

    authenticateToAuthenticationService($http, $scope.fetch, cannotAuthenticate);
}
