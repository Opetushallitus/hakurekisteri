'use strict';

// Declare app level module which depends on filters, and services
var app = angular.module('myApp', ['ngRoute', 'ngResource'])
    .config(function ($routeProvider, $locationProvider) {
        //$routeProvider.when('/', {templateUrl: 'partials/home.html', controller: HomeCtrl});
        $routeProvider.when('/opiskeluoikeudet', {templateUrl: 'partials/opiskeluoikeudet.html', controller: OpiskeluoikeudetCtrl});
        //$routeProvider.when('/suoritukset', {templateUrl: 'partials/suoritukset.html', controller: SuorituksetCtrl});
        $routeProvider.otherwise({redirectTo: '/opiskeluoikeudet'});
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