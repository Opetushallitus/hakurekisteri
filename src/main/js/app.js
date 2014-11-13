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

app.factory('MessageService', function() {
    var messages = [];
    return {
        messages: messages,
        addMessage: function(message, clear) {
            if (clear) messages.length = 0;
            messages.push(message);
        },
        removeMessage: function(message) {
            var index = messages.indexOf(message);
            if (index !== -1) messages.splice(index, 1);
        },
        clearMessages: function() {
            messages.length = 0;
        }
    }
});

app.filter('hilight', function() {
    return function (input, query) {
        return input.replace(new RegExp('('+ query + ')', 'gi'), '<strong>$1</strong>');
    }
});

app.directive('messages', function() {
    return {
        controller: function($scope, MessageService) {
            $scope.messages = MessageService.messages;
            $scope.removeMessage = MessageService.removeMessage;
        },
        templateUrl: 'templates/messages'
    }
});

app.directive('tiedonsiirtomenu', function() {
    return {
        controller: function($scope, $location) {
            $scope.isActive = function(path) {
                return path === $location.path()
            };
        },
        templateUrl: 'templates/tiedonsiirtomenu'
    }
});