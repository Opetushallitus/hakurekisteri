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
    return $resource("/authentication-service/resources/henkilo/:henkiloOid", {_cacheEnv: getCacheEnvKey()}, {
        getCached: {method: "GET", isArray: false, cache: true, timeout: 3000},
        get: {method: "GET", isArray: false, cache: false, timeout: 3000}
    });
});

app.factory('Organisaatio', function($resource) {
    return $resource("/organisaatio-service/rest/organisaatio/:organisaatioOid", {_cacheEnv: getCacheEnvKey()}, {
        getCached: {method: "GET", isArray: false, cache: true, timeout: 3000}
    });
});

app.factory('MyRoles', function($resource) {
    return $resource("/cas/myroles", {_cacheEnv: getCacheEnvKey()}, {
        getCached: {method: "GET", isArray: true, cache: true, timeout: 3000}
    });
});

app.factory('Opiskelijat', function($resource) {
    return $resource("rest/v1/opiskelijat", {}, {
        get: {method: "GET", isArray: true, cache: false, timeout: 3000}
    });
});

app.factory('Suoritukset', function($resource) {
    return $resource("rest/v1/suoritukset", {}, {
        get: {method: "GET", isArray: true, cache: false, timeout: 3000}
    });
});

app.factory('Koodisto', function($resource) {
    return $resource("/koodisto-service/rest/json/:koodisto/koodi/:koodiUri", {_cacheEnv: getCacheEnvKey()}, {
        getCached: {method: "GET", isArray: false, cache: true, timeout: 3000}
    });
});

String.prototype.hashCode = function() {
    var hash = 0;
    if (this.length == 0) return hash;
    for (var i = 0; i < this.length; i++) {
        hash = ((hash << 5) - hash) + this.charCodeAt(i);
        hash = hash & hash;
    }
    return hash;
};

function getCacheEnvKey() {
    return encodeURIComponent(location.hostname.hashCode());
}

