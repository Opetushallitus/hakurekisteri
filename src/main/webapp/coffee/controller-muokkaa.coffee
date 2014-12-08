app.controller "MuokkaaCtrl", [
  "$scope"
  "$routeParams"
  "$location"
  "$http"
  "$log"
  "$q"
  "$modal"
  "Opiskelijat"
  "Suoritukset"
  "Opiskeluoikeudet"
  "LokalisointiService"
  "MurupolkuService"
  "MessageService"
  ($scope, $routeParams, $location, $http, $log, $q, $modal, Opiskelijat, Suoritukset, Opiskeluoikeudet, LokalisointiService, MurupolkuService, MessageService) ->
    loadMenuTexts = ->
      $scope.luokkatasot = [
        {
          value: "9"
          text: getOphMsg("suoritusrekisteri.luokktaso.9", "9")
        }
        {
          value: "10"
          text: getOphMsg("suoritusrekisteri.luokktaso.10", "10")
        }
        {
          value: "A"
          text: getOphMsg("suoritusrekisteri.luokktaso.a", "ammattistartti")
        }
        {
          value: "AK"
          text: getOphMsg("suoritusrekisteri.luokktaso.ak", "ammatillinen peruskoulutus")
        }
        {
          value: "L"
          text: getOphMsg("suoritusrekisteri.luokktaso.l", "lukio")
        }
        {
          value: "M"
          text: getOphMsg("suoritusrekisteri.luokktaso.m", "maahanmuuttajien ammatilliseen peruskoulutukseen valmistava koulutus")
        }
        {
          value: "ML"
          text: getOphMsg("suoritusrekisteri.luokktaso.ml", "maahanmuuttajien lukiokoulutukseen valmistava koulutus")
        }
        {
          value: "V"
          text: getOphMsg("suoritusrekisteri.luokktaso.v", "vammaisten valmentava ja kuntouttava opetus ja ohjaus")
        }
      ]
      $scope.yksilollistamiset = [
        {
          value: "Ei"
          text: getOphMsg("suoritusrekisteri.yks.ei", "Ei")
        }
        {
          value: "Osittain"
          text: getOphMsg("suoritusrekisteri.yks.osittain", "Osittain")
        }
        {
          value: "Alueittain"
          text: getOphMsg("suoritusrekisteri.yks.alueittain", "Alueittain")
        }
        {
          value: "Kokonaan"
          text: getOphMsg("suoritusrekisteri.yks.kokonaan", "Kokonaan")
        }
      ]
      $scope.koulutukset = [
        {
          value: komo.ulkomainen
          text: getOphMsg("suoritusrekisteri.komo." + komo.ulkomainen, "Ulkomainen")
        }
        {
          value: komo.peruskoulu
          text: getOphMsg("suoritusrekisteri.komo." + komo.peruskoulu, "Peruskoulu")
        }
        {
          value: komo.lisaopetus
          text: getOphMsg("suoritusrekisteri.komo." + komo.lisaopetus, "Perusopetuksen lisäopetus")
        }
        {
          value: komo.ammattistartti
          text: getOphMsg("suoritusrekisteri.komo." + komo.ammattistartti, "Ammattistartti")
        }
        {
          value: komo.maahanmuuttaja
          text: getOphMsg("suoritusrekisteri.komo." + komo.maahanmuuttaja, "Maahanmuuttaja")
        }
        {
          value: komo.valmentava
          text: getOphMsg("suoritusrekisteri.komo." + komo.valmentava, "Valmentava")
        }
        {
          value: komo.ylioppilastutkinto
          text: getOphMsg("suoritusrekisteri.komo." + komo.ylioppilastutkinto, "Ylioppilastutkinto")
        }
      ]
      $scope.tilat = [
        {
          value: "KESKEN"
          text: getOphMsg("suoritusrekisteri.tila.KESKEN", "Kesken")
        }
        {
          value: "KESKEYTYNYT"
          text: getOphMsg("suoritusrekisteri.tila.KESKEYTYNYT", "Keskeytynyt")
        }
        {
          value: "VALMIS"
          text: getOphMsg("suoritusrekisteri.tila.VALMIS", "Valmis")
        }
      ]
      return
    getMyRoles = ->
      $http.get("/cas/myroles",
        cache: true
      ).success((data) ->
        $scope.myRoles = angular.fromJson(data)
        return
      ).error ->
        $log.error "cannot connect to CAS"
        return

      return
    fetchHenkilotiedot = ->
      $http.get(henkiloServiceUrl + "/resources/henkilo/" + encodeURIComponent($scope.henkiloOid),
        cache: false
      ).success((henkilo) ->
        $scope.henkilo = henkilo  if henkilo
        return
      ).error ->
        MessageService.addMessage
          type: "danger"
          message: "Henkilötietojen hakeminen ei onnistunut. Yritä uudelleen?"
          messageKey: "suoritusrekisteri.muokkaa.henkilotietojenhakeminen"

        back()
        return

      return
    enrichLuokkatieto = (luokkatieto) ->
      if luokkatieto.oppilaitosOid
        getOrganisaatio $http, luokkatieto.oppilaitosOid, (organisaatio) ->
          luokkatieto.oppilaitos = organisaatio.oppilaitosKoodi
          luokkatieto.organisaatio = organisaatio
          return

      luokkatieto.editable = true
      return
    fetchLuokkatiedot = ->
      enrich = ->
        angular.forEach $scope.luokkatiedot, enrichLuokkatieto  if $scope.luokkatiedot
        return
      Opiskelijat.query
        henkilo: $scope.henkiloOid
      , ((luokkatiedot) ->
          $scope.luokkatiedot = luokkatiedot
          enrich()
          return
        ), ->
        MessageService.addMessage
          type: "danger"
          message: "Luokkatietojen hakeminen ei onnistunut. Yritä uudelleen?"
          messageKey: "suoritusrekisteri.muokkaa.luokkatietojenhakeminen"

        back()
        return

      return
    enrichSuoritus = (suoritus) ->
      if suoritus.myontaja
        getOrganisaatio $http, suoritus.myontaja, (organisaatio) ->
          suoritus.oppilaitos = organisaatio.oppilaitosKoodi
          suoritus.organisaatio = organisaatio
          return

      if suoritus.komo and suoritus.komo.match(/^koulutus_\d*$/)
        getKoulutusNimi $http, suoritus.komo, (koulutusNimi) ->
          suoritus.koulutus = koulutusNimi
          return

      else
        suoritus.editable = true
      return
    fetchSuoritukset = ->
      enrich = ->
        angular.forEach $scope.suoritukset, enrichSuoritus  if $scope.suoritukset
        return
      Suoritukset.query
        henkilo: $scope.henkiloOid
      , ((suoritukset) ->
          suoritukset.sort (a, b) ->
            sortByFinDateDesc a.valmistuminen, b.valmistuminen

          $scope.suoritukset = suoritukset
          enrich()
          return
        ), ->
        MessageService.addMessage
          type: "danger"
          message: "Suoritustietojen hakeminen ei onnistunut. Yritä uudelleen?"
          messageKey: "suoritusrekisteri.muokkaa.suoritustietojenhakeminen"

        back()
        return

      return
    fetchOpiskeluoikeudet = ->
      enrich = ->
        if $scope.opiskeluoikeudet
          angular.forEach $scope.opiskeluoikeudet, (opiskeluoikeus) ->
            if opiskeluoikeus.myontaja
              getOrganisaatio $http, opiskeluoikeus.myontaja, (organisaatio) ->
                opiskeluoikeus.oppilaitos = organisaatio.oppilaitosKoodi
                opiskeluoikeus.organisaatio = organisaatio
                return

            if opiskeluoikeus.komo and opiskeluoikeus.komo.match(/^koulutus_\d*$/)
              getKoulutusNimi $http, opiskeluoikeus.komo, (koulutusNimi) ->
                opiskeluoikeus.koulutus = koulutusNimi
                return

            return

        return
      Opiskeluoikeudet.query
        henkilo: $scope.henkiloOid
      , (opiskeluoikeudet) ->
        $scope.opiskeluoikeudet = opiskeluoikeudet
        enrich()
        return

      return
    back = ->
      if history and history.back
        history.back()
      else
        $location.path "/opiskelijat"
      return
    initDatepicker = ->
      $scope.showWeeks = true
      $scope.dateOptions =
        formatYear: "yyyy"
        startingDay: 1

      $scope.format = "mediumDate"
      return
    $scope.henkiloOid = $routeParams.henkiloOid
    $scope.myRoles = []
    $scope.suoritukset = []
    $scope.luokkatiedot = []
    $scope.kielet = []
    $scope.komo = komo
    LokalisointiService.loadMessages loadMenuTexts
    getKoodistoAsOptionArray $http, "kieli", "fi", $scope.kielet, "koodiArvo"
    MurupolkuService.addToMurupolku
      href: "#/opiskelijat"
      key: "suoritusrekisteri.muokkaa.muru1"
      text: "Opiskelijoiden haku"
    , true
    MurupolkuService.addToMurupolku
      key: "suoritusrekisteri.muokkaa.muru"
      text: "Muokkaa opiskelijan tietoja"
    , false
    getMyRoles()
    $scope.isOPH = ->
      Array.isArray($scope.myRoles) and ($scope.myRoles.indexOf("APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001") > -1 or $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_READ_UPDATE_1.2.246.562.10.00000000001") > -1)

    fetchHenkilotiedot()
    fetchLuokkatiedot()
    fetchSuoritukset()
    fetchOpiskeluoikeudet()
    $scope.getOppilaitos = (searchStr, obj) ->
      return []  if (typeof obj.organisaatio is "object") and obj.organisaatio.oppilaitosKoodi is searchStr
      if searchStr and searchStr.trim().match(/^\d{5}$/)
        $http.get(organisaatioServiceUrl + "/rest/organisaatio/" + searchStr).then ((result) ->
          [result.data]
        ), ->
          []

      else if searchStr and searchStr.length > 2
        $http.get(organisaatioServiceUrl + "/rest/organisaatio/hae",
          params:
            searchstr: searchStr
            organisaatioTyyppi: "Oppilaitos"
        ).then ((result) ->
          if result.data and result.data.numHits > 0
            result.data.organisaatiot
          else
            []
        ), ->
          []

      else
        []

    $scope.save = ->
      validateOppilaitoskoodit = ->
        angular.forEach $scope.luokkatiedot.concat($scope.suoritukset), ((obj) ->
          if not obj["delete"] and obj.editable and not (obj.komo and obj.komo is komo.ylioppilastutkinto)
            d = $q.defer()
            @push d
            if not obj.oppilaitos or not obj.oppilaitos.match(/^\d{5}$/)
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.oppilaitoskoodipuuttuu"
                message: "Oppilaitoskoodi puuttuu tai se on virheellinen."
                descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi"
                description: "Tarkista oppilaitoskoodi ja yritä uudelleen."

              d.reject "validationerror"
            else
              getOrganisaatio $http, obj.oppilaitos, ((organisaatio) ->
                if obj.komo
                  obj.myontaja = organisaatio.oid
                else obj.oppilaitosOid = organisaatio.oid  if obj.luokkataso
                d.resolve "validated against organisaatio"
                return
              ), ->
                MessageService.addMessage
                  type: "danger"
                  messageKey: "suoritusrekisteri.muokkaa.oppilaitostaeiloytynyt"
                  message: "Oppilaitosta ei löytynyt oppilaitoskoodilla."
                  descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi"
                  description: "Tarkista oppilaitoskoodi ja yritä uudelleen."

                d.reject "validationerror in call to organisaatio"
                return

          return
        ), validations
        return
      deleteFromArray = (obj, arr) ->
        index = arr.indexOf(obj)
        arr.splice index, 1  if index isnt -1
        return
      saveSuoritukset = ->
        angular.forEach $scope.suoritukset, ((suoritus) ->
          d = $q.defer()
          @push d
          $log.debug "save suoritus: " + suoritus.id  if suoritus.editable
          if suoritus["delete"]
            if suoritus.id
              suoritus.$remove (->
                deleteFromArray suoritus, $scope.suoritukset
                $log.debug "suoritus removed"
                d.resolve "done"
                return
              ), ->
                MessageService.addMessage
                  type: "danger"
                  messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja"
                  message: "Virhe tallennettaessa suoritustietoja."
                  descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita"
                  description: "Yritä uudelleen."

                d.reject "error deleting suoritus: " + suoritus
                return

            else
              deleteFromArray suoritus, $scope.suoritukset
              d.resolve "done"
          else
            suoritus.$save (->
              enrichSuoritus suoritus
              d.resolve "done"
              return
            ), ->
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessasuoritustietoja"
                message: "Virhe tallennettaessa suoritustietoja."
                descriptionKey: "suoritusrekisteri.muokkaa.virhesuoritusyrita"
                description: "Yritä uudelleen."

              d.reject "error saving suoritus: " + suoritus
              return

          return
        ), deferreds
        return
      saveLuokkatiedot = ->
        angular.forEach $scope.luokkatiedot, ((luokkatieto) ->
          $log.debug "save luokkatieto: " + luokkatieto.id
          d = $q.defer()
          @push d
          if luokkatieto["delete"]
            if luokkatieto.id
              luokkatieto.$remove (->
                deleteFromArray luokkatieto, $scope.luokkatiedot
                $log.info "luokkatieto removed"
                d.resolve "done"
                return
              ), ->
                MessageService.addMessage
                  type: "danger"
                  messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja"
                  message: "Virhe tallennettaessa luokkatietoja."
                  descriptionKey: "suoritusrekisteri.muokkaa.virheluokkatietoyrita"
                  description: "Yritä uudelleen."

                d.reject "error deleting luokkatieto: " + luokkatieto
                return

            else
              deleteFromArray luokkatieto, $scope.luokkatiedot
              d.resolve "done"
          else
            luokkatieto.$save (->
              enrichLuokkatieto luokkatieto
              d.resolve "done"
              return
            ), ->
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja"
                message: "Virhe tallennettaessa luokkatietoja."
                descriptionKey: "suoritusrekisteri.muokkaa.virheluokkayrita"
                description: "Yritä uudelleen."

              d.reject "error saving luokkatieto: " + luokkatieto
              return

          return
        ), deferreds
        return
      MessageService.clearMessages()
      validations = []
      validateOppilaitoskoodit()
      deferreds = []
      allValidated = $q.all(validations.map((deferred) ->
        deferred.promise
      ))
      allValidated.then (->
        saveSuoritukset()
        saveLuokkatiedot()
        allSaved = $q.all(deferreds.map((deferred) ->
          deferred.promise
        ))
        allSaved.then (->
          $log.info "all saved successfully"
          MessageService.addMessage
            type: "success"
            messageKey: "suoritusrekisteri.muokkaa.tallennettu"
            message: "Tiedot tallennettu."

          return
        ), (errors) ->
          $log.error "errors while saving: " + errors
          MessageService.addMessage
            type: "danger"
            messageKey: "suoritusrekisteri.muokkaa.tallennusepaonnistui"
            message: "Tietojen tallentaminen ei onnistunut. Yritä uudelleen."

          return

        return
      ), (errors) ->
        $log.error "validation errors: " + errors
        return

      return

    $scope.cancel = ->
      back()
      return

    $scope.checkYlioppilastutkinto = (suoritus) ->
      if suoritus.komo is komo.ylioppilastutkinto
        suoritus.myontaja = ylioppilastutkintolautakunta
        getOrganisaatio $http, ylioppilastutkintolautakunta, (org) ->
          suoritus.organisaatio = org
          return

      return

    $scope.addSuoritus = ->
      $scope.suoritukset.push new Suoritukset(
        henkiloOid: $scope.henkiloOid
        tila: "KESKEN"
        yksilollistaminen: "Ei"
        myontaja: null
        editable: true
      )
      return

    $scope.editArvosana = (suoritusId) ->
      openModal = (template, controller) ->
        isolatedScope = $scope.$new(true)
        isolatedScope.modalInstance = $modal.open(
          templateUrl: template
          controller: controller
          scope: isolatedScope
          size: "lg"
          resolve:
            suoritusId: ->
              suoritusId
        )
        $scope.modalInstance = isolatedScope.modalInstance
        return
      openModal "templates/arvosanat", "ArvosanaCtrl"
      $scope.modalInstance.result.then ((arvosanaRet) ->
        if Array.isArray(arvosanaRet)
          isolatedScope = $scope.$new(true)
          isolatedScope.modalInstance = $modal.open(
            templateUrl: "templates/duplikaatti"
            controller: "DuplikaattiCtrl"
            scope: isolatedScope
            size: "lg"
            resolve:
              arvosanat: ->
                arvosanaRet
          )
          $scope.modalInstance = isolatedScope.modalInstance
          $scope.modalInstance.result.then ((ret) ->
            MessageService.addMessage ret  if ret
            return
          ), ->
            $log.info "duplicate modal closed"
            return

        else MessageService.addMessage arvosanaRet  if arvosanaRet
        return
      ), ->
        $log.info "modal closed"
        return

      return

    $scope.editYoarvosana = (suoritusId) ->
      openModal = (template, controller) ->
        isolatedScope = $scope.$new(true)
        isolatedScope.modalInstance = $modal.open(
          templateUrl: template
          controller: controller
          scope: isolatedScope
          resolve:
            suoritusId: ->
              suoritusId
        )
        $scope.modalInstance = isolatedScope.modalInstance
        return
      openModal "templates/yoarvosanat", "YoarvosanaCtrl"
      $scope.modalInstance.result.then ((yoarvosanaRet) ->
        MessageService.addMessage yoarvosanaRet  if yoarvosanaRet
        return
      ), ->
        $log.info "yo modal closed"
        return

      return

    $scope.addLuokkatieto = ->
      $scope.luokkatiedot.push new Opiskelijat(
        henkiloOid: $scope.henkiloOid
        oppilaitosOid: null
        editable: true
      )
      return

    $scope.openDatepicker = ($event, obj, fieldName) ->
      $event.preventDefault()
      $event.stopPropagation()
      obj[fieldName] = true
      return

    initDatepicker()
]