app.config ($locationProvider, $routeProvider, $httpProvider) ->
  $routeProvider.when "/opiskelijat",
    templateUrl: "templates/muokkaa-obd.html"
    controller: "MuokkaaSuorituksetObdCtrl"

  $routeProvider.when "/muokkaa/:henkilo",
    templateUrl: "templates/muokkaa-obd.html"
    controller: "MuokkaaSuorituksetObdCtrl"

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
        $http.get(window.url("suoritusrekisteri.haut"), { cache: true }).then(((response) -> response.data), ->
          MessageService.addMessage
            type: "danger"
            message: "Tietojen lataaminen näytölle epäonnistui."
            description: "Päivitä näyttö tai navigoi sille uudelleen."
          []
        )
      hakukohdekoodit: ($http, MessageService) ->
        $http.get(window.url("koodisto-service.koodisByKoodisto","hakukohteet"), { cache: true }).then(((response) -> response.data), ->
          MessageService.addMessage
            type: "danger"
            message: "Tietojen lataaminen näytölle epäonnistui."
            description: "Päivitä näyttö tai navigoi sille uudelleen."
          []
        )
      aikuhakukohdekoodit: ($http, MessageService) ->
        $http.get(window.url("koodisto-service.koodisByKoodisto","aikuhakukohteet"), { cache: true }).then(((response) -> response.data), ->
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
        $http.get(window.url("suoritusrekisteri.haut"), { cache: true }).then(((response) -> response.data), ->
          MessageService.addMessage
            type: "danger"
            message: "Tietojen lataaminen näytölle epäonnistui."
            description: "Päivitä näyttö tai navigoi sille uudelleen."
          []
        )
      hakukohdekoodit: ->
        []
      aikuhakukohdekoodit: ->
        []

  $routeProvider.otherwise
    redirectTo: (routeParams, currentLocation, search) ->
      "/opiskelijat"
  $locationProvider.html5Mode false

app.run ($cacheFactory, $http, $log, MessageService) ->
  if (window.mocksOn)
    $http.defaults.cache = $cacheFactory("test")
    window.testCache = $http.defaults.cache
  $http.get(window.url("authentication-service.buildversion")).success(->
    return
  ).error ->
    MessageService.addMessage
      type: "danger"
      messageKey: "suoritusrekisteri.opiskelijat.henkiloeiyhteytta"
      message: "Henkilöpalveluun ei juuri nyt saada yhteyttä."
      descriptionKey: "suoritusrekisteri.opiskelijat.henkiloyrita"
      description: "Yritä hetken kuluttua uudelleen."
