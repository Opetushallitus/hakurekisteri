function MurupolkuCtrl($rootScope) {
    $rootScope.hideMuru = false;
    $rootScope.murupolku = [];

    $rootScope.addToMurupolku = function(element, reset) {
        if (reset) {
            $rootScope.murupolku = [];
        }
        $rootScope.murupolku.push(element);
    };

    $rootScope.hideMurupolku = function() {
        $rootScope.hideMuru = true;
    };
}