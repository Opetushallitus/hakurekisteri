"use strict"

injector = angular.injector(['ng']);
$http = injector.get('$http');

callerIdHeaderValue = "1.2.246.562.10.00000000001.suoritusrekisteri.frontend"
window.opintopolku_caller_id = callerIdHeaderValue

$http.defaults.headers.common['Caller-Id'] = callerIdHeaderValue;
$.ajaxSetup({headers: {callerIdHeaderName: callerIdHeaderValue}});

plainUrls = undefined
window.urls.load("suoritusrekisteri-web-oph.json", {overrides: "rest/v1/properties"}).then ->
  $http.get(window.url("kouta-internal.auth.login")).then((res) -> console.log("Kouta login successful"))
  $http.get(window.url("cas.myroles"),
    cache: true
  ).success (myroles) ->
    window.myroles = myroles
    setUserLang(myroles)
    angular.element(document).ready ->
      angular.bootstrap(document, ['myApp'])
      plainUrls = window.urls().noEncode()

setUserLang = (myroles) ->
  [lang] = myroles.filter (key) -> key.substring(0, 5) == 'LANG_'

  if lang?
    lang = lang.substring(5)
  else
    lang = (navigator.language or navigator.userLanguage).substr(0, 2)

  lang = "fi" if not lang or [
    "fi"
    "sv"
    "en"
  ].indexOf(lang) is -1

  window.userLang = lang.toLowerCase()

app = angular.module "myApp", [
  "ngRoute"
  "ngResource"
  "ui.bootstrap"
  "ngFileUpload"
  "ngSanitize"
  "ngCookies"
  "angular-clipboard"
]

if (window.mocksOn)
  angular.module('myApp').requires.push('e2e-mocks')

app.factory "Opiskelijat", ($resource) ->
  $resource plainUrls.url("suoritusrekisteri.opiskelija", ":opiskelijaId"), { opiskelijaId: "@id" }, {
      query:
        method: "GET"
        isArray: true
        cache: false
        timeout: 55000

      save:
        method: "POST"
        timeout: 15000

      remove:
        method: "DELETE"
        timeout: 15000
  }


app.factory "Suoritukset", ($resource) ->
  $resource plainUrls.url("suoritusrekisteri.suoritus",":suoritusId"), { suoritusId: "@id" }, {
    query:
      method: "GET"
      isArray: true
      cache: false
      timeout: 55000
    save:
      method: "POST"
      timeout: 15000
    remove:
      method: "DELETE"
      timeout: 15000
  }


app.factory "Opiskeluoikeudet", ($resource) ->
  $resource plainUrls.url("suoritusrekisteri.opiskeluoikeus",":opiskeluoikeusId"), { opiskeluoikeusId: "@id" }, {
    query:
      method: "GET"
      isArray: true
      cache: false
      timeout: 55000

    save:
      method: "POST"
      timeout: 15000

    remove:
      method: "DELETE"
      timeout: 15000
  }

app.factory "Arvosanat", ($resource) ->
  $resource plainUrls.url("suoritusrekisteri.arvosana",":arvosanaId"), { arvosanaId: "@id" }, {
    query:
      method: "GET"
      isArray: true
      cache: false
      timeout: 55000

    save:
      method: "POST"
      timeout: 30000

    remove:
      method: "DELETE"
      timeout: 15000
  }

app.factory "RekisteriTiedot", ($resource) ->
  $resource plainUrls.url("suoritusrekisteri.rekisteritieto"), { }, {
    query:
      method: "GET"
      isArray: true
      cache: false
      timeout: 55000

    save:
      method: "POST"
      timeout: 30000

    remove:
      method: "DELETE"
      timeout: 15000
  }

app.factory "VirtaSuoritukset", ($resource) ->
  $resource plainUrls.url("suoritusrekisteri.virtasuoritukset", ":id"), { id: "@id" }, {
    query:
      method: "GET"
      isArray: false
      cache: true
      timeout: 30000
  }


app.factory "MessageService", ->
  messages = []
  return (
    messages: messages
    addMessage: (message, clear) ->
      if !message.descriptionKey? && !message.messageKey?
        console.error("Problem with message", message)
        throw new Error("Problem with message")
      messages.length = 0  if clear
      messages.push message
      that = this
      if message.type == "success"
        setTimeout ( ->
          that.removeMessage(message)
        ), 2000

    removeMessage: (message) ->
      index = messages.indexOf(message)
      messages.splice index, 1  if index isnt -1
      element = angular.element($('#status-messages').find('.alert-success'))
      if(element.scope())
        element.scope().$apply()
      return

    clearMessages: ->
      messages.length = 0
      return
  )

app.run ["$http","$cookies", ($http, $cookies) ->
    $http.defaults.headers.common['CSRF'] = $cookies['CSRF']
]
app.filter "hilight", ->
  (input, query) ->
    input.replace new RegExp("(" + query + ")", "gi"), "<strong>$1</strong>"

app.directive "messages", ->
  return {
    controller: ($scope, MessageService) ->
      $scope.messages = MessageService.messages
      $scope.removeMessage = MessageService.removeMessage
      return

    templateUrl: "templates/messages.html"
  }

app.directive 'customdateparser', ->
  return {
    restrict: 'A',
    require: '?ngModel',
    link: ($scope, $element, $attrs, $ngModel) ->
      if $ngModel
        $ngModel.$parsers.unshift (data) ->
          if data instanceof Date
            data
          else
            parts = data.split('.')
            date = new Date(parts[2], parts[1]-1, parts[0])
            if +parts[2] == date.getFullYear() && +parts[1]-1 == date.getMonth() && +parts[0] == date.getDate()
              date
            else
              undefined
  }

app.directive 'datepickerPopup', ->
  return {
    restrict: 'EAC',
    require: '?ngModel',
    link: ($scope, $element, $attrs, $controller) ->
      $controller.$formatters.shift()
  }

app.directive "tiedonsiirtomenu", ->
  return {
    controller: ($scope, $location) ->
      $scope.menu = [
        {
          path: "/tiedonsiirto/hakeneet"
          href: "#/tiedonsiirto/hakeneet"
          role: "app_tiedonsiirto_valinta"
          messageKey: "suoritusrekisteri.tiedonsiirto.menu.hakeneet"
          text: "Hakeneet ja valitut"
        }
        {
          path: "/tiedonsiirto/kkhakeneet"
          href: "#/tiedonsiirto/kkhakeneet"
          role: "app_tiedonsiirto_valinta"
          messageKey: "suoritusrekisteri.tiedonsiirto.menu.kkhakeneet"
          text: "Hakeneet ja valitut (KK)"
        }
      ]

      $scope.isActive = (path) ->
        path is $location.path()

      $scope.hasRole = (role) ->
        if window.myroles
          if window.myroles.toString().toLowerCase().match(new RegExp(role))
            return true
          else
            return false
        else if location.hostname is "localhost"
          return true
        false

      return

    templateUrl: "templates/tiedonsiirtomenu.html"
  }
