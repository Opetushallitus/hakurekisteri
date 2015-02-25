app.controller "MuokkaaSuoritus", [
  "$scope"
  "$http"
  "$q"
  "MessageService"
  ($scope, $http, $q, MessageService) ->
    enrichSuoritus = (suoritus) ->
      if suoritus.myontaja
        getOrganisaatio $http, suoritus.myontaja, (organisaatio) ->
          $scope.info.oppilaitos = organisaatio.oppilaitosKoodi
          $scope.info.organisaatio = organisaatio
      if suoritus.komo and suoritus.komo.match(/^koulutus_\d*$/)
        getKoulutusNimi $http, suoritus.komo, (koulutusNimi) ->
          $scope.info.koulutus = koulutusNimi
      else
        $scope.info.editable = true

    $scope.validateData = ->
      $scope.validateOppilaitoskoodiFromScopeAndUpdateMyontajaInModel($scope.info, $scope.suoritus)

    $scope.saveData = ->
      if modifiedCache.hasChanged()
        d = $q.defer()
        suoritus = $scope.suoritus
        if $scope.info.delete
          if suoritus.id
            suoritus.$remove (->
              deleteFromArray suoritus, $scope.henkilo.suoritukset
              d.resolve "done"
            ), ->
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja"
                message: "Virhe tallennettaessa suoritustietoja."
                descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita"
                description: "Yritä uudelleen."
              d.reject "error deleting suoritus: " + suoritus
          else
            deleteFromArray suoritus, $scope.henkilo.suoritukset
            d.resolve "done"
        else
          suoritus.$save (->
            enrichSuoritus suoritus
            d.resolve "done"
          ), ->
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja"
              message: "Virhe tallennettaessa suoritustietoja."
              descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita"
              description: "Yritä uudelleen."
            d.reject "error saving suoritus: " + suoritus
        [d.promise]
      else
        []

    modifiedCache = changeDetection($scope.suoritus)
    $scope.info = {}
    enrichSuoritus($scope.suoritus)
    $scope.addDataScope($scope)
]
