'use strict';

var msgCategory = "suoritusrekisteri";

var henkiloServiceUrl = "/authentication-service";
var organisaatioServiceUrl = "/organisaatio-service";
var koodistoServiceUrl = "/koodisto-service";
var hakuAppServiceUrl = "/haku-app";

Array.prototype.diff = function(a) {
    return this.filter(function(i) { return a.indexOf(i) < 0; });
};

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

function getPostitoimipaikka($http, postinumero, successCallback, errorCallback) {
    $http.get(koodistoServiceUrl + '/rest/json/posti/koodi/posti_' + encodeURIComponent(postinumero), {cache: true})
        .success(successCallback)
        .error(errorCallback);
}

function getKoodistoAsOptionArray($http, koodisto, kielikoodi, options) {
    $http.get(koodistoServiceUrl + '/rest/json/' + encodeURIComponent(koodisto) + '/koodi', {cache: true})
        .success(function(koodisto) {
            angular.forEach(koodisto, function(koodi) {
                metas: for (var j = 0; j < koodi.metadata.length; j++) {
                    var meta = koodi.metadata[j];
                    if (meta.kieli.toLowerCase() === kielikoodi.toLowerCase()) {
                        options.push({
                            value: koodi.koodiArvo,
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
