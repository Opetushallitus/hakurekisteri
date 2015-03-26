app.controller "MuokkaaLuokkatieto", [
  "$scope"
  "$http"
  "$q"
  "MessageService"
  ($scope, $http, $q, MessageService) ->
    enrichLuokkatieto = (luokkatieto) ->
      if luokkatieto.oppilaitosOid
        getOrganisaatio $http, luokkatieto.oppilaitosOid, (organisaatio) ->
          $scope.info.oppilaitos = organisaatio.oppilaitosKoodi
          $scope.info.organisaatio = organisaatio
      $scope.info.editable = true

    $scope.validateData = (updateOnly) ->
      $scope.validateOppilaitoskoodiFromScopeAndUpdateMyontajaInModel($scope.info, $scope.luokkatieto, !updateOnly)

    $scope.hasChanged = ->
      $scope.validateData(true)
      modifiedCache.hasChanged()

    $scope.saveData = ->
      luokkatieto = $scope.luokkatieto
      if $scope.hasChanged()
        d = $q.defer()
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
          d.reject "error saving luokkatieto: " + luokkatieto
        d.promise.then () ->
          modifiedCache.update()
        [d.promise]

    $scope.poistaLuokkatieto = () ->
      luokkatieto = $scope.luokkatieto
      removeLuokkatietoScope = () ->
        $scope.removeDataScope($scope)
        deleteFromArray luokkatieto, $scope.henkilo.luokkatiedot
      if confirm("Poista luokkatieto?")
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