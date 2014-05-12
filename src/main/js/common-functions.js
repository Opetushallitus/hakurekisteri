'use strict';

var msgCategory = "suoritusrekisteri";

function getBaseUrl() {
    if (location.hostname === 'localhost') return 'https://itest-virkailija.oph.ware.fi';
    return '';
}

var henkiloServiceUrl = getBaseUrl() + "/authentication-service";
var organisaatioServiceUrl = getBaseUrl() + "/organisaatio-service";
var hakuAppServiceUrl = getBaseUrl() + "/haku-app";
var koodistoServiceUrl = getBaseUrl() + "/koodisto-service";

if (!Array.prototype.diff)
    Array.prototype.diff = function(a) {
        return this.filter(function(i) { return a.indexOf(i) < 0; });
    };

if (!Array.prototype.getUnique)
    Array.prototype.getUnique = function() {
        var u = {}, a = [];
        for(var i = 0, l = this.length; i < l; ++i){
            if(u.hasOwnProperty(this[i])) {
                continue;
            }
            a.push(this[i]);
            u[this[i]] = 1;
        }
        return a;
    };

function getOrganisaatio($http, organisaatioOid, successCallback, errorCallback) {
    $http.get(organisaatioServiceUrl + '/rest/organisaatio/' + encodeURIComponent(organisaatioOid), {cache: true})
        .success(successCallback)
        .error(errorCallback);
}

function authenticateToAuthenticationService($http, successCallback, errorCallback) {
    $http.get(henkiloServiceUrl + '/buildversion.txt?auth')
        .success(successCallback)
        .error(errorCallback);
}

function getOphMsg(key, def) {
    if (window.globalGetOphMsg) return window.globalGetOphMsg(key, def);
    else key;
}

function ensureConsoleMethods() {
    var method;
    var noop = function () {};
    var methods = [
        'assert', 'clear', 'count', 'debug', 'dir', 'dirxml', 'error',
        'exception', 'group', 'groupCollapsed', 'groupEnd', 'info', 'log',
        'markTimeline', 'profile', 'profileEnd', 'table', 'time', 'timeEnd',
        'timeStamp', 'trace', 'warn'
    ];
    var length = methods.length;
    var console = (window.console = window.console || {});

    while (length--) {
        method = methods[length];
        if (!console[method]) console[method] = noop;
    }
}

(function() {
    ensureConsoleMethods();

    if (window.globalInitOphMsg) window.globalInitOphMsg(function() {});
}());