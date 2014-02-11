'use strict';

// Declare app level module which depends on filters, and services
var app = angular.module('myApp', ['ngRoute', 'ngResource', 'ui.bootstrap'])
    .config(function ($locationProvider, $routeProvider) {
        $routeProvider.when('/opiskelijat', {templateUrl: 'templates/opiskelijat', controller: OpiskelijatCtrl});
        $routeProvider.when('/muokkaa/:henkiloOid', {templateUrl: 'templates/muokkaa', controller: MuokkaaCtrl});
        $routeProvider.otherwise({redirectTo: '/opiskelijat'});
        $locationProvider.html5Mode(false);
    });

app.factory('Henkilo', function($resource) {
    return $resource("/authentication-service/resources/henkilo/:henkiloOid", {henkiloOid: "@henkiloOid"}, {
        get: {method: "GET", cache: true, timeout: 3000},
        save: {method: "PUT"}
    });
});

app.factory('HenkiloByHetu', function($resource) {
    return $resource("/authentication-service/resources/henkilo/byHetu/:hetu", {hetu: "@hetu"}, {
        get: {method: "GET", cache: true, timeout: 3000}
    });
});

app.factory('Organisaatio', function($resource) {
    return $resource("/organisaatio-service/rest/organisaatio/:organisaatioOid", {organisaatioOid: "@organisaatioOid"}, {
        get: {method: "GET", cache: true, timeout: 3000}
    });
});

app.factory('Opiskelijat', function($resource) {
    return $resource("rest/v1/opiskelijat/:opiskelijaId", {opiskelijaId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 3000}
    });
});

app.factory('Suoritukset', function($resource) {
    return $resource("rest/v1/suoritukset/:suoritusId", {suoritusId: "@id"}, {
        query: {method: "GET", isArray: true, cache: false, timeout: 3000}
    });
});

app.factory('Koodi', function($resource) {
    return $resource("/koodisto-service/rest/json/:koodisto/koodi/:koodiUri", {koodiUri: "@koodiUri"}, {
        get: {method: "GET", cache: true, timeout: 3000}
    });
});

app.factory('Koodisto', function($resource) {
    return $resource("/koodisto-service/rest/json/:koodisto/koodi", {koodisto: "@koodisto"}, {
        query: {method: "GET", isArray: true, cache: true, timeout: 3000}
    });
});

