'use strict';

var app = angular.module('myApp', ['ngRoute', 'ngResource', 'ui.bootstrap'])
    .config(function ($locationProvider, $routeProvider) {
        $routeProvider.when('/opiskelijat', {templateUrl: 'templates/opiskelijat', controller: OpiskelijatCtrl});
        $routeProvider.when('/muokkaa/:henkiloOid', {templateUrl: 'templates/muokkaa', controller: MuokkaaCtrl});
        $routeProvider.when('/eihakeneet', {templateUrl: 'templates/eihakeneet', controller: EihakeneetCtrl});
        $routeProvider.otherwise({redirectTo: '/opiskelijat'});
        $locationProvider.html5Mode(false);
    });

app.factory('Henkilot', function($resource) {
    return $resource("rest/v1/henkilot/:henkiloId", {henkiloId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 55000},
        save: {method: "POST", timeout: 15000}
    });
});

app.factory('Opiskelijat', function($resource) {
    return $resource("rest/v1/opiskelijat/:opiskelijaId", {opiskelijaId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 55000},
        save: {method: "POST", timeout: 15000}
    });
});

app.factory('Suoritukset', function($resource) {
    return $resource("rest/v1/suoritukset/:suoritusId", {suoritusId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 55000},
        save: {method: "POST", timeout: 15000}
    });
});


