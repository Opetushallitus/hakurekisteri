'use strict';

function ArvosanaCtrl($scope, $rootScope, $http, $q, $log, Arvosanat, Suoritukset, suoritusId) {
    $scope.arvosanat = [];
    $scope.oppiaineet = [];
    $scope.valinnaisuudet = [
        {value: false, text: getOphMsg("suoritusrekisteri.valinnaisuus.ei", "Ei")},
        {value: true, text: getOphMsg("suoritusrekisteri.valinnaisuus.kylla", "Kyllä")}
    ];

    Suoritukset.get({ suoritusId: suoritusId }, function(suoritus) {
        var pohjakoulutusFilter = "onperusasteenoppiaine_1";
        if (suoritus.komo === "lukio") {
            pohjakoulutusFilter = "onlukionoppiaine_1";
        }

        var koodistoPromises = [];
        var oppiaineet = {};

        $http.get(koodistoServiceUrl + '/rest/json/oppiaineetyleissivistava/koodi/', { cache: true })
            .success(function(koodit) {
                for (var i = 0; i < koodit.length; i++) {
                    var koodi = koodit[i];
                    var p = $http.get(koodistoServiceUrl + '/rest/json/relaatio/sisaltyy-alakoodit/' + koodi.koodiUri, { cache: true })
                        .success(function (alaKoodit) {
                            oppiaineet[koodi.koodiUri] = { koodi: koodi, alaKoodit: alaKoodit };
                        });
                    koodistoPromises.push(p);
                }

                var koodistoDone = $q.all(koodistoPromises);
                koodistoDone.then(function() {

                    function filterOppiaine(koodiUri) {
                        return oppiaineet[koodiUri].alaKoodit.filter(function(alakoodi) {
                            alakoodi.koodiUri === pohjakoulutusFilter;
                        }).length > 0
                    }
                    function composeOppiaine(koodiUri) {
                        var text = oppiaineet[koodiUri].koodi.metadata.sort(function (a, b) { return a.kieli < b.kieli ? -1 : 1 })[0].nimi;
                        return {
                            oppiaine: oppiaineet[koodiUri].koodi.koodiArvo,
                            oppiaineText: text,
                            isValinnainen: oppiaineet[koodiUri].alaKoodit.filter(function (alakoodi) {
                                alakoodi.koodiUri === 'oppiaineenvalinnaisuus_1';
                            }).length > 0,
                            isKielisyys: oppiaineet[koodiUri].alaKoodit.filter(function (alakoodi) {
                                alakoodi.koodiUri === 'oppiaineenkielisyys_1 ';
                            }).length > 0
                        };
                    }

                    $scope.oppiaineet = Object.keys(oppiaineet).filter(filterOppiaine).map(composeOppiaine);

                    function fetchArvosanat() {
                        Arvosanat.query({ suoritus: suoritusId }, function(arvosanat) {
                            $scope.arvosanat = arvosanat;
                            if (arvosanat.length === 0) {
                                $scope.arvosanat = $scope.oppiaineet.map(function(o) {
                                    return new Arvosanat({ aine: o.oppiaine })
                                });
                            }
                        }, function() {
                            $rootScope.modalInstance.close({
                                type: "danger",
                                messageKey: "suoritusrekisteri.muokkaa.arvosanat.arvosanapalveluongelma",
                                message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
                            })
                        });
                    }

                    fetchArvosanat();
                });
            })
            .error(function() {
                $rootScope.modalInstance.close({
                    type: "danger",
                    messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma",
                    message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
                })
            });
    }, function() {
        $rootScope.modalInstance.close({
            type: "danger",
            messageKey: "suoritusrekisteri.muokkaa.arvosanat.taustapalveluongelma",
            message: "Taustapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
        })
    });

    $scope.addArvosana = function() {
        $scope.arvosanat.push(new Arvosanat({ suoritus: suoritusId, arvio: { asteikko: "4-10" }, lisatiedot: "", valinnainen: "false" }))
    };

    $scope.save = function() {
        $rootScope.modalInstance.close({
            type: "success",
            messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennettu",
            message: "Arvosanat tallennettu."
        })
    };

    $scope.cancel = function() {
        $rootScope.modalInstance.close();
    };

    $scope.delete = function(arvosana) {
        var index = $scope.arvosanat.indexOf(arvosana);
        if (index !== -1) $scope.arvosanat.splice(index, 1);
    };
}