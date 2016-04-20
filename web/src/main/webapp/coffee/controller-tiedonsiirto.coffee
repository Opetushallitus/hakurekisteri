app.controller "TiedonsiirtoCtrl", [
  "$scope"
  "MessageService"
  "LokalisointiService"
  "$log"
  "$http"
  ($scope, MessageService, LokalisointiService, $log, $http) ->
    supportsFileApi = window.FileReader?

    fetchEnabledState = (type) ->
      $http.get(window.url("suoritusrekisteri.siirtoIsOpen", "v2", type), {cache: true})
        .success (data) ->
          $scope[type + "Enabled"] = data.open
        .error ->
          $scope[type + "Enabled"] = false
          $log.error "cannot connect " + type

    fetchEnabledState('perustiedot')
    fetchEnabledState('arvosanat')

    $scope.isSendingDisabled = () ->
      !$scope.tyyppi

    $http.get(window.url("koodisto-service.koodisByKoodisto","oppiaineetyleissivistava"), {cache: true}).success (koodit) ->
      translateWithMetadata = (koodi, metadatas) -> (lang) ->
        translations = R.fromPairs(R.map((metadata) -> [metadata.kieli.toLowerCase(), metadata.nimi])(metadatas))
        translations[lang] || translations["fi"] || koodi

      $scope.aineidenKielistykset = R.fromPairs(R.map((koodi) -> [koodi.koodiArvo.toLowerCase(), translateWithMetadata(koodi.koodiArvo, koodi.metadata)])(koodit))

    $scope.validointiVirheet = []

    fileupload = document.getElementById("tiedosto")

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
        prefixForMandatoryRule = "hakurekisteri.perusopetus.mandatory-"
        message = if (ruleId.indexOf(prefixForMandatoryRule) == 0)
          aineet = ruleId.replace(prefixForMandatoryRule, "").split("-or-")
            .map (aine) -> $scope.aineidenKielistykset[aine](LokalisointiService.lang)
          aineet.join(" tai ") + " puuttuu"
        else
          ruleId
        {ruleId, todistukset, count: todistukset.length, message}
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
          url = 'rest/v2/siirto/' + $scope.tyyppi

          form.get(0).setAttribute('action', url)
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