app.controller "MuokkaaSuoritus", [
  "$scope"
  "$http"
  "$q"
  "$modal"
  "$log"
  "Arvosanat"
  "Suoritukset"
  "MessageService"
  ($scope, $http, $q, $modal, $log, Arvosanat, Suoritukset, MessageService) ->
    enrichSuoritus = (suoritus) ->
      if suoritus.myontaja
        getOrganisaatio $http, suoritus.myontaja, (organisaatio) ->
          $scope.oppilaitos = organisaatio.oppilaitosKoodi
          $scope.organisaatio = organisaatio
      if suoritus.komo and suoritus.komo.match(/^koulutus_\d*$/)
        getKoulutusNimi $http, suoritus.komo, (koulutusNimi) ->
          $scope.koulutus = koulutusNimi
      else
        $scope.editable = true

    saveSuoritus = ->
      suoritus = $scope.suoritus
      if modifiedCache.hasChanged(suoritus.id, suoritus)
        d = $q.defer()
        if $scope.deleteSuoritus
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

    modifiedCache = createChangeDetectionCache()
    modifiedCache.add($scope.suoritus.id, $scope.suoritus)
    enrichSuoritus($scope.suoritus)
    $scope.addSave(saveSuoritus)

]
