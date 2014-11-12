'use strict';

app.controller('MurupolkuCtrl', ['$rootScope', function($rootScope) {
    $rootScope.murupolku = [];

    $rootScope.addToMurupolku = function(element, reset) {
        if (reset) {
            $rootScope.murupolku.length = 0;
        }
        $rootScope.murupolku.push(element);
    };
}]);