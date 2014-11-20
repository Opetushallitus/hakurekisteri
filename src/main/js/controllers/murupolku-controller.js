'use strict';

app.controller('MurupolkuCtrl', ['$scope', 'MurupolkuService', '$location', function($scope, MurupolkuService, $location) {
    $scope.murupolku = MurupolkuService.murupolku;
    $scope.isHidden = MurupolkuService.isHidden;

    $scope.goHome = function() {
        if ($location.path().match(/tiedonsiirto/)) $location.path('/tiedonsiirto');
        else $location.path('/')
    }
}]);