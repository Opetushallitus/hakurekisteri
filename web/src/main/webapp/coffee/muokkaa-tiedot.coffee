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
          $scope.henkilo.luokkatiedot = luokkatiedot
          enrichLuokkatieto(l) for l in luokkatiedot
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
          $scope.henkilo.suoritukset = suoritukset
          for s in suoritukset
            enrichSuoritus(s)
        ), ->
          MessageService.addMessage {
            type: "danger"
            message: "Suoritustietojen hakeminen ei onnistunut. Yritä uudelleen?"
            messageKey: "suoritusrekisteri.muokkaa.suoritustietojenhakeminen"
          }
        return

      fetchOpiskeluoikeudet = ->
        enrich = ->
          ((opiskeluoikeus) ->
            if opiskeluoikeus.myontaja
              getOrganisaatio $http, opiskeluoikeus.myontaja, (organisaatio) ->
                opiskeluoikeus.oppilaitos = organisaatio.oppilaitosKoodi
                opiskeluoikeus.organisaatio = organisaatio
            if opiskeluoikeus.komo and opiskeluoikeus.komo.match(/^koulutus_\d*$/)
              getKoulutusNimi $http, opiskeluoikeus.komo, (koulutusNimi) ->
                opiskeluoikeus.koulutus = koulutusNimi
            return
          )(opiskeluoikeus) for opiskeluoikeus in $scope.henkilo.opiskeluoikeudet  if $scope.henkilo.opiskeluoikeudet
          return
        Opiskeluoikeudet.query { henkilo: henkiloOid }, (opiskeluoikeudet) ->
          $scope.henkilo.opiskeluoikeudet = opiskeluoikeudet
          enrich()

      initDatepicker = ->
        $scope.showWeeks = true
        $scope.format = "shortDate"
        $scope.dateOptions =
          startingDay: 1
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

      $scope.saveTiedot = ->
        for saveFn in $scope.henkilo.extraSaves
          saveFn()
        validateOppilaitoskoodit = ->
          ((obj) ->
            if not obj["delete"] and obj.editable and not (obj.komo and obj.komo is komo.ylioppilastutkinto)
              d = $q.defer()
              validationPromises.push d
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
          )(obj) for obj in $scope.henkilo.luokkatiedot.concat($scope.henkilo.suoritukset)
          return

        deleteFromArray = (obj, arr) ->
          index = arr.indexOf(obj)
          arr.splice index, 1  if index isnt -1
          return

        saveSuoritukset = ->
          ((suoritus) ->
            d = $q.defer()
            muokkaaSavePromises.push d
            if suoritus["delete"]
              if suoritus.id
                suoritus.$remove (->
                  deleteFromArray suoritus, $scope.henkilo.suoritukset
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
                deleteFromArray suoritus, $scope.henkilo.suoritukset
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
          )(suoritus) for suoritus in $scope.henkilo.suoritukset
          return

        saveLuokkatiedot = ->
          ((luokkatieto) ->
            d = $q.defer()
            muokkaaSavePromises.push d
            if luokkatieto["delete"]
              if luokkatieto.id
                luokkatieto.$remove (->
                  deleteFromArray luokkatieto, $scope.henkilo.luokkatiedot
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
                deleteFromArray luokkatieto, $scope.henkilo.luokkatiedot
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
          )(luokkatieto) for luokkatieto in $scope.henkilo.luokkatiedot
          return
        validationPromises = []
        validateOppilaitoskoodit()
        muokkaaSavePromises = []
        $q.all(validationPromises.map((d) -> d.promise)).then (->
          saveSuoritukset()
          saveLuokkatiedot()
          $q.all(muokkaaSavePromises.map((d) -> d.promise)).then (->
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

      $scope.addSave = (fn) ->
        $scope.henkilo.extraSaves.push fn

      initializeHenkilotiedot()
]