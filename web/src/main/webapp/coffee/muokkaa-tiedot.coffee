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
          dataScopes: []
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

      fetchLuokkatiedot = ->
        Opiskelijat.query { henkilo: henkiloOid }, ((luokkatiedot) ->
          $scope.henkilo.luokkatiedot = luokkatiedot
        ), ->
          MessageService.addMessage
            type: "danger"
            message: "Luokkatietojen hakeminen ei onnistunut. Yritä uudelleen?"
            messageKey: "suoritusrekisteri.muokkaa.luokkatietojenhakeminen"

      fetchSuoritukset = ->
        Suoritukset.query { henkilo: henkiloOid }, ((suoritukset) ->
          suoritukset.sort (a, b) -> sortByFinDateDesc a.valmistuminen, b.valmistuminen
          for suoritus in suoritukset
            suoritus.valmistuminen = formatDate(suoritus.valmistuminen)
          $scope.henkilo.suoritukset = suoritukset
        ), ->
          MessageService.addMessage {
            type: "danger"
            message: "Suoritustietojen hakeminen ei onnistunut. Yritä uudelleen?"
            messageKey: "suoritusrekisteri.muokkaa.suoritustietojenhakeminen"
          }

      formatDate = (input) ->
        if(input.indexOf('.') > -1)
          parts = input.split('.')
          d = new Date(parts[2], parts[1], parts[0])
        else if(input.indexOf('-') > -1)
          parts = input.split('-')
          d = new Date(parts[2], parts[1], parts[0])
        if parts
          ""+d.getDate()+"."+(0+d.getMonth())+"."+d.getFullYear()
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
          dayFormat: 'd'
          formatMonth: 'm'
          formatYear: 'yyyy'
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

      $scope.validateOppilaitoskoodiFromScopeAndUpdateMyontajaInModel = (info, model) ->
        if not info["delete"] and info.editable and not (model.komo and model.komo is komo.ylioppilastutkinto)
          d = $q.defer()
          if not info.oppilaitos or not info.oppilaitos.match(/^\d{5}$/)
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.oppilaitoskoodipuuttuu"
              message: "Oppilaitoskoodi puuttuu tai se on virheellinen."
              descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi"
              description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
            d.reject "validationerror"
          else
            getOrganisaatio $http, info.oppilaitos, ((organisaatio) ->
              if model.komo
                model.myontaja = organisaatio.oid
              else if model.luokkataso
                model.oppilaitosOid = organisaatio.oid
              d.resolve "validated against organisaatio"
            ), ->
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.oppilaitostaeiloytynyt"
                message: "Oppilaitosta ei löytynyt oppilaitoskoodilla."
                descriptionKey: "suoritusrekisteri.muokkaa.tarkistaoppilaitoskoodi"
                description: "Tarkista oppilaitoskoodi ja yritä uudelleen."
              d.reject "validationerror in call to organisaatio"
          [d.promise]
        else
          []

      $scope.addDataScope = (fn) ->
        $scope.henkilo.dataScopes.push fn

      saveData = ->
        promises = []
        for scope in $scope.henkilo.dataScopes
          promises = promises.concat(scope.saveData())
        promises

      validateData = ->
        promises = []
        for scope in $scope.henkilo.dataScopes
          if scope.hasOwnProperty("validateData")
            promises = promises.concat(scope.validateData())
        promises

      $scope.saveTiedot = ->
        $q.all(validateData()).then (->
          $q.all(saveData()).then ((res) ->
            if(res[0])
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
        window.scrollTo(0,
        document.getElementById('application-name').getBoundingClientRect().height)


      $scope.checkYlioppilastutkinto = (suoritus) ->
        if suoritus.komo is komo.ylioppilastutkinto
          suoritus.myontaja = ylioppilastutkintolautakunta
          getOrganisaatio $http, ylioppilastutkintolautakunta, (org) ->
            suoritus.organisaatio = org

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

      initializeHenkilotiedot()
]