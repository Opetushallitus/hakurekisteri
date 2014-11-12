'use strict';

app.controller('MurupolkuCtrl', ['$scope', 'MurupolkuService', function($scope, MurupolkuService) {
    $scope.murupolku = MurupolkuService.murupolku;
}]);