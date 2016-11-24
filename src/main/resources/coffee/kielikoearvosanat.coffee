app.controller "KielikoeArvosanat", [
  "$scope"
  "$http"
  "$q"
  "Arvosanat"
  "MessageService"
  "LokalisointiService"
  ($scope, $http, $q, Arvosanat, MessageService, LokalisointiService) ->
    $scope.arvosanat = []
    $scope.modified = {}
    $scope.myontajat = {}
    Arvosanat.query {suoritus: $scope.suoritus.id}, ((arvosanatData) ->
      for a in arvosanatData
        do (a) ->
          $scope.modified[a.id] = false
          a.myonnetty = $scope.parseFinDate(a.myonnetty)
          $scope.myontajat[a.id] = ""
          getOrganisaatio $http, a.source, (org) ->
            $scope.myontajat[a.id] = org.nimi[LokalisointiService.lang] or org.nimi.fi or org.nimi.sv or org.nimi.en or ""
      $scope.arvosanat = arvosanatData
      ), ->
      MessageService.addMessage
        type: "danger"
        messageKey: "suoritusrekisteri.muokkaa.arvosanat.arvosanapalveluongelma"
        message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."

    $scope.markModified = (a) ->
      $scope.modified[a.id] = true
      $scope.enableSave()

    $scope.hasChanged = ->
      $scope.arvosanat.some((a) -> $scope.modified[a.id])

    $scope.saveData = ->
      [$q.all(
        $scope.arvosanat
        .filter((a) -> $scope.modified[a.id])
        .map((a) -> a.$save().then(->
          $scope.modified[a.id] = false
          a.myonnetty = $scope.parseFinDate(a.myonnetty)
        ))
      ).then((->
      ), ->
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennuseionnistunut"
          message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen."
        $q.reject("error saving kielikoe arvosanat"))]

    $scope.addDataScope($scope)
]
