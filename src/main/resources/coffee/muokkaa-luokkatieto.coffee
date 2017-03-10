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

    $scope.validateData = (updateOnly) ->
      $scope.validateOppilaitoskoodiFromScopeAndUpdateModel($scope.info, $scope.luokkatieto, !updateOnly)

    $scope.hasChanged = ->
      $scope.validateData(true)
      modifiedCache.hasChanged()

    $scope.saveData = ->
      if $scope.hasChanged()
        d = $q.defer()
        luokkatieto = $scope.luokkatieto
        luokkatieto.$save ((result) ->
          if JSON.stringify(result) == JSON.stringify(modifiedCache.original())
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja"
              message: "Virhe tallennettaessa luokkatietoja."
              descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoikeudet"
              description: "Tarkista oikeudet organisaatioon."
            d.reject "not authorized to save luokkatieto: " + JSON.stringify luokkatieto
          else
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