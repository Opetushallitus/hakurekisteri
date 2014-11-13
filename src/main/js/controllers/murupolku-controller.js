'use strict';

app.controller('MurupolkuCtrl', ['$scope', 'MurupolkuService', function($scope, MurupolkuService) {
    $scope.murupolku = MurupolkuService.murupolku;
    $scope.isHidden = MurupolkuService.isHidden;
}]);