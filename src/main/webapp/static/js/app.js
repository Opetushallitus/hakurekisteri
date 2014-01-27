'use strict';

// Declare app level module which depends on filters, and services
var app = angular.module('myApp', ['ngRoute', 'ngResource'])
    .config(function ($locationProvider) {
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