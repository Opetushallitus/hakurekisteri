'use strict';

var app = angular.module('myApp', ['ngRoute', 'ngResource', 'ui.bootstrap', 'ngUpload', 'ngSanitize']);

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

app.factory('MurupolkuService', function() {
    var murupolku = [];
    var hide = false;
    return {
        murupolku: murupolku,
        addToMurupolku: function(item, reset) {
            if (reset) murupolku.length = 0;
            murupolku.push(item);
            hide = false;
        },
        hideMurupolku: function() {
            hide = true;
        },
        isHidden: function() {
            return hide;
        }
    };
});