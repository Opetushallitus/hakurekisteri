app.controller "KielikoeArvosanat", [
  "$scope"
  "$q"
  "Arvosanat"
  "MessageService"
  ($scope, $q, Arvosanat, MessageService) ->
    $scope.arvosanat = []
    $scope.modified = {}
    Arvosanat.query {suoritus: $scope.suoritus.id}, ((arvosanatData) ->
      for a in arvosanatData
        $scope.modified[a.id] = false
        a.myonnetty = $scope.parseFinDate(a.myonnetty)
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
        message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen.")]

    $scope.addDataScope($scope)
]
