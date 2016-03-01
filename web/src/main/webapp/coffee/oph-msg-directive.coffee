app.factory "LokalisointiService", [
  "$log"
  "$http"
  ($log, $http) ->
    getLang = ->
      lang = undefined
      i = 0
      while i < localisationMyroles.length
        lang = localisationMyroles[i].substring(5).toLowerCase()  if localisationMyroles[i].indexOf("LANG_") is 0
        i++
      lang = (if lang then lang else (navigator.language or navigator.userLanguage).substr(0, 2).toLowerCase())
      lang = "fi"  if not lang or [
        "fi"
        "sv"
        "en"
      ].indexOf(lang) is -1
      lang

    addTranslation = (msgKey, lang, elemText, oldTranslation) ->
      allowEmptyTranslationUpdate = false
      if not oldTranslation or allowEmptyTranslationUpdate and (not oldTranslation.value or $.trim(oldTranslation).length is 0)
        data =
          value: elemText
          key: msgKey
          locale: lang
          category: msgCategory

        $.ajax
          type: (if oldTranslation then "PUT" else "POST")
          url: (if oldTranslation then window.url("lokalisointi.update", oldTranslation.id) else window.url("lokalisointi.add"))
          data: JSON.stringify(data)
          contentType: "application/json; charset=UTF-8"
          dataType: "json"
      return

    addTranslations = (msgKey, elemText, oldTranslation) ->
      addTranslation msgKey, "fi", elemText, oldTranslation
      addTranslation msgKey, "sv", elemText, oldTranslation
      addTranslation msgKey, "en", elemText, oldTranslation
      return

    localisationMyroles = []
    translations = inited: false

    service =
      lang: "fi"
    service.loadMessages = (callback) ->
      $http.get(window.url("lokalisointi.category", msgCategory),
        cache: true
      ).success (data) ->
        $http.get(window.url("cas.myroles"),
          cache: true
        ).success (myroles) ->
          unless translations.inited
            localisationMyroles = myroles
            service.lang = getLang()
            i = 0

            while i < data.length
              t = data[i]
              translations[t.key] = []  unless translations[t.key]
              translations[t.key][t.locale] = t
              i++
            translations.inited = true
          callback()  if callback
          return
        return
      return

    service.getTranslation = (msgKey, lang, elemText) ->
      if msgKey is "regexp"
        for key of translations
          translation = translations[key]
          if translation[lang]
            text = translation[lang].value
            regExp = new RegExp(key)
            #$log.debug("oph msg regexp, key: "+key+", text: "+text);
            return elemText.replace(regExp, text)  if elemText.match(regExp)
        $log.warn "no matching regexp translation for: " + elemText
        return elemText
      xLangs = translations[msgKey]
      unless xLangs
        addTranslations msgKey, elemText
        return "[" + msgKey + "-NA]"
      oldTranslation = xLangs[lang]
      x = oldTranslation.value
      if x is "" or not x
        addTranslations msgKey, elemText, oldTranslation
        return "[" + msgKey + "-" + lang + "]"
      x

    if window.msgCategory is `undefined`
      window.msgCategory = msgCategory

    window.globalInitOphMsg = (callback) ->
      service.loadMessages ->
        callback()
        return
      return

    window.globalGetOphMsg = (msgKey, defaultText) ->
      if translations.inited
        service.getTranslation msgKey, service.lang, (if defaultText then defaultText else msgKey)
      else
        $log.error "translations not inited, globalGetOphMsg must be called after globalInitOphMsg, returning key " + msgKey
        msgKey

    return service
]

app.directive "ophMsg", [
  "$log"
  "LokalisointiService"
  ($log, LokalisointiService) ->
    return {
      scope:
        msgKey: "@ophMsg"
      link: ($scope, element, attrs) ->
        attrs.$observe "ophMsg", (msgKey) ->
          $scope.msgKey = msgKey
          LokalisointiService.loadMessages ->
            if $scope.msgKey.indexOf(msgCategory) is 0 or $scope.msgKey is "regexp"
              element.text LokalisointiService.getTranslation($scope.msgKey, LokalisointiService.lang, element.text())
            else
              $log.warn "localisation directive, key doesn't start with the category!, cat: " + msgCategory + ", key: " + $scope.msgKey + ", element:"
            return
          return
        return
    }
]
app.run [
  "$log"
  "LokalisointiService"
  ($log, LokalisointiService) ->
    if window.globalInitOphMsg
      window.globalInitOphMsg ->
]