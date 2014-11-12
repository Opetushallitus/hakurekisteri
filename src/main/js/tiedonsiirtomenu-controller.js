'use strict';

app.controller('TiedonsiirtomenuCtrl', ['$scope', '$location', function($scope, $location) {
    $scope.isActive = function(path) {
        return path === $location.path()
    };
}]);