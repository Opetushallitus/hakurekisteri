'use strict';

// Declare app level module which depends on filters, and services
var app = angular.module('myApp', ['ngRoute', 'ngResource', 'ui.bootstrap'])
    .config(function ($locationProvider, $routeProvider) {
        $routeProvider.when('/suoritukset', {templateUrl: 'templates/suoritukset', controller: SuorituksetCtrl});
        $routeProvider.when('/muokkaa/:henkilo', {templateUrl: 'templates/muokkaa', controller: MuokkaaCtrl});
        $routeProvider.otherwise({redirectTo: '/suoritukset'});
        $locationProvider.html5Mode(false);
    });

app.factory('Henkilo', function($resource) {
    return $resource("/authentication-service/resources/henkilo/:henkiloOid", {}, {
        get: {method: "GET", isArray: false, cache: true, timeout: 3000}
    });
});

app.factory('Organisaatio', function($resource) {
    return $resource("/organisaatio-service/rest/organisaatio/:organisaatioOid", {}, {
        get: {method: "GET", isArray: false, cache: true, timeout: 3000}
    });
});

app.factory('MyRoles', function($resource) {
    return $resource("/cas/myroles", {}, {
        get: {method: "GET", isArray: false, cache: true, timeout: 3000}
    });
});
