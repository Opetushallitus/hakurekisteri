'use strict';

function EihakeneetCtrl($scope, $routeParams, $http, $q) {
    var hakuOid = $routeParams.haku;
    var oppilaitosOid = $routeParams.oppilaitos;
    var luokka = $routeParams.luokka;

    $scope.loading = false;
    $scope.allRows = [];
    $scope.messages = [];

    function enrichOpiskelijat() {
        var deferredEnrichments = [];
        angular.forEach($scope.allRows, function(opiskelija) {
            if (opiskelija.henkiloOid) {
                var deferredEnrichment = $q.defer();
                deferredEnrichments.push(deferredEnrichment);
                $http.get(henkiloServiceUrl + '/resources/henkilo/' + encodeURIComponent(opiskelija.henkiloOid), {cache: true})
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
                    opiskelija.oppilaitos = organisaatio.oppilaitosKoodi + ' ' + organisaatio.nimi.fi;
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

    $scope.fetch = function() {
        if (hakuOid && oppilaitosOid && luokka) {
            var deferredOpiskelijat = $q.defer();
            var luokanOpiskelijat = [];
            $http.get("rest/v1/opiskelijat?oppilaitosOid=" + encodeURIComponent(oppilaitosOid)
                    + "&luokka=" + encodeURIComponent(luokka))
                .success(function(opiskelijat) {
                    if (opiskelijat) {
                        luokanOpiskelijat = opiskelijat;
                    }
                    deferredOpiskelijat.resolve("done");
                })
                .error(function() {
                    deferredOpiskelijat.reject("error");
                });

            var deferredHakemukset = $q.defer();
            var luokanHakemukset = [];
            $http.get(hakuAppServiceUrl + "/applications/list/fullName/asc?appState=ACTIVE&asYear=2014&asSemester=kausi_k&discretionaryOnly=false&checkAllApplications=false&start=0&rows=50&sendingSchoolOid="
                    + encodeURIComponent(oppilaitosOid))
                .success(function(hakemukset) {
                    if (hakemukset && hakemukset.results) {
                        luokanHakemukset = hakemukset.results;
                    }
                    deferredHakemukset.resolve("done");
                })
                .error(function() {
                    deferredHakemukset.reject("error");
                });

            var bothPromise = $q.all([deferredOpiskelijat.promise, deferredHakemukset.promise]);
            bothPromise.then(function() {
                var hakeneetOpiskelijat = [];
                for (var i = 0; i < luokanOpiskelijat.length; i++) {
                    var opiskelija = luokanOpiskelijat[i];
                    for (var j = 0; j < luokanHakemukset.length; j++) {
                        if (opiskelija.henkiloOid === luokanHakemukset[j].personOid) {
                            hakeneetOpiskelijat.push(opiskelija);
                        }
                    }
                }
                $scope.allRows = luokanOpiskelijat.diff(hakeneetOpiskelijat);
                enrichOpiskelijat();
            }, function(errors) {
                $scope.messages.push({
                    type: "danger",
                    message: "Virhe ladattaessa tietoja: " + errors,
                    description: ""
                });
            });
        } else {
            $scope.messages.push({
                type: "danger",
                message: "Virheelliset parametrit:",
                description: "haku=" + hakuOid + ", oppilaitos=" + oppilaitosOid + ", luokka=" + luokka
            });
        }
    };

    $scope.fetch();

    $scope.removeMessage = function(message) {
        var index = $scope.messages.indexOf(message);
        if (index !== -1) {
            $scope.messages.splice(index, 1);
        }
    };
}

