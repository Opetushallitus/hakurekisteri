app.factory "MuokkaaTiedot", [
  "$location"
  "$http"
  "$log"
  "$q"
  "Opiskelijat"
  "Suoritukset"
  "Opiskeluoikeudet"
  "LokalisointiService"
  "MurupolkuService"
  "MessageService"
  ($location, $http, $log, $q, Opiskelijat, Suoritukset, Opiskeluoikeudet, LokalisointiService, MurupolkuService, MessageService) ->
    muokkaaHenkilo: (henkiloOid, $scope) ->

      initializeHenkilotiedot = ->
        $scope.henkilo = # // main data object
          suoritukset: []
          luokkatiedot: []
          opiskeluoikeudet: []
          extraSaves: []
        $scope.myRoles = []
        $scope.luokkatasot = []
        $scope.yksilollistamiset = []
        $scope.tilat = []
        $scope.kielet = []
        $scope.komo = komo

        LokalisointiService.loadMessages loadMenuTexts

        getKoodistoAsOptionArray $http, "kieli", "fi", $scope.kielet, "koodiArvo"
        getKoodistoAsOptionArray $http, "luokkataso", "fi", $scope.luokkatasot, "koodiArvo"
        getKoodistoAsOptionArray $http, "yksilollistaminen", "fi", $scope.yksilollistamiset, "koodiArvo", true
        getKoodistoAsOptionArray $http, "suorituksentila", "fi", $scope.tilat, "koodiArvo"

        updateMurupolku()
        getMyRoles()

        fetchHenkilotiedot()
        fetchLuokkatiedot()
        fetchSuoritukset()
        fetchOpiskeluoikeudet()
        initDatepicker()

      loadMenuTexts = ->
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
            text: getOphMsg("suoritusrekisteri.komo." + komo.maahanmuuttaja, "Maahanmuuttajien ammatilliseen valmistava")
          }
          {
            value: komo.maahanmuuttajalukio
            text: getOphMsg("suoritusrekisteri.komo." + komo.maahanmuuttajalukio, "Maahanmuuttajien lukioon valmistava")
          }
          {
            value: komo.valmentava
            text: getOphMsg("suoritusrekisteri.komo." + komo.valmentava, "Valmentava")
          }
          {
            value: komo.ylioppilastutkinto
            text: getOphMsg("suoritusrekisteri.komo." + komo.ylioppilastutkinto, "Ylioppilastutkinto")
          }
          {
            value: komo.lukio
            text: getOphMsg("suoritusrekisteri.komo." + komo.lukio, "Lukio")
          }
          {
            value: komo.ammatillinen
            text: getOphMsg("suoritusrekisteri.komo." + komo.ammatillinen, "Ammatillinen")
          }
        ]

      getMyRoles = ->
        $http.get("/cas/myroles", { cache: true }).success((data) ->
          $scope.myRoles = angular.fromJson(data)
        ).error ->
          $log.error "cannot connect to CAS"

      fetchHenkilotiedot = ->
        $http.get(henkiloServiceUrl + "/resources/henkilo/" + encodeURIComponent(henkiloOid), { cache: false }).success((henkilo) ->
          jQuery.extend($scope.henkilo, henkilo)  if henkilo
          return
        ).error ->
          MessageService.addMessage
            type: "danger"
            message: "Henkilötietojen hakeminen ei onnistunut. Yritä uudelleen?"
            messageKey: "suoritusrekisteri.muokkaa.henkilotietojenhakeminen"

      enrichLuokkatieto = (luokkatieto) ->
        if luokkatieto.oppilaitosOid
          getOrganisaatio $http, luokkatieto.oppilaitosOid, (organisaatio) ->
            luokkatieto.oppilaitos = organisaatio.oppilaitosKoodi
            luokkatieto.organisaatio = organisaatio
        luokkatieto.editable = true

      fetchLuokkatiedot = ->
        Opiskelijat.query { henkilo: henkiloOid }, ((luokkatiedot) ->
          for luokkatieto in luokkatiedot
            luokkatieto.loppuPaiva = formatDate(luokkatieto.loppuPaiva)
            enrichLuokkatieto(luokkatieto)
          $scope.henkilo.luokkatiedot = luokkatiedot
        ), ->
          MessageService.addMessage
            type: "danger"
            message: "Luokkatietojen hakeminen ei onnistunut. Yritä uudelleen?"
            messageKey: "suoritusrekisteri.muokkaa.luokkatietojenhakeminen"
        return

      enrichSuoritus = (suoritus) ->
        if suoritus.myontaja
          getOrganisaatio $http, suoritus.myontaja, (organisaatio) ->
            suoritus.oppilaitos = organisaatio.oppilaitosKoodi
            suoritus.organisaatio = organisaatio
        if suoritus.komo and suoritus.komo.match(/^koulutus_\d*$/)
          getKoulutusNimi $http, suoritus.komo, (koulutusNimi) ->
            suoritus.koulutus = koulutusNimi
        else
          suoritus.editable = true
        return

      fetchSuoritukset = ->
        Suoritukset.query { henkilo: henkiloOid }, ((suoritukset) ->
          suoritukset.sort (a, b) -> sortByFinDateDesc a.valmistuminen, b.valmistuminen
          for suoritus in suoritukset
            suoritus.valmistuminen = formatDate(suoritus.valmistuminen)
            enrichSuoritus(suoritus)
          $scope.henkilo.suoritukset = suoritukset
        ), ->
          MessageService.addMessage {
            type: "danger"
            message: "Suoritustietojen hakeminen ei onnistunut. Yritä uudelleen?"
            messageKey: "suoritusrekisteri.muokkaa.suoritustietojenhakeminen"
          }
        return

      formatDate = (input) ->
        if(input.indexOf(':') > -1)
          parts = []
          d = new Date(input)
        else if(input.indexOf('.') > -1)
          parts = input.split('.')
          d = new Date(parts[2], parts[1], parts[0])
        else if(input.indexOf('-') > -1)
          parts = input.split('-')
          d = new Date(parts[2], parts[1], parts[0])
        if parts
          ""+d.getDate()+"."+(1+d.getMonth())+"."+d.getFullYear()
        else
          "Virheellinen päivämäärä: " + d

      fetchOpiskeluoikeudet = ->
        Opiskeluoikeudet.query { henkilo: henkiloOid }, (opiskeluoikeudet) ->
          $scope.henkilo.opiskeluoikeudet = opiskeluoikeudet
          if $scope.henkilo.opiskeluoikeudet
            for opiskeluoikeus in $scope.henkilo.opiskeluoikeudet
              if opiskeluoikeus.myontaja
                getOrganisaatio $http, opiskeluoikeus.myontaja, (organisaatio) ->
                  opiskeluoikeus.oppilaitos = organisaatio.oppilaitosKoodi
                  opiskeluoikeus.organisaatio = organisaatio
              if opiskeluoikeus.komo and opiskeluoikeus.komo.match(/^koulutus_\d*$/)
                getKoulutusNimi $http, opiskeluoikeus.komo, (koulutusNimi) ->
                  opiskeluoikeus.koulutus = koulutusNimi

      initDatepicker = ->
        $scope.showWeeks = true
        $scope.format = "mediumDate"
        $scope.dateOptions =
          startingDay: 1
          formatMonth: 'MM'
          formatYear: 'yy'
        return

      updateMurupolku = ->
        MurupolkuService.addToMurupolku {
          href: "#/opiskelijat"
          key: "suoritusrekisteri.muokkaa.muru1"
          text: "Opiskelijoiden haku"
        }, true
        MurupolkuService.addToMurupolku {
          key: "suoritusrekisteri.muokkaa.muru"
          text: "Muokkaa opiskelijan tietoja"
        }, false

      $scope.isOPH = ->
        Array.isArray($scope.myRoles) and ($scope.myRoles.indexOf("APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001") > -1 or $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_READ_UPDATE_1.2.246.562.10.00000000001") > -1)

      needsValidation = (obj) -> not obj["delete"] and obj.editable and not (obj.komo and obj.komo is komo.ylioppilastutkinto)
      validateOppilaitoskoodit = ->
        $scope.henkilo.luokkatiedot.concat($scope.henkilo.suoritukset).filter(needsValidation).map (obj) ->
          d = $q.defer()
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
            ), ->
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.oppilaitostaeiloytynyt"
                message: "Oppilaitosta ei löytynyt oppilaitoskoodilla."
                descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi"
                description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
              d.reject "validationerror in call to organisaatio"
          d.promise

      deleteFromArray = (obj, arr) ->
        index = arr.indexOf(obj)
        arr.splice index, 1  if index isnt -1
        return

      saveSuoritukset = ->
        $scope.henkilo.suoritukset.map (suoritus) ->
          d = $q.defer()
          if suoritus["delete"]
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
          d.promise

      saveLuokkatiedot = ->
        $scope.henkilo.luokkatiedot.map (luokkatieto) ->
          d = $q.defer()
          if luokkatieto["delete"]
            if luokkatieto.id
              luokkatieto.$remove (->
                deleteFromArray luokkatieto, $scope.henkilo.luokkatiedot
                d.resolve "done"
              ), ->
                MessageService.addMessage
                  type: "danger"
                  messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja"
                  message: "Virhe tallennettaessa luokkatietoja."
                  descriptionKey: "suoritusrekisteri.muokkaa.virheluokkatietoyrita"
                  description: "Yritä uudelleen."

                d.reject "error deleting luokkatieto: " + luokkatieto
            else
              deleteFromArray luokkatieto, $scope.henkilo.luokkatiedot
              d.resolve "done"
          else
            luokkatieto.$save (->
              enrichLuokkatieto luokkatieto
              d.resolve "done"
            ), ->
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.virhetallennettaessaluokkatietoja"
                message: "Virhe tallennettaessa luokkatietoja."
                descriptionKey: "suoritusrekisteri.muokkaa.virheluokkayrita"
                description: "Yritä uudelleen."
              d.reject "error saving luokkatieto: " + luokkatieto
          d.promise

      $scope.addSave = (fn) ->
        $scope.henkilo.extraSaves.push fn

      $scope.saveTiedot = ->
        for saveFn in $scope.henkilo.extraSaves
          saveFn()
        $q.all(validateOppilaitoskoodit()).then (->
          $q.all(saveSuoritukset().concat(saveLuokkatiedot())).then (->
            $log.info "all saved successfully"
            MessageService.addMessage
              type: "success"
              messageKey: "suoritusrekisteri.muokkaa.tallennettu"
              message: "Tiedot tallennettu."
          ), (errors) ->
            $log.error "errors while saving: " + errors
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.tallennusepaonnistui"
              message: "Tietojen tallentaminen ei onnistunut. Yritä uudelleen."
        ), (errors) ->
          $log.error "validation errors: " + errors
        window.scrollTo(0, 0)

      $scope.checkYlioppilastutkinto = (suoritus) ->
        if suoritus.komo is komo.ylioppilastutkinto
          suoritus.myontaja = ylioppilastutkintolautakunta
          getOrganisaatio $http, ylioppilastutkintolautakunta, (org) ->
            suoritus.organisaatio = org
        return

      $scope.addSuoritus = ->
        $scope.henkilo.suoritukset.push new Suoritukset(
          henkiloOid: henkiloOid
          tila: "KESKEN"
          yksilollistaminen: "Ei"
          myontaja: null
          editable: true
        )

      $scope.addLuokkatieto = ->
        $scope.henkilo.luokkatiedot.push new Opiskelijat(
          henkiloOid: henkiloOid
          oppilaitosOid: null
          editable: true
        )

      $scope.openDatepicker = ($event, obj, fieldName) ->
        $event.preventDefault()
        $event.stopPropagation()
        obj[fieldName] = true

      initializeHenkilotiedot()
]