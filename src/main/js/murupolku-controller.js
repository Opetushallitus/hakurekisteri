function MurupolkuCtrl($rootScope) {
    $rootScope.murupolku = [];

    $rootScope.addToMurupolku = function(element, reset) {
        if (reset) {
            $rootScope.murupolku.length = 0;
        }
        $rootScope.murupolku.push(element);
    };
}