app.config ($locationProvider, $routeProvider) ->
  $routeProvider.when "/opiskelijat",
    templateUrl: "templates/opiskelijat"
    controller: "OpiskelijatCtrl"

  $routeProvider.when "/muokkaa/:henkiloOid",
    templateUrl: "templates/muokkaa"
    controller: "MuokkaaCtrl"

  $routeProvider.when "/eihakeneet",
    templateUrl: "templates/eihakeneet"
    controller: "EihakeneetCtrl"

  $routeProvider.when "/tiedonsiirto/lahetys",
    templateUrl: "templates/tiedonsiirto"
    controller: "TiedonsiirtoCtrl"

  $routeProvider.when "/tiedonsiirto/tila",
    templateUrl: "templates/tiedonsiirtotila"
    controller: "TiedonsiirtotilaCtrl"

  $routeProvider.when "/tiedonsiirto/hakeneet",
    templateUrl: "templates/hakeneet"
    controller: "HakeneetCtrl"
    resolve:
      aste: ->
        "toinenaste"

  $routeProvider.when "/tiedonsiirto",
    redirectTo: (routeParams, currentLocation, search) ->
      "/tiedonsiirto/lahetys"

  $routeProvider.when "/tiedonsiirto/kkhakeneet",
    templateUrl: "templates/hakeneet?aste=kk"
    controller: "HakeneetCtrl"
    controllerAs: "KkHakeneetCtrl"
    resolve:
      aste: ->
        "kk"

  $routeProvider.otherwise
    redirectTo: (routeParams, currentLocation, search) ->
      "/opiskelijat"
  $locationProvider.html5Mode false
  return

app.run ($http, $log, MessageService) ->
  $http.get(henkiloServiceUrl + "/buildversion.txt?auth").success(->
    $log.debug "called authentication-service successfully"
    return
  ).error ->
    MessageService.addMessage
      type: "danger"
      messageKey: "suoritusrekisteri.opiskelijat.henkiloeiyhteytta"
      message: "Henkilöpalveluun ei juuri nyt saada yhteyttä."
      descriptionKey: "suoritusrekisteri.opiskelijat.henkiloyrita"
      description: "Yritä hetken kuluttua uudelleen."

    return

  return
