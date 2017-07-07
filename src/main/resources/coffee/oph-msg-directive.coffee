app.factory "LokalisointiService", [
  "$log"
  "$http"
  ($log, $http) ->
    getLang = -> window.userLang

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
      if (elemText)
        addTranslation msgKey, "fi", elemText, oldTranslation
        addTranslation msgKey, "sv", elemText, oldTranslation
        addTranslation msgKey, "en", elemText, oldTranslation

    translations = inited: false

    service =
      lang: getLang()
    service.loadMessages = (callback) ->
      $http.get(window.url("lokalisointi.category", msgCategory),
        cache: true
      ).success (data) ->
        unless translations.inited
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

    service.getTranslation = (msgKey, lang = getLang(), elemText) ->
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
        return elemText
      oldTranslation = xLangs[lang]
      if !oldTranslation
        return elemText
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
            element.text LokalisointiService.getTranslation($scope.msgKey, LokalisointiService.lang, element.text())
            return
          return
        return
    }
]
app.run [
  "$log"
  "LokalisointiService"
  "$rootScope"
  ($log, LokalisointiService, $rootScope) ->
    $rootScope.translate = LokalisointiService.getTranslation
    if window.globalInitOphMsg
      window.globalInitOphMsg ->
]