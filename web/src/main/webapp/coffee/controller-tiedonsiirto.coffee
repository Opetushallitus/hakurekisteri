app.controller "TiedonsiirtoCtrl", [
  "$scope"
  "MurupolkuService"
  "MessageService"
  "LokalisointiService"
  "$log"
  "$http"
  ($scope, MurupolkuService, MessageService, LokalisointiService, $log, $http) ->
    supportsFileApi = window.FileReader?

    $http.get(koodistoServiceUrl + "/rest/json/oppiaineetyleissivistava/koodi/", {cache: true}).success (koodit) ->
      translateWithMetadata = (koodi, metadatas) -> (lang) ->
        translations = R.fromPairs(R.map((metadata) -> [metadata.kieli.toLowerCase(), metadata.nimi])(metadatas))
        translations[lang] || translations["fi"] || koodi

      $scope.aineidenKielistykset = R.fromPairs(R.map((koodi) -> [koodi.koodiArvo.toLowerCase(), translateWithMetadata(koodi.koodiArvo, koodi.metadata)])(koodit))

    $scope.validointiVirheet = []

    fileupload = document.getElementById("tiedosto")

    MurupolkuService.addToMurupolku
      key: "suoritusrekisteri.tiedonsiirto.muru"
      text: "Tiedonsiirto"
    , true

    clearFile = () ->
      try
        elem = document.getElementById("tiedosto")
        if elem
          elem.value = ''
          if elem.value
            elem.type = 'text'
            elem.type = 'file'
      catch e
        $log.error(e)

    $scope.validateXmlFile = ->
      if supportsFileApi and $scope.tyyppi is "arvosanat"
        file = fileupload.files[0]
        reader = new FileReader()
        reader.readAsText(file)
        reader.addEventListener "loadend", -> validateXml(reader.result)

    validateXml = window.validateXml = (xml) ->
      result = hakurekisteri.perusopetus.xml.validate.validoi(xml)
      $scope.validointiVirheet = R.toPairs(R.groupBy(({rule, resource}) -> rule.id)(result)).map ([ruleId, todistusTuplet]) ->
        todistukset = todistusTuplet.map (tuple) -> tuple.resource
        aineet = ruleId.replace("mandatory-", "").split("-or-")
          .map((aine) -> $scope.aineidenKielistykset[aine](LokalisointiService.lang))
        message = aineet.join(" tai ") + " puuttuu"
        {ruleId, todistukset, count: todistukset.length, message}
      console.log("validation result", $scope.validointiVirheet)
      $scope.$apply()

    $scope.beforeSubmitCheck = ->
      $scope.$apply(->
        MessageService.clearMessages()
      )
      if !$scope.tyyppi
        $scope.$apply(->
          MessageService.addMessage
            type: "danger"
            message: "Tiedoston tyyppiä ei ole valittu"
            messageKey: "suoritusrekisteri.tiedonsiirto.tyyppiaeiolevalittu"
        )
        false
      if !fileupload.value
        $scope.$apply(->
          MessageService.addMessage
            type: "danger"
            message: "Tiedostoa ei ole valittu"
            messageKey: "suoritusrekisteri.tiedonsiirto.tiedostoaeiolevalittu"
        )
        false
      else
        form = jQuery("#uploadForm")
        if form.get(0)
          form.get(0).setAttribute('action', 'rest/v1/siirto/' + $scope.tyyppi)
        $scope.sending = true
        delete $scope.uploadResult
        true

    $scope.uploadComplete = (content) ->
      isImportBatchResponse = (content) ->
        (typeof content is "string" and content.match(/.*"batchType".*/g)) or (typeof content is "object" and content.batchType)

      isIncidentResponse = (content) ->
        (typeof content is "string" and content.match(/.*"incidentId".*/g)) or (typeof content is "object" and content.incidentId)

      if isImportBatchResponse(content)
        response = (if typeof content is "object" then content else angular.fromJson(content))
        $scope.uploadResult =
          type: "success"
          message: "Tiedosto lähetetty."
          messageKey: "suoritusrekisteri.tiedonsiirto.tiedostolahetetty"
          id: response.id
      else if isIncidentResponse(content)
        response = (if typeof content is "object" then content else angular.fromJson(content))
        $scope.uploadResult =
          type: "danger"
          message: "Virhe lähettäessä tiedostoa."
          messageKey: "suoritusrekisteri.tiedonsiirto.virhe"
          description: response.message
          validationErrors: response.validationErrors
      delete $scope.sending
      clearFile()
      return

    $scope.reset = ->
      document.getElementById("uploadForm").reset()
      delete $scope.uploadResult
      delete $scope.tyyppi
      MessageService.clearMessages()
      return
]