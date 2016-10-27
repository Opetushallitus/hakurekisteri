app.controller "KielikoeArvosanat", [
  "$scope"
  "Arvosanat"
  "MessageService"
  ($scope, Arvosanat, MessageService) ->
    $scope.arvosanat = []
    Arvosanat.query {suoritus: $scope.suoritus.id}, ((arvosanatData) ->
      for a in arvosanatData
        a.arvio.arvosana = "true" == a.arvio.arvosana.toLowerCase()
      $scope.arvosanat = arvosanatData
      ), ->
      MessageService.addMessage
        type: "danger"
        messageKey: "suoritusrekisteri.muokkaa.arvosanat.arvosanapalveluongelma"
        message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
]
