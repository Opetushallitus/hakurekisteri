'use strict';

app.controller('HakeneetCtrl', ['$scope', '$http', '$modal', 'MurupolkuService', 'MessageService', 'aste', function($scope, $http, $modal, MurupolkuService, MessageService, aste) {
    $scope.haut = [];
    $scope.kaudet = [];
    $scope.hakuehdot = [
        {value: 'Kaikki', text: 'Kaikki hakeneet'},
        {value: 'Hyvaksytyt', text: 'Hyväksytyt'},
        {value: 'Vastaanottaneet', text: 'Paikan vastaanottaneet'}
    ];

    function isKk() {
        return aste === 'kk'
    }

    function tiedostotyypit() {
        if (isKk()) return [
            {value: 'Json', text: 'JSON'},
            {value: 'Excel', text: 'Excel'}
        ];
        return [
            {value: 'Json', text: 'JSON'},
            {value: 'Xml', text: 'XML'},
            {value: 'Excel', text: 'Excel'}
        ];
    }

    $scope.tiedostotyypit = tiedostotyypit();

    if (isKk()) $scope.vainKkHaut = true;

    if (isKk())
        MurupolkuService.addToMurupolku({key: "suoritusrekisteri.hakeneet.muru.kk", text: "Hakeneet ja valitut opiskelijat (KK)"}, true);
    else
        MurupolkuService.addToMurupolku({key: "suoritusrekisteri.hakeneet.muru", text: "Hakeneet ja valitut opiskelijat"}, true);

    loadHakutiedot($http, $scope, MessageService);

    $scope.reloadHakutiedot = function() {
        loadHakutiedot($http, $scope, MessageService)
    };

    $scope.search = function() {
        MessageService.clearMessages();

        if (isKk()) {
            if (!$scope.oppijanumero && !$scope.hakukohde) {
                if (!$scope.oppijanumero) {
                    MessageService.addMessage({
                        type: "danger",
                        message: "Oppijanumeroa ei ole syötetty.",
                        description: "Syötä oppijanumero ja yritä uudelleen."
                    })
                }
                if (!$scope.hakukohde) {
                    MessageService.addMessage({
                        type: "danger",
                        message: "Hakukohdetta ei ole valittu.",
                        description: "Valitse hakukohde ja yritä uudelleen. Hakukohde on helpompi löytää, jos valitset ensin haun ja organisaation."
                    })
                }
                return;
            }
        } else {
            if (!$scope.haku || !$scope.organisaatio || !$scope.hakuehto || !$scope.tiedostotyyppi) {
                if (!$scope.haku) {
                    MessageService.addMessage({
                        type: "danger",
                        message: "Hakua ei ole valittu.",
                        description: "Valitse haku ja yritä uudelleen."
                    })
                }
                if (!$scope.organisaatio) {
                    MessageService.addMessage({
                        type: "danger",
                        message: "Organisaatiota ei ole valittu.",
                        description: "Valitse organisaatio ja yritä uudelleen."
                    })
                }
                return;
            }
        }

        var url = isKk() ? 'rest/v1/kkhakijat' : 'rest/v1/hakijat';
        var data = isKk() ? {
            oppijanumero: $scope.oppijanumero ? $scope.oppijanumero : null,
            haku: $scope.haku ? $scope.haku.oid : null,
            organisaatio: $scope.organisaatio ? $scope.organisaatio.oid : null,
            hakukohde: $scope.hakukohde ? $scope.hakukohde : null,
            hakuehto: $scope.hakuehto,
            tyyppi: $scope.tiedostotyyppi,
            tiedosto: true
        } : {
            haku: $scope.haku ? $scope.haku.oid : null,
            organisaatio: $scope.organisaatio ? $scope.organisaatio.oid : null,
            hakukohdekoodi: $scope.hakukohde ? $scope.hakukohde : null,
            hakuehto: $scope.hakuehto,
            tyyppi: $scope.tiedostotyyppi,
            tiedosto: true
        };

        $scope.fileLoading = true;

        $.fileDownload(url, {
            data: data
        }).done(function() {
            $scope.$apply(function () {
                delete $scope.fileLoading;
            });
        }).fail(function() {
            $scope.$apply(function() {
                MessageService.addMessage({
                    type: "danger",
                    message: "Tiedoston lataaminen epäonnistui.",
                    description: "Palvelussa saattaa olla kuormaa. Yritä hetken kuluttua uudelleen."
                });
                delete $scope.fileLoading;
            });
        });
    };

    $scope.reset = function() {
        MessageService.clearMessages();
        delete $scope.kausi;
        delete $scope.organisaatio;
        delete $scope.hakukohde;
        $scope.hakukohdenimi = "";
        $scope.hakuehto = 'Kaikki';
        $scope.tiedostotyyppi = 'Json';
    };
    $scope.reset();

    $scope.avaaOrganisaatiohaku = function() {
        var isolatedScope = $scope.$new(true);
        isolatedScope.modalInstance = $modal.open({
            templateUrl: 'templates/organisaatiohaku',
            controller: 'OrganisaatioCtrl',
            scope: isolatedScope,
            size: 'lg'
        });
        $scope.modalInstance = isolatedScope.modalInstance;

        $scope.modalInstance.result.then(function (valittu) {
            $scope.organisaatio = valittu;
            $scope.clearHakukohde();
        }, function () {
            // error
        });
    };

    $scope.clearHakukohde = function() {
        delete $scope.hakukohdenimi;
        delete $scope.hakukohde;
    };

    $scope.hakukohdekoodit = [];
    loadHakukohdekoodit($http, $scope);

    // korkeakouluaste
    $scope.searchHakukohde = function() {
        return $http.get(tarjontaServiceUrl + '/rest/v1/hakukohde/search', {
            params: {
                searchTerms: $scope.hakukohdenimi,
                hakuOid: $scope.haku ? $scope.haku.oid : null,
                organisationOid: $scope.organisaatio ? $scope.organisaatio.oid : null
            },
            cache: true
        }).then(function(res) {
            if (!res.data.result || res.data.result.tuloksia === 0) return [];

            var hakukohteet = res.data.result.tulokset.map(function(tarjoaja) {
                return tarjoaja.tulokset.map(function(hakukohde) {
                    return {
                        oid: hakukohde.oid,
                        nimi: (tarjoaja.nimi.fi ? tarjoaja.nimi.fi : (tarjoaja.nimi.sv ? tarjoaja.nimi.sv : tarjoaja.nimi.en)) + ": " +
                            (hakukohde.nimi.fi ? hakukohde.nimi.fi : (hakukohde.nimi.sv ? hakukohde.nimi.sv : hakukohde.nimi.en)) + ": " +
                            hakukohde.vuosi + " " + (hakukohde.kausi.fi ? hakukohde.kausi.fi : (hakukohde.kausi.sv ? hakukohde.kausi.sv : hakukohde.kausi.en))
                    }
                })
            }).reduce(function(a, b) { return a.concat(b) });

            hakukohteet.sort(function(a, b) {
                if (!a.nimi && !b.nimi) return 0;
                if (!a.nimi) return -1;
                if (!b.nimi) return 1;
                if (a.nimi.toLowerCase() === b.nimi.toLowerCase()) return 0;
                return a.nimi.toLowerCase() < b.nimi.toLowerCase() ? -1 : 1;
            });

            return hakukohteet;
        }, function() { return [] })
    };
    $scope.setHakukohde = function (item) {
        $scope.hakukohde = item.oid
    };
    $scope.searchHenkilo = function() {
        if ($scope.oppijanumero && $scope.oppijanumero.trim().match(/[0-9.]{11,30}/)) {
            $http.get(henkiloServiceUrl + '/resources/henkilo/' + encodeURIComponent($scope.oppijanumero.trim()), {cache: true})
                .then(function(res) {
                    $scope.henkilo = res.data;
                })
        }
    };

    // toinen aste
    $scope.searchHakukohdekoodi = function(text) {
        return $scope.hakukohdekoodit.filter(function(h) {
            if (!text) return false;
            return (h.koodi && h.koodi.indexOf(text) > -1) || (h.nimi && h.nimi.toLowerCase().indexOf(text.toLowerCase()) > -1);
        });
    };
    $scope.setHakukohdenimi = function() {
        if ($scope.hakukohde) {
            var nimet = $scope.searchHakukohdekoodi($scope.hakukohde);
            if (nimet.length === 1)
                $scope.hakukohdenimi = nimet[0].nimi;
            else
                $scope.hakukohdenimi = "";
        } else {
            $scope.hakukohdenimi = "";
        }
    };
}]);

