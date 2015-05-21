app.controller "MuokkaaSuoritus", [
  "$scope"
  "$http"
  "$q"
  "MessageService"
  ($scope, $http, $q, MessageService) ->
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

    $scope.validateData = (updateOnly) ->
      $scope.validateOppilaitoskoodiFromScopeAndUpdateMyontajaInModel($scope.info, $scope.suoritus, !updateOnly)

    yoSuoritusHasValidValmistuminen = (date) ->
      $scope.parseFinDate(date).getTime() < $scope.parseFinDate("1.1.1990").getTime()

    $scope.hasChanged = ->
      $scope.validateData(true)
      if $scope.info.valmistuminen
        if $scope.suoritus.komo != $scope.komo.ylioppilastutkinto || yoSuoritusHasValidValmistuminen($scope.info.valmistuminen)
          $scope.suoritus.valmistuminen = $scope.formatDateWithZeroPaddedNumbers($scope.info.valmistuminen)
        else
          # alert("YO-suorituksen päivämäärä pitää olla ennen 1.1.1990")
          $scope.suoritus.valmistuminen = modifiedCache.original().valmistuminen
          $scope.info.valmistuminen = $scope.formatDateNoZeroPaddedNumbers($scope.suoritus.valmistuminen)
      modifiedCache.hasChanged()

    $scope.saveData = ->
      if $scope.hasChanged()
        d = $q.defer()
        suoritus = $scope.suoritus
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
      if confirm("Poista suoritus " + suoritus.valmistuminen + "?")
        if suoritus.id
          suoritus.$remove removeSuoritusScope, ->
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja"
              message: "Virhe tallennettaessa suoritustietoja."
              descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita"
              description: "Yritä uudelleen."
        else
          removeSuoritusScope()

    $scope.parseFinDate = (input) ->
      if input instanceof Date
        input
      else
        parts = input.split('.')
        new Date(parts[2], parts[1]-1, parts[0])

    pad = (n) ->
      if n<10
        '0'+n
      else
        n

    $scope.formatDateNoZeroPaddedNumbers = (input) ->
      date = $scope.parseFinDate(input)
      ""+date.getDate()+"."+(date.getMonth()+1)+"."+date.getFullYear()

    $scope.formatDateWithZeroPaddedNumbers = (date) ->
      date = $scope.parseFinDate(date)
      "" + pad(date.getDate()) + "." + pad(date.getMonth()+1) + "." + date.getFullYear()

    modifiedCache = changeDetection($scope.suoritus)
    $scope.info = {}
    $scope.info.valmistuminen = $scope.formatDateNoZeroPaddedNumbers($scope.suoritus.valmistuminen)
    enrichSuoritus($scope.suoritus)
    $scope.checkYlioppilastutkinto($scope.suoritus)
    $scope.addDataScope($scope)
    $scope.$watch "info", $scope.enableSave, true
    $scope.$watch "suoritus", $scope.enableSave, true
]
