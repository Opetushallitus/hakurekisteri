'use strict';

var app = angular.module('myApp', ['ngRoute', 'ngResource', 'ui.bootstrap', 'ngUpload', 'ngSanitize'])
    .config(function ($locationProvider, $routeProvider) {
        $routeProvider.when('/opiskelijat', {
            templateUrl: 'templates/opiskelijat',
            controller: OpiskelijatCtrl
        });
        $routeProvider.when('/muokkaa/:henkiloOid', {
            templateUrl: 'templates/muokkaa',
            controller: MuokkaaCtrl
        });
        $routeProvider.when('/eihakeneet', {
            templateUrl: 'templates/eihakeneet',
            controller: EihakeneetCtrl
        });
        $routeProvider.when('/tiedonsiirto/lahetys', {
            templateUrl: 'templates/tiedonsiirto',
            controller: TiedonsiirtoCtrl
        });
        $routeProvider.when('/tiedonsiirto/hakeneet', {
            templateUrl: 'templates/hakeneet',
            controller: HakeneetCtrl,
            resolve: {
                aste: function() { return "toinenaste" }
            }
        });
        $routeProvider.when('/tiedonsiirto', {
            redirectTo: '/tiedonsiirto/lahetys'
        });
        $routeProvider.when('/tiedonsiirto/kkhakeneet', {
            templateUrl: 'templates/hakeneet?aste=kk',
            controller: HakeneetCtrl,
            controllerAs: "KkHakeneetCtrl",
            resolve: {
                aste: function() { return "kk" }
            }
        });
        $routeProvider.otherwise({redirectTo: '/opiskelijat'});
        $locationProvider.html5Mode(false);
    });

app.factory('Opiskelijat', function($resource) {
    return $resource("rest/v1/opiskelijat/:opiskelijaId", {opiskelijaId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 55000},
        save: {method: "POST", timeout: 15000},
        remove: {method: "DELETE", timeout: 15000}
    });
});

app.factory('Suoritukset', function($resource) {
    return $resource("rest/v1/suoritukset/:suoritusId", {suoritusId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 55000},
        save: {method: "POST", timeout: 15000},
        remove: {method: "DELETE", timeout: 15000}
    });
});

app.factory('Opiskeluoikeudet', function($resource) {
    return $resource("rest/v1/opiskeluoikeudet/:opiskeluoikeusId", {opiskeluoikeusId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 55000},
        save: {method: "POST", timeout: 15000},
        remove: {method: "DELETE", timeout: 15000}
    });
});

app.factory('Arvosanat', function($resource) {
    return $resource("rest/v1/arvosanat/:arvosanaId", {arvosanaId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 55000},
        save: {method: "POST", timeout: 30000},
        remove: {method: "DELETE", timeout: 15000}
    });
});

app.filter('hilight', function() {
    return function (input, query) {
        return input.replace(new RegExp('('+ query + ')', 'gi'), '<strong>$1</strong>');
    }
});