function loadHakutiedot($http, $scope, MessageService) {
    $http.get('rest/v1/haut', {cache: true})
        .success(function(hautResponse) {
            var kaudet = [];
            var haut = [];
            kaudet.push({text: ''});

            var kausiExists = function(kausi) {
                return kaudet.some(function(k) {
                    return (k.vuosi === kausi.vuosi && k.kausi === kausi.kausi)
                });
            };
            var resolveKausiText = function(kausiUri) {
                return (kausiUri && kausiUri.match(/^kausi_s.*/) ? 'Syksy' : (kausiUri && kausiUri.match(/^kausi_k.*/) ? 'Kevät' : 'KAUSI PUUTTUU'))
            };

            var filteredHaut = hautResponse.filter(function(h) {
                if ($scope.vainKkHaut) return h.kkHaku;
                else return true;
            });
            for (var i = 0; i < filteredHaut.length; i++) {
                var haku = filteredHaut[i];
                var k = {
                    vuosi: haku.vuosi,
                    kausi: haku.kausi,
                    text: '' + haku.vuosi + ' ' + resolveKausiText(haku.kausi)
                };
                if (!kausiExists(k)) kaudet.push(k);

                haut.push({
                    vuosi: haku.vuosi,
                    kausi: haku.kausi,
                    hakukausi: resolveKausiText(haku.kausi),
                    oid: haku.oid,
                    text: (haku.nimi && haku.nimi.fi ? haku.nimi.fi : (haku.nimi && haku.nimi.sv ? haku.nimi.sv : (haku.nimi && haku.nimi.en ? haku.nimi.en : 'NIMI PUUTTUU')))
                });
            }

            var sortByNimi = function(a, b) {
                if (a && b && a.text && b.text)
                    if (a.text.toLowerCase() === b.text.toLowerCase()) return 0;
                    else return a.text.toLowerCase() < b.text.toLowerCase() ? -1 : 1;
                return 0;
            };
            kaudet.sort(sortByNimi);

            var sortByKausiAndNimi = function(a, b) {
                var aKausi = a.vuosi + a.kausi;
                var bKausi = b.vuosi + b.kausi;
                if (aKausi === bKausi) return sortByNimi(a, b);
                else return aKausi < bKausi ? 1 : -1;
            };
            haut.sort(sortByKausiAndNimi);

            $scope.kaudet = kaudet;
            if (kaudet.length > 0) {
                $scope.kausi = kaudet[0];
            }
            $scope.haut = haut;
        })
        .error(function() {
            MessageService.addMessage({
                type: "danger",
                message: "Tietojen lataaminen näytölle epäonnistui.",
                description: "Päivitä näyttö tai navigoi sille uudelleen."
            });
        });
}

function loadHakukohdekoodit($http, $scope) {
    $http.get(koodistoServiceUrl + '/rest/json/hakukohteet/koodi', { cache: true })
        .success(function(data) {
            $scope.hakukohdekoodit = data.map(function(koodi) {
                return {
                    koodi: koodi.koodiArvo,
                    nimi: koodi.metadata.sort(function(a, b) {
                        if (a.kieli && b.kieli && a.kieli !== b.kieli) return (a.kieli < b.kieli ? -1 : 1);
                        else return 0;
                    })
                        .map(function(kielistys) {
                            return kielistys.nimi;
                        })
                        .join("; ")
                };
            }).sort(function(a, b) {
                if (a.koodi && b.koodi && a.koodi !== b.koodi) return (a.koodi < b.koodi ? -1 : 1);
                else return 0;
            });
        });
}
