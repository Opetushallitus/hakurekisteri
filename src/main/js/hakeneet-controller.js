function HakeneetCtrl($scope, $rootScope, $http, $location, $modal) {
    $scope.errors = [];
    $scope.haut = [];
    $scope.kaudet = [];
    $scope.hakuehdot = [
        {value: 'Kaikki', text: 'Kaikki hakeneet'},
        {value: 'Hyvaksytyt', text: 'Hyväksytyt'},
        {value: 'Vastaanottaneet', text: 'Paikan vastaanottaneet'}
    ];
    $scope.tiedostotyypit = [
        {value: 'Json', text: 'JSON'},
        {value: 'Xml', text: 'XML'},
        {value: 'Excel', text: 'Excel'}
    ];

    $rootScope.activeMenuItem = $location.path();
    $rootScope.addToMurupolku({text: "Hakeneet ja valitut opiskelijat"}, true);

    function loadHakutiedot() {
        $http.get('rest/v1/haut', {cache: true})
            .success(function(hautResponse) {
                var kaudet = [];
                var haut = [];
                kaudet.push({text: ''});

                var containsKausi = function(kaudet, kausi) {
                    for (var i = 0; i < kaudet.length; i++) {
                        var k = kaudet[i];
                        if (k.vuosi === kausi.vuosi && k.kausi === kausi.kausi) {
                            return true;
                        }
                    }
                    return false;
                };
                var resolveKausiText = function(kausiUri) {
                    return (kausiUri && kausiUri.match(/^kausi_s.*/) ? 'Syksy' : (kausiUri && kausiUri.match(/^kausi_k.*/) ? 'Kevät' : 'KAUSI PUUTTUU'))
                };

                for (var i = 0; i < hautResponse.length; i++) {
                    var haku = hautResponse[i];
                    var k = {
                        vuosi: haku.vuosi,
                        kausi: haku.kausi,
                        text: '' + haku.vuosi + ' ' + resolveKausiText(haku.kausi)
                    };
                    if (!containsKausi(kaudet, k)) kaudet.push(k);

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
                $scope.errors.push({
                    message: "Tietojen lataaminen näytölle epäonnistui.",
                    description: "Päivitä näyttö tai navigoi sille uudelleen."
                });
            });
    }

    loadHakutiedot();

    $scope.search = function() {
        $scope.errors.length = 0;

        if (!$scope.haku || !$scope.organisaatio || !$scope.hakuehto || !$scope.tiedostotyyppi) {
            if (!$scope.haku) {
                $scope.errors.push({
                    message: "Hakua ei ole valittu.",
                    description: "Valitse haku ja yritä uudelleen."
                })
            }
            if (!$scope.organisaatio) {
                $scope.errors.push({
                    message: "Organisaatiota ei ole valittu.",
                    description: "Valitse organisaatio ja yritä uudelleen."
                })
            }
            if (!$scope.hakuehto) {
                $scope.errors.push({
                    message: "Hakuehtoa ei ole valittu.",
                    description: "Valitse hakuehto ja yritä uudelleen."
                })
            }
            if (!$scope.tiedostotyyppi) {
                $scope.errors.push({
                    message: "Tiedostotyyppiä ei ole valittu.",
                    description: "Valitse tiedostotyyppi ja yritä uudelleen."
                })
            }
            return;
        }

        $scope.fileLoading = true;

        $.fileDownload('rest/v1/hakijat', {
            data: {
                haku: $scope.haku ? $scope.haku.oid : null,
                organisaatio: $scope.organisaatio ? $scope.organisaatio.oid : null,
                hakukohdekoodi: $scope.hakukohde ? $scope.hakukohde : null,
                hakuehto: $scope.hakuehto,
                tyyppi: $scope.tiedostotyyppi,
                tiedosto: true
            }
        }).done(function() {
            $scope.$apply(function () {
                delete $scope.fileLoading;
            });
        }).fail(function() {
            $scope.$apply(function() {
                $scope.errors.push({
                    message: "Tiedoston lataaminen epäonnistui.",
                    description: "Palvelussa saattaa olla kuormaa. Yritä hetken kuluttua uudelleen."
                });
                delete $scope.fileLoading;
            });
        });
    };

    $scope.reset = function() {
        $scope.errors.length = 0;
        delete $scope.kausi;
        delete $scope.organisaatio;
        delete $scope.hakukohde;
        $scope.hakukohdenimi = "";
        $scope.hakuehto = 'Kaikki';
        $scope.tiedostotyyppi = 'Xml';
    };
    $scope.reset();

    $scope.removeError = function(error) {
        var index = $scope.errors.indexOf(error);
        if (index !== -1) {
            $scope.errors.splice(index, 1);
        }
    };

    $scope.avaaOrganisaatiohaku = function() {
        $rootScope.modalInstance = $modal.open({
            templateUrl: 'templates/organisaatiohaku',
            controller: OrganisaatioCtrl
        });

        $rootScope.modalInstance.result.then(function (valittu) {
            $scope.organisaatio = valittu;
        }, function () {
            // error
        });
    };

    $scope.hakukohdekoodit = [];
    function loadHakukohdekoodit() {
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
    loadHakukohdekoodit();

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
}


