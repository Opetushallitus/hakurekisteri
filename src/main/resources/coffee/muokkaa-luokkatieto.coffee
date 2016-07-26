app.controller "MuokkaaLuokkatieto", [
  "$scope"
  "$http"
  "$q"
  "MessageService"
  "LokalisointiService"
  ($scope, $http, $q, MessageService, LokalisointiService) ->
    enrichLuokkatieto = (luokkatieto) ->
      if luokkatieto.oppilaitosOid
        getOrganisaatio $http, luokkatieto.oppilaitosOid, (organisaatio) ->
          $scope.info.oppilaitos = organisaatio.oppilaitosKoodi
          $scope.info.organisaatio = organisaatio
      $scope.info.editable = true

    getOppilaitosOid = () ->
      d = $q.defer()
      getOrganisaatio $http, $scope.info.oppilaitos, ((organisaatio) ->
        $scope.luokkatieto.oppilaitosOid = organisaatio.oid
        d.resolve "validated against organisaatio"
      ), ->
        d.reject "validationerror in call to organisaatio"
      d.promise

    $scope.hasChanged = ->
      modifiedCache.hasChanged()

    $scope.saveData = ->
      if $scope.hasChanged()
        d = $q.defer()
        getOppilaitosOid().then () ->
          luokkatieto = $scope.luokkatieto
          luokkatieto.$save (->
            enrichLuokkatieto luokkatieto
            d.resolve "done"
          ), ->
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja"
              message: "Virhe tallennettaessa luokkatietoja."
              descriptionKey: "suoritusrekisteri.muokkaa.virheluokkayrita"
              description: "Yritä uudelleen."
            d.reject "error saving luokkatieto: " + JSON.stringify luokkatieto
        d.promise.then () ->
          modifiedCache.update()
        [d.promise]

    $scope.poistaLuokkatieto = () ->
      luokkatieto = $scope.luokkatieto
      removeLuokkatietoScope = () ->
        $scope.removeDataScope($scope)
        deleteFromArray luokkatieto, $scope.henkilo.luokkatiedot
      if confirm(LokalisointiService.getTranslation('haluatkoPoistaaLuokkatiedon'))
        if luokkatieto.id
          luokkatieto.$remove removeLuokkatietoScope, ->
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja"
              message: "Virhe tallennettaessa luokkatietoja."
              descriptionKey: "suoritusrekisteri.muokkaa.virheluokkatietoyrita"
              description: "Yritä uudelleen."
        else
          removeLuokkatietoScope()

    modifiedCache = changeDetection($scope.luokkatieto)
    $scope.info = {}
    enrichLuokkatieto($scope.luokkatieto)
    $scope.addDataScope($scope)
    $scope.$watch "luokkatieto", $scope.enableSave, true
    $scope.$watch "info", $scope.enableSave, true
]