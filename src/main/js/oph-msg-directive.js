// NOTE! this directive is used from multiple apps (tiedonsiirto+suoritusrekisteri)

// localisation
app.directive('ophMsg', function($log, $http) {

    function getLang() {
        var lang;
        for (var i = 0; i < localisationMyroles.length; i++) {
            if (localisationMyroles[i].indexOf('LANG_') == 0) {
                lang = localisationMyroles[i].substring(5).toLowerCase();
            }
        }
        lang = lang ? lang : (navigator.language || navigator.userLanguage).substr(0, 2).toLowerCase();
        if (!lang || ["fi","sv","en"].indexOf(lang) == -1) {
            lang = "fi";
        }
        return lang;
    }

    function addTranslation(msgKey, lang, elemText, oldTranslation) {
        var allowEmptyTranslationUpdate = false; // voi laittaa päälle alustavan kääntämisen ajaksi
//        $log.info("addTranslation, key: "+msgKey+", lang: "+lang+", elemText: "+elemText+", allowEmptyTranslationUpdate: "+allowEmptyTranslationUpdate+", old: "+oldTranslation);
        if (!oldTranslation || allowEmptyTranslationUpdate && (!oldTranslation.value || $.trim(oldTranslation).length == 0)) { // dont update existing translation
            //var createValue = lang == "fi" ? elemText : ""; // suomenkielisille käännöksille oletuksena elementin sisältöteksti, muut käännökset tyhjiksi
            var createValue = elemText; // kaikille käännöksille oletuksena elementin sisältöteksti
            var data = { "value": createValue, "key": msgKey, "locale": lang, "category": msgCategory };
            $.ajax({
                type: oldTranslation ? "PUT" : "POST",
                url: localisationBackend + (oldTranslation ? "/"+oldTranslation.id : ""),
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

    function getTranslation(msgKey, lang, elemText) {

        /*
        // note: devkoodia kielistyksien lisäämiseksi lokalisointipalveluun
        function tempregexp(key, txt) {
//            translations[key] = {};
//            translations[key][lang]={value: txt};
            addTranslations(key, txt, null);
        }
        tempregexp(
            "cvc-complex-type.*?\'(.*?)\'.*:(.*?)}\' is expected.",
            "elementti $1 > $2 puuttuu"
        );
        tempregexp(
            "cvc-pattern-valid: Value \'(.*?)\'.*?\'(.*?)\' for type \'(.*?)\'.*",
            "$3 -elementin arvo '$1' ei täsmää ehdon kanssa: $2"
        );
        tempregexp(
            "cvc-enumeration-valid: Value \'(.*?)\'.*?\'(.*?)\'.*?\'(.*?)\'.*?\'(.*?)\'.*",
            "$4 -elementin arvo '$1' ei ole sallittu, pitää olla joku näistä: $2"
        );
        */

        /*
         Korvataan elementin sisältö regexpillä jos ophMsg avaimenea 'regexp', esim:
            directiivi:             <span oph-msg="regexp">...</span>
            html elementin sisältö: cvc-complex-type.2.4.b: The content of element 'ROW' is not complete. One of '{"http://service.henkilo.sade.vm.fi/types/perusopetus/henkilotiedot":LUOKKATASO}' is expected.
            lokalisointi key:       cvc-complex-type.*?'(.*?)'.*:(.*?)}' is expected.
            lokalisointi teksti:    $1 > $2 puuttuu
            tuottaa käännöksen:     ROW > LUOKKATASO puuttuu
        Huom! toimii myös normikäännöksille
        */
        if (msgKey=="regexp") {
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

        /////////////////////////////////////////////////

        var xLangs = translations[msgKey];
        if (!xLangs) { // ei käännöksiä millekään kielelle -> luodaan kaikille kielille
            addTranslations(msgKey, elemText);
            return "["+msgKey+"-NA]"
        }
        var oldTranslation = xLangs[lang];
        var x = oldTranslation.value;
        if (x === "" || !x) { // ko kielen käännöstä ei löydy, koitetaan luoda silti kaikki, muut luonnit ei mene läpi jos käännös olemassa jo jollain kielellä
            addTranslations(msgKey, elemText, oldTranslation);
            return "["+msgKey+"-"+lang+"]"
        }

        $log.debug("getTranslation, lang: "+lang+", key: "+msgKey+" => "+x);
        return x;
    }

    function loadMessages(callback) {
        // huomaa cachen käyttö, myöhemmät kutsut tapahtuvat samantien
        $http.get(msgResource, { cache: true}).success(function (data) {
            $http.get(backendUrl+"/cas/myroles", { cache: true}).success(function (myroles) {
                // init stuff
                if (!translations.inited) {
                    localisationMyroles = myroles;
                    lang = getLang();
                    for (var i = 0; i < data.length; i++) {
                        var t = data[i];
                        if (!translations[t.key]) translations[t.key] = [];
                        translations[t.key][t.locale] = t;
                    }
                    translations.inited=true;
                    $log.info("localisations inited, lang: "+lang+", localisationBackend: "+localisationBackend+", translations: "+translations.length);
                }

                // get msg
                if (callback) callback();
            });
        });
    }

    // check we have msgCategory defined
    if (window.msgCategory === undefined) {
        $log.error("ERROR! msgCategory global variable not defined!!!");
        return;
    }
    // init vars
    var backendUrl = location.host.indexOf('localhost') == 0 ? "https://itest-virkailija.oph.ware.fi" : ""; // use itest backend in local dev
    var localisationBackend = backendUrl + "/lokalisointi/cxf/rest/v1/localisation";
    var msgResource = localisationBackend + "?category="+msgCategory;
    var localisationMyroles = [];
    var lang = "fi";
    var translations = {inited:false};
    $log.info("backend: "+backendUrl);

    // also publish ophMsg function so we can call it programmatically from inside controllers - NOTE! saman varmaan voisi tehdä nätimminkin
    window.globalInitOphMsg = function(callback){
        loadMessages(function () {
            callback();
        });
    };
    window.globalGetOphMsg = function(msgKey,defaultText){
        if (!translations.inited) {
            $log.error("translations not inited, globalGetOphMsg must be called after globalInitOphMsg, returning key "+msgKey);
            return msgKey;
        } else {
            return getTranslation(msgKey, lang, defaultText ? defaultText : msgKey);
        }
    };

    return {
        scope: {
            msgKey: '@ophMsg'
        },
        link: function($scope, element, attrs) {
            attrs.$observe('ophMsg', function(msgKey) { // pitää käyttää observia jotta dynaamisissa tapauksissakin toimii (oph-msg -attribuutti muuttuu sivun latauksen jälkeen)
                $scope.msgKey = msgKey;
                loadMessages(function () {
                    if ($scope.msgKey.indexOf(msgCategory) == 0 || $scope.msgKey=='regexp') {
                        element.text(getTranslation($scope.msgKey, lang, element.text()));
                    } else {
                        $log.warn("localisation directive, key doesn't start with the category!, cat: "+msgCategory+", key: "+$scope.msgKey+", element:");
//                        $log.info(element);
                    }
                });
            });
        }
    };
});
