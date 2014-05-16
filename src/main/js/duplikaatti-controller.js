'use strict';

function DuplikaattiCtrl($scope, $rootScope, $log, Arvosanat, suoritusId) {
    $scope.arvosanat = [];

    Arvosanat.query({ suoritus: suoritusId }, function(arvosanat) {
        $scope.arvosanat = arvosanat.filter(function(a, index, arr) {
            return arr.some(function(b, bIndex) {
                return (index !== bIndex && b.aine === a.aine && b.lisatieto === a.lisatieto && b.valinnainen === a.valinnainen)
            })
        }).sort(function(a, b) {
            if (a.aine === b.aine) {
                if (a.lisatieto === b.lisatieto) {
                    return (a.valinnainen === true && b.valinnainen === false ? 1 : (a.valinnainen === false && b.valinnainen === true ? -1 : 0))
                } else {
                    return (a.lisatieto < b.lisatieto ? -1 : 1)
                }
            } else {
                return (a.aine < b.aine ? -1 : 1)
            }
        })
    }, function() {
        $rootScope.modalInstance.close({
            type: "danger",
            messageKey: "suoritusrekisteri.muokkaa.duplikaatti.virheladattaessaarvosanoja",
            message: "Virhe ladattaessa arvosanoja. Yritä uudelleen."
        })
    });

    $scope.remove = function(arvosana) {
        arvosana.$remove(function() {
            var index = $scope.arvosanat.indexOf(arvosana);
            if (index !== -1) $scope.arvosanat.splice(index, 1);
        }, function() {
            $rootScope.modalInstance.close({
                type: "danger",
                messageKey: "suoritusrekisteri.muokkaa.duplikaatti.virhepoistettaessaarvosanaa",
                message: "Virhe poistettaessa arvosanaa. Yritä uudelleen."
            })
        })
    };

    $scope.close = function() {
        $rootScope.modalInstance.close()
    };
}