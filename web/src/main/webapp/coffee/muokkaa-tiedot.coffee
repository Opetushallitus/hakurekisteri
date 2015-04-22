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
        $scope.disableSave = true
        $scope.komo = {}

        getKoodistoAsOptionArray $http, "kieli", "fi", $scope.kielet, "koodiArvo"
        getKoodistoAsOptionArray $http, "luokkataso", "fi", $scope.luokkatasot, "koodiArvo"
        getKoodistoAsOptionArray $http, "yksilollistaminen", "fi", $scope.yksilollistamiset, "koodiArvo", true
        getKoodistoAsOptionArray $http, "suorituksentila", "fi", $scope.tilat, "koodiArvo"

        messageLoaded = $q.defer()
        LokalisointiService.loadMessages ->
          messageLoaded.resolve()
        updateMurupolku()
        getMyRoles()

        fetchHenkilotiedot()
        fetchLuokkatiedot()
        $q.all([fetchKomos(), messageLoaded, fetchSuoritukset()]).then( (arr) ->
          loadMenuTexts()
          $scope.henkilo.suoritukset = arr[2]
        )

        fetchOpiskeluoikeudet()
        initDatepicker()

      loadMenuTexts = ->
        $scope.koulutukset = [
          {
            value: $scope.komo.ulkomainen
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.ulkomainen, "Ulkomainen")
          }
          {
            value: $scope.komo.peruskoulu
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.peruskoulu, "Peruskoulu")
          }
          {
            value: $scope.komo.lisaopetus
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.lisaopetus, "Perusopetuksen lisäopetus")
          }
          {
            value: $scope.komo.ammattistartti
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.ammattistartti, "Ammattistartti")
          }
          {
            value: $scope.komo.maahanmuuttaja
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.maahanmuuttaja, "Maahanmuuttajien ammatilliseen valmistava")
          }
          {
            value: $scope.komo.maahanmuuttajalukio
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.maahanmuuttajalukio, "Maahanmuuttajien lukioon valmistava")
          }
          {
            value: $scope.komo.valmentava
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.valmentava, "Valmentava")
          }
          {
            value: $scope.komo.ylioppilastutkinto
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.ylioppilastutkinto, "Ylioppilastutkinto")
          }
          {
            value: $scope.komo.lukio
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.lukio, "Lukio")
          }
          {
            value: $scope.komo.ammatillinen
            text: getOphMsg("suoritusrekisteri.komo." + $scope.komo.ammatillinen, "Ammatillinen")
          }
        ]

      getMyRoles = ->
        $http.get("/cas/myroles", { cache: true }).success((data) ->
          $scope.myRoles = angular.fromJson(data)
        ).error ->
          $log.error "cannot connect CAS"

      fetchKomos = ->
        komosLoaded = $q.defer()
        $http.get("rest/v1/komo", { cache: true }).success((data) ->
          $scope.komo =
            ulkomainen: data.ulkomainenkorvaavaKomoOid
            peruskoulu: data.perusopetusKomoOid
            lisaopetus: data.lisaopetusKomoOid
            ammattistartti: data.ammattistarttiKomoOid
            maahanmuuttaja: data.ammatilliseenvalmistavaKomoOid
            maahanmuuttajalukio: data.lukioonvalmistavaKomoOid
            valmentava: data.valmentavaKomoOid
            ylioppilastutkinto: data.yotutkintoKomoOid
            ammatillinen: data.ammatillinenKomoOid
            lukio: data.lukioKomoOid
          $scope.ylioppilastutkintolautakunta = data.ylioppilastutkintolautakunta
          komosLoaded.resolve()
        ).error(->komosLoaded.reject("cannot get komos"))
        return komosLoaded


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
        return Suoritukset.query({ henkilo: henkiloOid }, ((suoritukset) ->
          suoritukset.sort (a, b) -> sortByFinDateDesc a.valmistuminen, b.valmistuminen
          return $scope.henkilo.suoritukset = suoritukset
        ), ->
          MessageService.addMessage {
            type: "danger"
            message: "Suoritustietojen hakeminen ei onnistunut. Yritä uudelleen?"
            messageKey: "suoritusrekisteri.muokkaa.suoritustietojenhakeminen"
          }).$promise

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
          formatMonth: 'MMMM'
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

      $scope.validateOppilaitoskoodiFromScopeAndUpdateMyontajaInModel = (info, model, validateError) ->
        if model.vahvistettu and not info["delete"] and info.editable and not (model.komo and model.komo is $scope.komo.ylioppilastutkinto)
          d = $q.defer()
          if not info.oppilaitos or not info.oppilaitos.match(/^\d{5}$/)
            if validateError
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
              if validateError
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

      $scope.addDataScope = (scope) ->
        $scope.henkilo.dataScopes.push scope

      $scope.removeDataScope = (scope) ->
        deleteFromArray scope, $scope.henkilo.dataScopes

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
            $scope.enableSave()
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

      $scope.enableSave = () ->
        $scope.disableSave = true
        for scope in $scope.henkilo.dataScopes
          if scope.hasOwnProperty("hasChanged") && scope.hasChanged()
            $scope.disableSave = false

      $scope.addSuoritus = ->
        $scope.henkilo.suoritukset.push new Suoritukset(
          henkiloOid: henkiloOid
          tila: "KESKEN"
          yksilollistaminen: "Ei"
          myontaja: null
          vahvistettu: true
          editable: true
          valmistuminen: new Date()
        )

      $scope.isFromApplication = (oid) ->
        oid.indexOf("1.2.246.562.11") > -1

      $scope.hakemusLink = (oid) ->
        getBaseUrl() + "/haku-app/virkailija/hakemus/" + oid

      $scope.addLuokkatieto = ->
        $scope.henkilo.luokkatiedot.push new Opiskelijat(
          henkiloOid: henkiloOid
          oppilaitosOid: null
          editable: true
        )

      initializeHenkilotiedot()
]