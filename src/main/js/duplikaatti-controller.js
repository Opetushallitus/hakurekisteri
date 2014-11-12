'use strict';

app.controller('DuplikaattiCtrl', ['$scope', '$rootScope', '$log', 'arvosanat', function($scope, $rootScope, $log, arvosanat) {
    $scope.arvosanat = arvosanat.sort(function(a, b) {
        if (a.aine === b.aine) {
            if (a.lisatieto === b.lisatieto) {
                return (a.valinnainen === true && b.valinnainen === false ? 1 : (a.valinnainen === false && b.valinnainen === true ? -1 : 0))
            } else {
                return (a.lisatieto < b.lisatieto ? -1 : 1)
            }
        } else {
            return (a.aine < b.aine ? -1 : 1)
        }
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
}]);