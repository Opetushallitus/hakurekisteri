'use strict';

app.config(function ($locationProvider, $routeProvider) {
    $routeProvider.when('/opiskelijat', {
        templateUrl: 'templates/opiskelijat',
        controller: 'OpiskelijatCtrl'
    });
    $routeProvider.when('/muokkaa/:henkiloOid', {
        templateUrl: 'templates/muokkaa',
        controller: 'MuokkaaCtrl'
    });
    $routeProvider.when('/eihakeneet', {
        templateUrl: 'templates/eihakeneet',
        controller: 'EihakeneetCtrl'
    });
    $routeProvider.when('/tiedonsiirto/lahetys', {
        templateUrl: 'templates/tiedonsiirto',
        controller: 'TiedonsiirtoCtrl'
    });
    $routeProvider.when('/tiedonsiirto/hakeneet', {
        templateUrl: 'templates/hakeneet',
        controller: 'HakeneetCtrl',
        resolve: {
            aste: function() { return "toinenaste" }
        }
    });
    $routeProvider.when('/tiedonsiirto', {
        redirectTo: '/tiedonsiirto/lahetys'
    });
    $routeProvider.when('/tiedonsiirto/kkhakeneet', {
        templateUrl: 'templates/hakeneet?aste=kk',
        controller: 'HakeneetCtrl',
        controllerAs: "KkHakeneetCtrl",
        resolve: {
            aste: function() { return "kk" }
        }
    });
    $routeProvider.otherwise({redirectTo: '/opiskelijat'});
    $locationProvider.html5Mode(false);
});

app.run(function($http, $log, MessageService) {
    $http.get(henkiloServiceUrl + '/buildversion.txt?auth')
        .success(function() {
            $log.debug("called authentication-service successfully")
        })
        .error(function() {
            MessageService.addMessage({
                type: "danger",
                messageKey: "suoritusrekisteri.opiskelijat.henkiloeiyhteytta",
                message: "Henkilöpalveluun ei juuri nyt saada yhteyttä.",
                descriptionKey: "suoritusrekisteri.opiskelijat.henkiloyrita",
                description: "Yritä hetken kuluttua uudelleen."
            })
        })
});