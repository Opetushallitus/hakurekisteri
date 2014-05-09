function MurupolkuCtrl($rootScope) {
    initMurupolku();

    function initMurupolku() {
        $rootScope.hideMuru = false;
        $rootScope.murupolku = [];
        $rootScope.murupolku.push({href: "#/", icon: "icon-breadcrumb-home"});
    }

    $rootScope.addToMurupolku = function(element, reset) {
        if (!$rootScope.murupolku || reset) {
            initMurupolku();
        }
        $rootScope.murupolku.push(element);
    };

    $rootScope.hideMurupolku = function() {
        $rootScope.hideMuru = true;
    };
}