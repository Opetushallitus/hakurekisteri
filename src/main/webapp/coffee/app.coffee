"use strict"

app = angular.module "myApp", [
  "ngRoute"
  "ngResource"
  "ui.bootstrap"
  "ngUpload"
  "ngSanitize"
]

app.factory "Opiskelijat", ($resource) ->
  $resource "rest/v1/opiskelijat/:opiskelijaId", { opiskelijaId: "@id" }, {
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
  $resource "rest/v1/suoritukset/:suoritusId", { suoritusId: "@id" }, {
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
  $resource "rest/v1/opiskeluoikeudet/:opiskeluoikeusId", { opiskeluoikeusId: "@id" }, {
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
  $resource "rest/v1/arvosanat/:arvosanaId", { arvosanaId: "@id" }, {
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


app.factory "MurupolkuService", ->
  murupolku = []
  hide = false

  return (
    murupolku: murupolku
    addToMurupolku: (item, reset) ->
      murupolku.length = 0  if reset
      murupolku.push item
      hide = false
      return
    hideMurupolku: ->
      hide = true
      return
    isHidden: ->
      hide
  )


app.factory "MessageService", ->
  messages = []
  return (
    messages: messages
    addMessage: (message, clear) ->
      messages.length = 0  if clear
      messages.push message
      return

    removeMessage: (message) ->
      index = messages.indexOf(message)
      messages.splice index, 1  if index isnt -1
      return

    clearMessages: ->
      messages.length = 0
      return
  )

app.filter "hilight", ->
  (input, query) ->
    input.replace new RegExp("(" + query + ")", "gi"), "<strong>$1</strong>"

app.directive "messages", ->
  return (
    controller: ($scope, MessageService) ->
      $scope.messages = MessageService.messages
      $scope.removeMessage = MessageService.removeMessage
      return

    templateUrl: "templates/messages"
  )

app.directive "tiedonsiirtomenu", ->
  return (
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
        {
          path: "/tiedonsiirto/lahetys"
          href: "#/tiedonsiirto/lahetys"
          role: "app_tiedonsiirto_crud"
          messageKey: "suoritusrekisteri.tiedonsiirto.menu.tiedostonlahetys"
          text: "Tiedoston lähetys"
        }
        {
          path: "/tiedonsiirto/tila"
          href: "#/tiedonsiirto/tila"
          role: "app_tiedonsiirto_crud_1.2.246.562.10.00000000001"
          messageKey: "suoritusrekisteri.tiedonsiirto.menu.tila"
          text: "Tiedonsiirtojen tila"
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

    templateUrl: "templates/tiedonsiirtomenu"
  )
