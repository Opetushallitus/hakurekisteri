'use strict';

app.controller('EihakeneetCtrl', ['$scope', 'MurupolkuService', 'MessageService', '$routeParams', '$http', '$q', function($scope, MurupolkuService, MessageService, $routeParams, $http, $q) {
    var hakuOid = $routeParams.haku;
    var oppilaitosOid = $routeParams.oppilaitos;
    var luokka = $routeParams.luokka;

    $scope.loading = false;
    $scope.allRows = [];

    MurupolkuService.hideMurupolku();

    function enrichOpiskelijat() {
        var deferredEnrichments = [];
        angular.forEach($scope.allRows, function(opiskelija) {
            if (opiskelija.henkiloOid) {
                var deferredEnrichment = $q.defer();
                deferredEnrichments.push(deferredEnrichment);
                $http.get(henkiloServiceUrl + '/resources/henkilo/' + encodeURIComponent(opiskelija.henkiloOid), {cache: false})
                    .success(function(henkilo) {
                        if (henkilo && henkilo.oidHenkilo === opiskelija.henkiloOid) {
                            opiskelija.sukunimi = henkilo.sukunimi;
                            opiskelija.etunimet = henkilo.etunimet;
                        }
                        deferredEnrichment.resolve("done");
                    })
                    .error(function() {
                        deferredEnrichment.reject("error");
                    });
            }
            if (opiskelija.oppilaitosOid) {
                var deferredEnrichment = $q.defer();
                deferredEnrichments.push(deferredEnrichment);
                getOrganisaatio($http, opiskelija.oppilaitosOid, function(organisaatio) {
                    opiskelija.oppilaitos = organisaatio.oppilaitosKoodi + ' ' + (organisaatio.nimi.fi ? organisaatio.nimi.fi : organisaatio.nimi.sv);
                    deferredEnrichment.resolve("done");
                }, function() {
                    deferredEnrichment.reject("error");
                });
            }
        });

        var enrichmentsDonePromise = $q.all(deferredEnrichments.map(function(enrichment) {
            return enrichment.promise;
        }));
        enrichmentsDonePromise.then(function() {
            $scope.allRows.sort(function(a, b) {
                if (a.sukunimi === b.sukunimi) {
                    if (a.etunimet === b.sukunimi)
                        return 0;
                    else
                        return a.etunimet < b.etunimet ? -1 : 1;
                } else {
                    return a.sukunimi < b.sukunimi ? -1 : 1;
                }
            });
        });
    }

    function fetchData() {
        if (hakuOid && oppilaitosOid) {
            $scope.loading = true;
            var deferredOpiskelijat = $q.defer();
            var luokanOpiskelijat = [];
            var opiskelijatConfig = { params: { oppilaitosOid: oppilaitosOid } };
            if (luokka) opiskelijatConfig.params.luokka = luokka;
            $http.get("rest/v1/opiskelijat", opiskelijatConfig)
                .success(function(opiskelijat) {
                    if (opiskelijat) {
                        luokanOpiskelijat = opiskelijat;
                    }
                    deferredOpiskelijat.resolve("done");
                })
                .error(function(data, status) {
                    deferredOpiskelijat.reject(status);
                });

            var deferredHakemukset = $q.defer();
            var luokanHakemukset = [];
            var hakemusConfig = { params: {
                discretionaryOnly: false,
                checkAllApplications: false,
                start: 0,
                rows: 500,
                sendingSchoolOid: oppilaitosOid,
                asId: hakuOid
            }};
            if (luokka) hakemusConfig.params.sendingClass = luokka;
            $http.get(hakuAppServiceUrl + "/applications/listshort", hakemusConfig)
                .success(function(hakemukset) {
                    if (hakemukset && hakemukset.results) {
                        luokanHakemukset = hakemukset.results.filter(function(h) { return h.state === 'ACTIVE' || h.state === 'INCOMPLETE' });
                    }
                    deferredHakemukset.resolve("done");
                })
                .error(function(data, status) {
                    deferredHakemukset.reject(status);
                });

            var bothPromise = $q.all([deferredOpiskelijat.promise, deferredHakemukset.promise]);
            bothPromise.then(function() {
                $scope.allRows = luokanOpiskelijat.filter(function hasNoHakemus(h) {
                    for (var i = 0; i < luokanHakemukset.length; i++) {
                        if (h.henkiloOid === luokanHakemukset[i].personOid) return false;
                    }
                    return true;
                });
                enrichOpiskelijat();
                $scope.loading = false;
            }, function(errors) {
                MessageService.addMessage({
                    type: "danger",
                    message: "Virhe ladattaessa tietoja: " + errors,
                    description: ""
                });
                $scope.loading = false;
            });
        } else {
            MessageService.addMessage({
                type: "danger",
                message: "Virheelliset parametrit:",
                description: "haku=" + hakuOid + ", oppilaitos=" + oppilaitosOid + ", luokka=" + luokka
            });
        }
    }

    fetchData();
}]);

