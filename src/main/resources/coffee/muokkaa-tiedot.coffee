app.factory "MuokkaaTiedot", [
  "$location"
  "$http"
  "$log"
  "$q"
  "clipboard"
  "Opiskelijat"
  "Suoritukset"
  "Opiskeluoikeudet"
  "LokalisointiService"
  "MessageService"
  "VirtaSuoritukset"
  ($location, $http, $log, $q, clipboard, Opiskelijat, Suoritukset, Opiskeluoikeudet, LokalisointiService, MessageService, VirtaSuoritukset) ->
    muokkaaHenkilo: (henkiloOid, $scope) ->
      initializeHenkilotiedot = ->
        $scope.henkilo = # // main data object
          suoritukset: []
          luokkatiedot: []
          opiskeluoikeudet: []
          vastaanotot: {
            opintopolku: []
            vanhat: []
          }
          dataScopes: []
        $scope.myRoles = []
        $scope.luokkatasot = []
        $scope.yksilollistamiset = []
        $scope.tilat = []
        $scope.kielet = []
        $scope.disableSave = true
        $scope.clipboardSupported = clipboard.supported
        $scope.copyToClipboard = getCopyToClipboardFn(clipboard)
        $scope.komo = {}

        getKoodistoAsOptionArray $http, "kieli", $scope.kielet, "koodiArvo"
        getKoodistoAsOptionArray $http, "luokkataso", $scope.luokkatasot, "koodiArvo"
        getKoodistoAsOptionArray $http, "yksilollistaminen", $scope.yksilollistamiset, "koodiArvo", true
        getKoodistoAsOptionArray $http, "suorituksentila", $scope.tilat, "koodiArvo"

        messageLoaded = $q.defer()
        LokalisointiService.loadMessages ->
          messageLoaded.resolve()
        getMyRoles()
        getRestrictions()

        $scope.ammatillisenKielikoeKomo = "ammatillisenKielikoe"

        fetchHenkilotiedot()
        fetchLuokkatiedot()
        $q.all([fetchKomos(), messageLoaded.promise, fetchSuoritukset()]).then( (arr) ->
          $scope.komo = arr[0]
          loadMenuTexts($scope.komo)
          $scope.henkilo.suoritukset = arr[2]
        )

        fetchOpiskeluoikeudet()
        fetchVastaanottotiedot()
        initDatepicker()

      loadMenuTexts = (komo) ->
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
            value: komo.kotitalous
            text: getOphMsg("suoritusrekisteri.komo." + komo.kotitalous, "Kotitalousopetus")
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
          {
            value: komo.erikoisammattitutkinto
            text: getOphMsg("suoritusrekisteri.komo." + komo.erikoisammattitutkinto, "Erikoisammattitutkinto")
          }
          {
            value: komo.ammatillinentutkinto
            text: getOphMsg("suoritusrekisteri.komo." + komo.ammatillinentutkinto, "Ammatillinentutkinto")
          }
          {
            value: komo.kansanopisto
            text: getOphMsg("suoritusrekisteri.komo." + komo.kansanopisto, "Kansanopiston lukuvuoden mittainen linja")
          }
          {
            value: komo.valma
            text: getOphMsg("suoritusrekisteri.komo." + komo.valma, "Ammatilliseen peruskoulutukseen valmentava koulutus")
          }
          {
            value: komo.telma
            text: getOphMsg("suoritusrekisteri.komo." + komo.telma, "Työhön ja itsenäiseen elämään valmentava koulutus")
          }
          {
            value: komo.perusopetuksenOppiaineenOppimaara
            text: getOphMsg("suoritusrekisteri.komo." + komo.perusopetuksenOppiaineenOppimaara, "Perusopetuksen oppiaineen oppimäärä")
          }
        ]
        $scope.ammatillinenKielikoeText = getOphMsg("suoritusrekisteri.komo." + $scope.ammatillisenKielikoeKomo, "Ammatillisen koulutuksen kielikoe")

      getMyRoles = ->
        $http.get(window.url("cas.myroles"), { cache: true }).success((data) ->
          $scope.myRoles = angular.fromJson(data)
        ).error ->
          $log.error "cannot connect CAS"

      getRestrictions = ->
        $http.get(window.url("suoritusrekisteri.rajoitukset", "opoUpdateGraduation"), { cache: true }).success((data) ->
          $scope.restrictionActiveSecondaryLevel = data
        ).error ->
          $log.error "cannot connect ohjausparametrit"

      fetchKomos = ->
        komosLoaded = $q.defer()
        $http.get(window.url("suoritusrekisteri.komo"), { cache: true }).success((data) ->
          $scope.ylioppilastutkintolautakunta = data.ylioppilastutkintolautakunta
          komosLoaded.resolve
            ulkomainen: data.ulkomainenkorvaavaKomoOid
            peruskoulu: data.perusopetusKomoOid
            lisaopetus: data.lisaopetusKomoOid
            kotitalous: data.lisaopetusTalousKomoOid
            ammattistartti: data.ammattistarttiKomoOid
            maahanmuuttaja: data.ammatilliseenvalmistavaKomoOid
            maahanmuuttajalukio: data.lukioonvalmistavaKomoOid
            valmentava: data.valmentavaKomoOid
            ylioppilastutkinto: data.yotutkintoKomoOid
            ammatillinen: data.ammatillinenKomoOid
            erikoisammattitutkinto: data.erikoisammattitutkintoKomoOid
            ammatillinentutkinto: data.ammatillinentutkintoKomoOid
            lukio: data.lukioKomoOid
            kansanopisto: data.kansanopistoKomoOid
            valma: data.valmaKomoOid
            telma: data.telmaKomoOid
            perusopetuksenOppiaineenOppimaara: data.perusopetuksenOppiaineenOppimaaraOid
        ).error(->komosLoaded.reject("cannot get komos"))
        return komosLoaded.promise

      $scope.fetchVirtaTiedot = ->
        id = if $scope.henkilo.hetu
          $scope.henkilo.hetu
        else
          $scope.henkilo.oidHenkilo
        VirtaSuoritukset.query { id: id }, ((virtatiedot) ->
          $scope.henkilo.virtatiedot = virtatiedot
          $scope.henkilo.virtatiedot.opiskeluoikeudet.forEach (opiskeluoikeus) ->
            if opiskeluoikeus.myontaja
              getOrganisaatio $http, opiskeluoikeus.myontaja, (organisaatio) ->
                opiskeluoikeus.oppilaitos = organisaatio.oppilaitosKoodi
                opiskeluoikeus.organisaatio = organisaatio
                return
            if opiskeluoikeus.koulutuskoodit
              opiskeluoikeus.koulutuskoodit = opiskeluoikeus.koulutuskoodit.map (koulutus) ->
                obj = koulutuskoodi: koulutus
                if koulutus.match(/^\d{6}$/)
                  getKoulutusNimi $http, "koulutus_" + koulutus, (koulutusNimi) ->
                    obj.nimi = koulutusNimi
                    return
                return obj
            return
          $scope.henkilo.virtatiedot.suoritukset.forEach (suoritus) ->
            if suoritus.myontaja
              getOrganisaatio $http, suoritus.myontaja, (organisaatio) ->
                suoritus.oppilaitos = organisaatio.oppilaitosKoodi
                suoritus.organisaatio = organisaatio
                return
            return
        )
        window.scrollTo(0, document.getElementById('application-name').getBoundingClientRect().height)

      $scope.formatMyontaja = (organisaatio, myontaja) ->
        if not organisaatio
          ""
        else
          (organisaatio.oppilaitosKoodi + ' ' + (organisaatio.nimi.fi || organisaatio.nimi.sv || organisaatio.nimi.en)) || myontaja

      $scope.formatLaji = (laji) ->
        if not laji
          ""
        formated = switch laji
          when "1" then "Tutkinto"
          when "2" then "Muu opintosuoritus"
          when "3" then "Ei huomioitava"
          when "4" then "Oppilaitoksen sisäinen"
          else laji
        formated

      $scope.formatArvosana = (arvosana, asteikko) ->
        if asteikko
          "#{arvosana} (#{asteikko})"
        else
          "#{arvosana}"

      $scope.formatKoulutukset = (koulutuskoodit) ->
        ("#{koulutus.koulutuskoodi} #{koulutus.nimi or ""}" for koulutus in koulutuskoodit).toString()


      $scope.convertOpiskeluOikeudet = (opiskeluoikeudet) ->
        ("#{opiskeluoikeus.alkuPvm or ""}\t#{opiskeluoikeus.loppuPvm or ""}\t#{$scope.formatMyontaja(opiskeluoikeus.organisaatio, opiskeluoikeus.myontaja)}\t#{$scope.formatKoulutukset(opiskeluoikeus.koulutuskoodit)}\t#{opiskeluoikeus.kieli or ""}\n" for opiskeluoikeus in opiskeluoikeudet).join("")

      $scope.convertOpintosuoritukset = (suoritukset) ->
        ("#{$scope.formatLaji(suoritus.laji)}\t#{suoritus.nimi or ""}\t#{$scope.formatArvosana(suoritus.arvosana, suoritus.asteikko)}\t#{suoritus.laajuus or ""}\t#{$scope.formatMyontaja(suoritus.organisaatio, suoritus.myontaja)}\t#{suoritus.suoritusPvm or ""}\n" for suoritus in suoritukset).join("")

      fetchHenkilotiedot = ->
        $http.get(window.url("oppijanumerorekisteri-service.henkilo", henkiloOid), { cache: false, headers: { 'External-Permission-Service': 'SURE' } }).success((henkilo) ->
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

      fetchVastaanottotiedot = ->
        $http.get(window.url("suoritusrekisteri.vastaanottotiedot", henkiloOid), { cache: false, headers: { 'External-Permission-Service': 'SURE' } }).success((vastaanottotiedot) ->
          $scope.henkilo.vastaanotot = vastaanottotiedot
          if $scope.henkilo.vastaanotot.opintopolku
            $scope.henkilo.vastaanotot.opintopolku.forEach (vastaanotto) ->
              getHakuNimi $http, vastaanotto.hakuOid, (hakuNimi) ->
                vastaanotto.haku = hakuNimi
                return
              getHakukohdeNimi $http, vastaanotto.hakukohdeOid, (hakukohdeNimi) ->
                vastaanotto.hakukohde = hakukohdeNimi
                return
              return
          return
        ).error ->
          MessageService.addMessage
            type: "danger"
            message: "Vastaanottotietojen hakeminen ei onnistunut. Yritä uudelleen?"
            messageKey: "suoritusrekisteri.muokkaa.vastaanottotietojenhakeminen"

      fetchOpiskeluoikeudet = ->
        Opiskeluoikeudet.query { henkilo: henkiloOid }, (opiskeluoikeudet) ->
          $scope.henkilo.opiskeluoikeudet = opiskeluoikeudet
          if $scope.henkilo.opiskeluoikeudet
            $scope.henkilo.opiskeluoikeudet.forEach (opiskeluoikeus) ->
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

      initDatepicker = ->
        $scope.showWeeks = true
        $scope.format = "mediumDate"
        $scope.dateOptions =
          startingDay: 1
          dayFormat: 'd'
          formatMonth: 'MMMM'
          formatYear: 'yyyy'
        return

      $scope.isOPH = () -> false
      $scope.showKoskiLink = () -> false
      $http.get(window.url("cas.myroles"), {cache: true}).success((data) ->
        $scope.myRoles = angular.fromJson(data)
        if Array.isArray($scope.myRoles)
          $scope.isOPH = () ->
            $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001") > -1 or
              $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_READ_UPDATE_1.2.246.562.10.00000000001") > -1
          $scope.showKoskiLink = () ->
            $scope.myRoles.indexOf("APP_KAYTTOOIKEUS_REKISTERINPITAJA") > -1 and
              $scope.myRoles.indexOf("APP_KOSKI_OPHKATSELIJA") > -1
      ).error ->
        $log.error "cannot connect to CAS"

      $scope.editSuoritusDisabled = (suoritus) ->
        return suoritus.komo != $scope.komo.ylioppilastutkinto && $scope.restrictionActiveSecondaryLevel && !$scope.isOPH()

      $scope.validateOppilaitoskoodiFromScopeAndUpdateModel = (info, model, validateError) ->
        if (model.vahvistettu or model.luokkataso) and not info["delete"] and info.editable and not (model.komo and (model.komo is $scope.komo.ylioppilastutkinto or $scope.isAmmatillinenKielikoe(model.komo)))
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
        window.scrollTo(0, document.getElementById('application-name').getBoundingClientRect().height)

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

      $scope.translateYksilollistaminen = (value) ->
        [koodi] = $scope.yksilollistamiset.filter (k) -> k.value is value
        if koodi
          koodi.text
        else
          value

      $scope.isFromApplication = (oid) ->
        oid.indexOf("1.2.246.562.11") > -1

      $scope.isAmmatillinenKielikoe = (komo) ->
        $scope.ammatillisenKielikoeKomo == komo;

      $scope.hakemusLink = (oid) ->
        window.url("haku-app.virkailija.hakemus", oid)

      $scope.addLuokkatieto = ->
        $scope.henkilo.luokkatiedot.push new Opiskelijat(
          henkiloOid: henkiloOid
          oppilaitosOid: null
          editable: true
        )

      initializeHenkilotiedot()
]
