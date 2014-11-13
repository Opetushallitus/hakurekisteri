'use strict';

app.factory('LokalisointiService', ['$log', '$http', function($log, $http) {
    var backendUrl = location.host.indexOf('localhost') == 0 ? "https://itest-virkailija.oph.ware.fi" : "";
    var localisationBackend = backendUrl + "/lokalisointi/cxf/rest/v1/localisation";
    var msgResource = localisationBackend + "?category=" + msgCategory;
    var localisationMyroles = [];
    var translations = { inited: false };
    $log.info("backend: " + backendUrl);

    var service = { lang: "fi" };

    function getLang() {
        var lang;
        for (var i = 0; i < localisationMyroles.length; i++) {
            if (localisationMyroles[i].indexOf('LANG_') == 0) {
                lang = localisationMyroles[i].substring(5).toLowerCase();
            }
        }
        lang = lang ? lang : (navigator.language || navigator.userLanguage).substr(0, 2).toLowerCase();
        if (!lang || ["fi", "sv", "en"].indexOf(lang) == -1) {
            lang = "fi";
        }
        return lang;
    }

    service.loadMessages = function(callback) {
        $http.get(msgResource, { cache: true }).success(function (data) {
            $http.get(backendUrl + "/cas/myroles", { cache: true }).success(function (myroles) {
                if (!translations.inited) {
                    localisationMyroles = myroles;
                    service.lang = getLang();
                    for (var i = 0; i < data.length; i++) {
                        var t = data[i];
                        if (!translations[t.key]) translations[t.key] = [];
                        translations[t.key][t.locale] = t;
                    }
                    translations.inited = true;
                    $log.info("localisations inited, lang: " + service.lang + ", localisationBackend: " + localisationBackend + ", translations: " + translations.length);
                }

                if (callback) callback();
            });
        });
    };

    function addTranslation(msgKey, lang, elemText, oldTranslation) {
        var allowEmptyTranslationUpdate = false;
        if (!oldTranslation || allowEmptyTranslationUpdate && (!oldTranslation.value || $.trim(oldTranslation).length == 0)) {
            var data = { "value": elemText, "key": msgKey, "locale": lang, "category": msgCategory };
            $.ajax({
                type: oldTranslation ? "PUT" : "POST",
                url: localisationBackend + (oldTranslation ? "/" + oldTranslation.id : ""),
                data: JSON.stringify(data),
                contentType: 'application/json; charset=UTF-8',
                dataType: "json"
            });
        }
    }

    function addTranslations(msgKey, elemText, oldTranslation) {
        //$.get(localisationBackend + "/authorize");
        addTranslation(msgKey, "fi", elemText, oldTranslation);
        addTranslation(msgKey, "sv", elemText, oldTranslation);
        addTranslation(msgKey, "en", elemText, oldTranslation);
    }

    service.getTranslation = function(msgKey, lang, elemText) {
        /*
         Korvataan elementin sisältö regexpillä jos ophMsg avaimenea 'regexp', esim:
         directiivi:             <span oph-msg="regexp">...</span>
         html elementin sisältö: cvc-complex-type.2.4.b: The content of element 'ROW' is not complete. One of '{"http://service.henkilo.sade.vm.fi/types/perusopetus/henkilotiedot":LUOKKATASO}' is expected.
         lokalisointi key:       cvc-complex-type.*?'(.*?)'.*:(.*?)}' is expected.
         lokalisointi teksti:    $1 > $2 puuttuu
         tuottaa käännöksen:     ROW > LUOKKATASO puuttuu
         Huom! toimii myös normikäännöksille
         */
        if (msgKey == "regexp") {
            for (var key in translations) {
                var translation = translations[key];
                if (translation[lang]) {
                    var text = translation[lang].value;
                    var regExp = new RegExp(key);
                    //$log.debug("oph msg regexp, key: "+key+", text: "+text);
                    if (elemText.match(regExp)) {
                        return elemText.replace(regExp, text)
                    }
                }
            }
            $log.warn("no matching regexp translation for: "+elemText);
            return elemText;
        }

        var xLangs = translations[msgKey];
        if (!xLangs) {
            addTranslations(msgKey, elemText);
            return "[" + msgKey + "-NA]";
        }
        var oldTranslation = xLangs[lang];
        var x = oldTranslation.value;
        if (x === "" || !x) {
            addTranslations(msgKey, elemText, oldTranslation);
            return "[" + msgKey + "-" + lang + "]";
        }

        $log.debug("getTranslation, lang: " + lang + ", key: " + msgKey + " => " + x);
        return x;
    };

    if (window.msgCategory === undefined) {
        $log.error("ERROR! msgCategory global variable not defined!!!");
        return;
    }

    window.globalInitOphMsg = function(callback){
        service.loadMessages(function () {
            callback();
        });
    };
    window.globalGetOphMsg = function(msgKey, defaultText){
        if (translations.inited) {
            return service.getTranslation(msgKey, service.lang, defaultText ? defaultText : msgKey);
        } else {
            $log.error("translations not inited, globalGetOphMsg must be called after globalInitOphMsg, returning key " + msgKey);
            return msgKey;
        }
    };

    return service;
}]);

app.directive('ophMsg', ['$log', 'LokalisointiService', function($log, LokalisointiService) {
    return {
        scope: {
            msgKey: '@ophMsg'
        },
        link: function($scope, element, attrs) {
            attrs.$observe('ophMsg', function(msgKey) {
                $scope.msgKey = msgKey;
                LokalisointiService.loadMessages(function () {
                    if ($scope.msgKey.indexOf(msgCategory) == 0 || $scope.msgKey == 'regexp') {
                        element.text(LokalisointiService.getTranslation($scope.msgKey, LokalisointiService.lang, element.text()));
                    } else {
                        $log.warn("localisation directive, key doesn't start with the category!, cat: "
                            + msgCategory + ", key: " + $scope.msgKey + ", element:");
                    }
                });
            });
        }
    };
}]);

app.run(function($log) {
    if (window.globalInitOphMsg) window.globalInitOphMsg(function() { $log.info("messages loaded") });
});