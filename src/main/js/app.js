'use strict';

var app = angular.module('myApp', ['ngRoute', 'ngResource', 'ui.bootstrap'])
    .config(function ($locationProvider, $routeProvider) {
        $routeProvider.when('/opiskelijat', {templateUrl: 'templates/opiskelijat', controller: OpiskelijatCtrl});
        $routeProvider.when('/muokkaa/:henkiloOid', {templateUrl: 'templates/muokkaa', controller: MuokkaaCtrl});
        $routeProvider.when('/eihakeneet', {templateUrl: 'templates/eihakeneet', controller: EihakeneetCtrl});
        $routeProvider.otherwise({redirectTo: '/opiskelijat'});
        $locationProvider.html5Mode(false);
    });

app.factory('Henkilo', function($resource) {
    return $resource(henkiloServiceUrl + "/resources/henkilo/:oidHenkilo", {oidHenkilo: "@oidHenkilo"}, {
        get: {method: "GET", timeout: 3000},
        save: {method: "PUT", timeout: 5000}
    });
});

app.factory('Opiskelijat', function($resource) {
    return $resource("rest/v1/opiskelijat/:opiskelijaId", {opiskelijaId: "@id"}, {
        query: {method: "GET", isArray: true, timeout: 3000},
        save: {method: "POST", timeout: 5000}
    });
});

app.factory('Suoritukset', function($resource) {
    return $resource("rest/v1/suoritukset/:suoritusId", {suoritusId: "@id"}, {
        query: {method: "GET", isArray: true, timeout: 3000},
        save: {method: "POST", timeout: 5000}
    });
});


