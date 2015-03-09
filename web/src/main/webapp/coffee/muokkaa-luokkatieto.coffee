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

    $scope.validateData = ->
      $scope.validateOppilaitoskoodiFromScopeAndUpdateMyontajaInModel($scope.info, $scope.luokkatieto)

    $scope.saveData = ->
      luokkatieto = $scope.luokkatieto
      if modifiedCache.hasChanged()
        d = $q.defer()
        if $scope.info.delete
          if luokkatieto.id
            luokkatieto.$remove (->
              deleteFromArray luokkatieto, $scope.henkilo.luokkatiedot
              d.resolve "done"
            ), ->
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja"
                message: "Virhe tallennettaessa luokkatietoja."
                descriptionKey: "suoritusrekisteri.muokkaa.virheluokkatietoyrita"
                description: "Yritä uudelleen."

              d.reject "error deleting luokkatieto: " + luokkatieto
          else
            deleteFromArray luokkatieto, $scope.henkilo.luokkatiedot
            d.resolve "done"
        else
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

    modifiedCache = changeDetection($scope.luokkatieto)
    $scope.info = {}
    enrichLuokkatieto($scope.luokkatieto)
    $scope.addDataScope($scope)
]