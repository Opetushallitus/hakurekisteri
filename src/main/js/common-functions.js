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
var tarjontaServiceUrl = getBaseUrl() + "/tarjonta-service";

var komo = {
    ulkomainen: "1.2.246.562.13.86722481404",
    peruskoulu: "1.2.246.562.13.62959769647",
    lisaopetus: "1.2.246.562.5.2013112814572435044876",
    ammattistartti: "1.2.246.562.5.2013112814572438136372",
    maahanmuuttaja: "1.2.246.562.5.2013112814572441001730",
    valmentava: "1.2.246.562.5.2013112814572435755085",
    ylioppilastutkinto: "1.2.246.562.5.2013061010184237348007"
};
var ylioppilastutkintolautakunta = "1.2.246.562.10.43628088406";

String.prototype.hashCode = function() {
    var hash = 0;
    if (this.length == 0) return hash;
    for (var i = 0; i < this.length; i++) {
        hash = ((hash << 5) - hash) + this.charCodeAt(i);
        hash = hash & hash;
    }
    return hash;
};

if (!Object.keys) Object.keys = function(o) {
    if (o !== Object(o))
        throw new TypeError('Object.keys called on a non-object');
    var k=[],p;
    for (p in o) if (Object.prototype.hasOwnProperty.call(o,p)) k.push(p);
    return k;
};

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

if(!Array.isArray)
    Array.isArray = function(arg) {
        return Object.prototype.toString.call(arg) === '[object Array]';
    };


function getOrganisaatio($http, organisaatioOid, successCallback, errorCallback) {
    $http.get(organisaatioServiceUrl + '/rest/organisaatio/' + encodeURIComponent(organisaatioOid), {cache: true})
        .success(successCallback)
        .error(errorCallback)
}

function getKoulutusNimi($http, koulutusUri, successCallback) {
    $http.get(koodistoServiceUrl + '/rest/json/koulutus/koodi/' + encodeURIComponent(koulutusUri), {cache: true})
        .success(function(koodi) {
            if (koodi.metadata) {
                for (var i = 0; i < koodi.metadata.length; i++) {
                    var meta = koodi.metadata[i];
                    if (meta.kieli === 'FI') {
                        return successCallback(meta.nimi);
                    }
                }
            }
            return successCallback("");
        })
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

function getKoodistoAsOptionArray($http, koodisto, kielikoodi, options, valueFromField) {
    $http.get(getBaseUrl() + '/koodisto-service/rest/json/' + encodeURIComponent(koodisto) + '/koodi', {cache: true})
        .success(function(koodisto) {
            angular.forEach(koodisto, function(koodi) {
                metas: for (var j = 0; j < koodi.metadata.length; j++) {
                    var meta = koodi.metadata[j];
                    if (meta.kieli.toLowerCase() === kielikoodi.toLowerCase()) {
                        var value = koodi.koodiUri + '#' + koodi.versio;
                        if (valueFromField === 'nimi')
                            value = meta.nimi;
                        if (valueFromField === 'koodiArvo')
                            value = koodi.koodiArvo;
                        options.push({
                            value: value,
                            text: meta.nimi
                        });
                        break metas;
                    }
                }
            });
            options.sort(function(a, b) {
                if (a.text === b.text) return 0;
                return a.text < b.text ? -1 : 1;
            });
        });
}

function parseFinDate(d) {
    return (d && d.match(/[0-3][0-9]\.[0-1][0-9]\.[0-9]{4}/)) ? new Date(parseInt(d.substr(6, 4), 10), parseInt(d.substr(3, 2), 10) - 1, parseInt(d.substr(0, 2), 10)) : null;
}

function sortByFinDateDesc(a, b) {
    var aDate = parseFinDate(a);
    if (!aDate) aDate = new Date(1000, 0, 1);
    var bDate = parseFinDate(b);
    if (!bDate) bDate = new Date(1000, 0, 1);
    return aDate > bDate ? -1 : aDate < bDate ? 1 : 0;
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