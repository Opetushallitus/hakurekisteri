app.config ($locationProvider, $routeProvider, $httpProvider) ->
  $routeProvider.when "/opiskelijat",
    templateUrl: "templates/opiskelijat.html"
    controller: "OpiskelijatCtrl"

  $routeProvider.when "/muokkaa-obd",
    templateUrl: "templates/muokkaa-obd.html"
    controller: "MuokkaaSuorituksetObdCtrl"

  $routeProvider.when "/muokkaa/:henkiloOid",
    templateUrl: "templates/muokkaa.html"
    controller: "MuokkaaCtrl"

  $routeProvider.when "/eihakeneet",
    templateUrl: "templates/eihakeneet.html"
    controller: "EihakeneetCtrl"

  $routeProvider.when "/tiedonsiirto/lahetys",
    templateUrl: "templates/tiedonsiirto.html"
    controller: "TiedonsiirtoCtrl"

  $routeProvider.when "/tiedonsiirto/tila",
    templateUrl: "templates/tiedonsiirtotila.html"
    controller: "TiedonsiirtotilaCtrl"

  $routeProvider.when "/tiedonsiirto/hakeneet",
    templateUrl: "templates/hakeneet.html"
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
    templateUrl: "templates/hakeneet.html?aste=kk"
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
      "/muokkaa-obd"
  $locationProvider.html5Mode false

  $httpProvider.interceptors.push 'callerIdInterceptor'

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
