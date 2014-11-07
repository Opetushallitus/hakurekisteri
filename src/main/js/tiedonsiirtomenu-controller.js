function TiedonsiirtomenuCtrl($scope, $location) {
    $scope.isActive = function(path) {
        return path === $location.path()
    };
}