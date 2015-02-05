app.controller "TiedonsiirtoCtrl", [
  "$scope"
  "MurupolkuService"
  "MessageService"
  "$log"
  ($scope, MurupolkuService, MessageService, $log) ->
    isImportBatchResponse = (content) ->
      (typeof content is "string" and content.match(/.*"batchType".*/g)) or (typeof content is "object" and content.batchType)

    isIncidentResponse = (content) ->
      (typeof content is "string" and content.match(/.*"incidentId".*/g)) or (typeof content is "object" and content.incidentId)

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

    $scope.beforeSubmitCheck = ->
      $scope.$apply(->
        MessageService.clearMessages()
      )
      if !$scope.tyyppi or document.getElementById("tiedosto").files.length is 0
        if !$scope.tyyppi
          $scope.$apply(->
            MessageService.addMessage
              type: "danger"
              message: "Tiedoston tyyppiä ei ole valittu"
              messageKey: "suoritusrekisteri.tiedonsiirto.tyyppiaeiolevalittu"
          )
        if document.getElementById("tiedosto").files.length is 0
          $scope.$apply(->
            MessageService.addMessage
              type: "danger"
              message: "Tiedostoa ei ole valittu"
              messageKey: "suoritusrekisteri.tiedonsiirto.tiedostoaeiolevalittu"
          )
        return false
      else
        form = jQuery("#uploadForm")
        if form.get(0)
          form.get(0).setAttribute('action', 'rest/v1/siirto/' + $scope.tyyppi)
        $scope.sending = true
        delete $scope.uploadResult
        return true

    $scope.uploadComplete = (content) ->
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