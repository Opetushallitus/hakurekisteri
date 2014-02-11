'use strict';

var msgCategory = "suoritusrekisteri";

function OpiskelijatCtrl($scope, $routeParams, $log, $http, Henkilo, HenkiloByHetu, Organisaatio, Opiskelijat) {
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
        $http.get('/cas/myroles')
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
            HenkiloByHetu.get({hetu: $scope.searchTerm}, function(henkilo) {
                $scope.hakuehto = henkilo.hetu + ' (' + henkilo.etunimet + ' ' + henkilo.sukunimi + ')';
                search({henkiloOid: henkilo.oidHenkilo});
            }, function() {
                $scope.hakuehto = $scope.searchTerm;
                $scope.loading = false;
            });
        } else if ($scope.searchTerm && $scope.searchTerm.match(/^\d{5}$/)) {
            Organisaatio.get({organisaatioOid: $scope.searchTerm}, function(organisaatio) {
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
        for (var i = 0; i < $scope.currentRows.length; i++) {
            var opiskelija = $scope.currentRows[i];
            if (opiskelija.oppilaitosOid) {
                Organisaatio.get({organisaatioOid: opiskelija.oppilaitosOid}, function(data) {
                    if (data && data.oid === opiskelija.oppilaitosOid)
                        opiskelija.oppilaitos = data.oppilaitosKoodi + ' ' + data.nimi.fi;
                });
            }
            if (opiskelija.henkiloOid) {
                Henkilo.get({henkiloOid: opiskelija.henkiloOid}, function(henkilo) {
                    if (henkilo && henkilo.oidHenkilo === opiskelija.henkiloOid && henkilo.sukunimi && henkilo.etunimet) {
                        opiskelija.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + (henkilo.hetu ? " (" + henkilo.hetu + ")" : "");
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




function MuokkaaCtrl($scope, $routeParams, $location, $log, Henkilo, Organisaatio, Koodi, Koodisto, Opiskelijat, Suoritukset) {
    $scope.messages = [];
    $scope.henkiloOid = $routeParams.henkiloOid;

    function fetchHenkilotiedot() {
        Henkilo.get({henkiloOid: $scope.henkiloOid}, function(henkilo) {
            $scope.henkilo = henkilo;
        }, function() {
            confirm("Henkilötietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchHenkilotiedot() : $location.path("/opiskelijat");
        });
    }
    fetchHenkilotiedot();

    function fetchLuokkatiedot() {
        Opiskelijat.query({henkiloOid: $scope.henkiloOid}, function(luokkatiedot) {
            $scope.luokkatiedot = luokkatiedot;
            enrichLuokkatiedot();
        }, function() {
            confirm("Luokkatietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchLuokkatiedot() : $location.path("/opiskelijat");
        });
    }
    fetchLuokkatiedot();

    function fetchSuoritukset() {
        Suoritukset.query({henkiloOid: $scope.henkiloOid}, function(suoritukset) {
            $scope.suoritukset = suoritukset;
            enrichSuoritukset();
        }, function() {
            confirm("Suoritustietojen hakeminen epäonnistui. Yritä uudelleen?") ? fetchSuoritukset() : $location.path("/opiskelijat");
        });
    }
    fetchSuoritukset();

    function enrichSuoritukset() {
        if ($scope.suoritukset) {
            for (var i = 0; i < $scope.suoritukset.length; i++) {
                var suoritus = $scope.suoritukset[i];
                if (suoritus.komoto && suoritus.komoto.tarjoaja) {
                    Organisaatio.get({organisaatioOid: suoritus.komoto.tarjoaja}, function(organisaatio) {
                        if (organisaatio.oid === suoritus.komoto.tarjoaja) {
                            suoritus.oppilaitos = organisaatio.nimi.fi ? organisaatio.nimi.fi : organisaatio.nimi.sv;
                        }
                    });
                }
            }
        }
    }
    function enrichLuokkatiedot() {
        if ($scope.luokkatiedot) {
            for (var i = 0; i < $scope.luokkatiedot.length; i++) {
                var luokkatieto = $scope.luokkatiedot[i];
                if (luokkatieto.oppilaitosOid) {
                    Organisaatio.get({organisaatioOid: luokkatieto.oppilaitosOid}, function(organisaatio) {
                        if (organisaatio.oid === luokkatieto.oppilaitosOid) {
                            luokkatieto.oppilaitos = organisaatio.oppilaitosKoodi;
                        }
                    })
                }
            }
        }
    }

    $scope.yksilollistamiset = ["Ei", "Osittain", "Kokonaan", "Alueittain"];

    getKoodistoAsOptionArray("maatjavaltiot2", Koodisto, 'FI', $scope.maat);
    getKoodistoAsOptionArray("kunta", Koodisto, 'FI', $scope.kunnat);
    getKoodistoAsOptionArray("kieli", Koodisto, 'FI', $scope.kielet);
    getKoodistoAsOptionArray("maatjavaltiot2", Koodisto, 'FI', $scope.kansalaisuudet);

    $scope.fetchPostitoimipaikka = function() {
        if ($scope.henkilo.postinumero && $scope.henkilo.postinumero.match(/^\d{5}$/)) {
            $scope.searchingPostinumero = true;
            Koodi.get({koodisto: "posti", koodiUri: "posti_" + $scope.henkilo.postinumero}, function(koodi) {
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
        for (var i = 0; i < $scope.luokkatiedot.length; i++) {
            var luokkatieto = $scope.luokkatiedot[i];
            Organisaatio.get({organisaatioOid: luokkatieto.oppilaitos}, function(organisaatio) {
                luokkatieto.oppilaitosOid = organisaatio.oid;
            }, function() {
                $scope.messages.push({
                    type: "danger",
                    message: "Virheellinen oppilaitoskoodi: " + luokkatieto.oppilaitos + ".",
                    description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
                });
            });
        }
        if ($scope.messages.length > 0) {
            return;
        }

        $scope.henkilo.$save(function(savedHenkilo) {
            $log.debug("henkilo saved: " + savedHenkilo);
            $scope.henkilo = savedHenkilo;
        }, function() {
            $scope.messages.push({
                type: "danger",
                message: "Virhe tallennettaessa henkilötietoja.",
                description: "Yritä uudelleen."
            });
        });

        for (var i = 0; i < $scope.suoritukset.length; i++) {
            var suoritus = $scope.suoritukset[i];
            suoritus.$save(function(savedSuoritus) {
                $log.debug("suoritus saved: " + savedSuoritus);
                suoritus = savedSuoritus;
            }, function() {
                $scope.messages.push({
                    type: "danger",
                    message: "Virhe tallennettaessa suoritustietoja.",
                    description: "Yritä uudelleen."
                });
            });
        }

        for (var i = 0; i < $scope.luokkatiedot.length; i++) {
            var luokkatieto = $scope.luokkatiedot[i];
            luokkatieto.$save(function(savedLuokkatieto) {
                $log.debug("opiskelija saved: " + savedLuokkatieto);
                luokkatieto = savedLuokkatieto;
            }, function() {
                $scope.messages.push({
                    type: "danger",
                    message: "Virhe tallennettaessa luokkatietoja.",
                    description: "Yritä uudelleen."
                });
            });
        }
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

function getKoodistoAsOptionArray(koodisto, Koodisto, kielikoodi, options) {
    options = [];
    Koodisto.query({koodisto: koodisto}, function(koodisto) {
        for (var i = 0; i < koodisto.length; i++) {
            var koodi = koodisto[i];
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
        }
        options.sort(function(a, b) {
            if (a.text === b.text) return 0;
            return a.text < b.text ? -1 : 1;
        });
    });
}
