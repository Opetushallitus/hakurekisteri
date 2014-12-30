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
      haut: ($http, MessageService) ->
        $http.get("rest/v1/haut", { cache: true }).then(((response) -> response.data), ->
          MessageService.addMessage
            type: "danger"
            message: "Tietojen lataaminen näytölle epäonnistui."
            description: "Päivitä näyttö tai navigoi sille uudelleen."
          []
        )
      hakukohdekoodit: ($http, MessageService) ->
        $http.get(koodistoServiceUrl + "/rest/json/hakukohteet/koodi", { cache: true }).then(((response) -> response.data), ->
          MessageService.addMessage
            type: "danger"
            message: "Tietojen lataaminen näytölle epäonnistui."
            description: "Päivitä näyttö tai navigoi sille uudelleen."
          []
        )

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
      haut: ($http, MessageService) ->
        $http.get("rest/v1/haut", { cache: true }).then(((response) -> response.data), ->
          MessageService.addMessage
            type: "danger"
            message: "Tietojen lataaminen näytölle epäonnistui."
            description: "Päivitä näyttö tai navigoi sille uudelleen."
          []
        )
      hakukohdekoodit: ->
        []

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
