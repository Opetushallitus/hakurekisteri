app.controller "MuokkaaSuoritus", [
  "$scope"
  "$http"
  "$q"
  "MessageService"
  "LokalisointiService"
  ($scope, $http, $q, MessageService, LokalisointiService) ->
    enrichSuoritus = (suoritus) ->
      $scope.info.showArvosanat = true
      $scope.info.editable = false
      if suoritus.myontaja and suoritus.myontaja.match(/^1\.2\.246\.562\.10\.\d+$/)
        getOrganisaatio $http, suoritus.myontaja, (organisaatio) ->
          $scope.info.oppilaitos = organisaatio.oppilaitosKoodi
          $scope.info.organisaatio = organisaatio

      if suoritus.komo and suoritus.komo.match(/^koulutus_\d*$/)
        $scope.info.showArvosanat = false
        getKoulutusNimi $http, suoritus.komo, (koulutusNimi) ->
          $scope.info.koulutus = koulutusNimi
      else if $scope.suoritus.source != $scope.ylioppilastutkintolautakunta
        $scope.info.editable = true

    $scope.checkYlioppilastutkinto = (suoritus) ->
      $scope.info.maxDate = null
      if suoritus.komo is $scope.komo.ylioppilastutkinto
        $scope.info.maxDate = '1989-12-31'
        suoritus.myontaja = $scope.ylioppilastutkintolautakunta
        getOrganisaatio $http, $scope.ylioppilastutkintolautakunta, (org) ->
          $scope.info.organisaatio = org

    $scope.resolveValueFromOptionArray = (value, list) ->
      for i in list
        if i.value == value
          return i.text
      value

    $scope.getOrganisaatioNimi = (org) ->
      if org and org.nimi
        org.nimi[LokalisointiService.lang] or org.nimi.fi or org.nimi.sv or org.nimi.en

    $scope.validateData = (updateOnly) ->
      $scope.validateOppilaitoskoodiFromScopeAndUpdateModel($scope.info, $scope.suoritus, !updateOnly)
      if $scope.info.editable
        $scope.validateValmistuminen(updateOnly)

    $scope.validateValmistuminen = (updateOnly) ->
      d = $q.defer()
      if not $scope.validateValmistumispaiva($scope.info.valmistuminen)
        if(!updateOnly)
          MessageService.addMessage
            type: "danger"
            messageKey: "suoritusrekisteri.muokkaa.valmistuminen"
            message: "Valmistumispäivä puuttuu tai se on virheellinen."
            descriptionKey: "suoritusrekisteri.muokkaa.tarkistavalmistuminen"
            description: "Tarkista valmistumispäivä ja yritä uudelleen."
          d.reject "validationerror"
      else
        if($scope.parseFinDate($scope.suoritus.valmistuminen).getTime() != $scope.info.valmistuminen.getTime())
          $scope.suoritus.valmistuminen = $scope.info.valmistuminen
        d.resolve "pvm ok"
      [d.promise]

    $scope.validateValmistumispaiva = (valmistumispvm) ->
      if !valmistumispvm
        false
      else if $scope.suoritus.komo != $scope.komo.ylioppilastutkinto
        true
      else
        valmistumispvm.getTime() < new Date(1990, 0, 1).getTime()

    $scope.hasChanged = ->
      $scope.validateData(true)
      modifiedCache.hasChanged()

    $scope.saveData = ->
      if $scope.hasChanged()
        d = $q.defer()
        suoritus = $scope.suoritus
        suoritus.$save (->
          enrichSuoritus suoritus
          d.resolve "done"
        ), (res) ->
          status = res.status

          if status == 404
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.virheestokausi"
              message: "Suorituksien päivittäminen on estetty tällä hetkellä."
              descriptionKey: "suoritusrekisteri.muokkaa.virheestokausi"
              description: "Suorituksien päivittäminen on estetty tällä hetkellä."
          else
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja"
              message: "Virhe tallennettaessa suoritustietoja."
              descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita"
              description: "Yritä uudelleen."
          d.reject "error saving suoritus: " + suoritus
        d.promise.then ->
          modifiedCache.update()
        [d.promise]
      else
        []

    $scope.poistaSuoritus = ->
      suoritus = $scope.suoritus
      removeSuoritusScope = () ->
        $scope.removeDataScope($scope)
        deleteFromArray suoritus, $scope.henkilo.suoritukset
      if confirm(LokalisointiService.getTranslation('poistaSuoritus') + " " + suoritus.valmistuminen + "?")
        if suoritus.id
          suoritus.$remove removeSuoritusScope, ->
            $scope.suoritus.valmistuminen
        else
          removeSuoritusScope()

    $scope.parseFinDate = (input) ->
      if input instanceof Date
        input
      else if input
        parts = input.split('.')
        new Date(parts[2], parts[1]-1, parts[0])

    $scope.showValmistuminenFormatted = ->
      $scope.formatDateNoZeroPaddedNumbers($scope.info.valmistuminen)

    $scope.formatDateNoZeroPaddedNumbers = (input) ->
      date = $scope.parseFinDate(input)
      if date
        ""+date.getDate()+"."+(date.getMonth()+1)+"."+date.getFullYear()

    modifiedCache = changeDetection($scope.suoritus)
    $scope.info = {}
    $scope.info.valmistuminen = $scope.parseFinDate($scope.suoritus.valmistuminen)
    enrichSuoritus($scope.suoritus)
    $scope.checkYlioppilastutkinto($scope.suoritus)
    $scope.addDataScope($scope)
    $scope.$watch "info", $scope.enableSave, true
    $scope.$watch "suoritus", $scope.enableSave, true

]
