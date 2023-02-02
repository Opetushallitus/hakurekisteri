loadHakutiedot = (hautResponse, $scope) ->
  kaudet = [text: ""]
  haut = []
  kausiExists = (kausi) ->
    kaudet.some (k) ->
      k.vuosi is kausi.vuosi and k.kausi is kausi.kausi
  resolveKausiText = (kausiUri) ->
    (if kausiUri and kausiUri.match(/^kausi_s.*/) then "Syksy" else ((if kausiUri and kausiUri.match(/^kausi_k.*/) then "Kevät" else "KAUSI PUUTTUU")))
  filteredHaut = hautResponse.filter((h) ->
    if $scope.vainKkHaut
      h.kkHaku
    else
      true
  )
  for haku in filteredHaut
    do (haku) ->
      k =
        vuosi: haku.vuosi
        kausi: haku.kausi
        text: "" + haku.vuosi + " " + resolveKausiText(haku.kausi)
      kaudet.push k  unless kausiExists(k)
      haut.push
        vuosi: haku.vuosi
        kausi: haku.kausi
        hakukausi: resolveKausiText(haku.kausi)
        oid: haku.oid
        kohdejoukkoUri: haku.kohdejoukkoUri
        text: ((if haku.nimi and haku.nimi.fi then haku.nimi.fi else ((if haku.nimi and haku.nimi.sv then haku.nimi.sv else ((if haku.nimi and haku.nimi.en then haku.nimi.en else "NIMI PUUTTUU"))))))
  sortByNimi = (a, b) ->
    if a and b and a.text and b.text
      if a.text.toLowerCase() is b.text.toLowerCase()
        return 0
      else
        return (if a.text.toLowerCase() < b.text.toLowerCase() then -1 else 1)
    0
  kaudet.sort sortByNimi
  sortByKausiAndNimi = (a, b) ->
    aKausi = a.vuosi + a.kausi
    bKausi = b.vuosi + b.kausi
    if aKausi is bKausi
      sortByNimi a, b
    else
      (if aKausi < bKausi then 1 else -1)
  haut.sort sortByKausiAndNimi
  $scope.kaudet = kaudet
  $scope.kausi = kaudet[0]  if kaudet.length > 0
  $scope.haut = haut


loadHakukohteet = (data, $scope) ->
  $scope.hakukohteet = data.map((h) ->
    koodi: h.koodiArvo
    koodiUri: h.koodiUri
    nimi: h.metadata.sort((a, b) ->
      if a.kieli and b.kieli and a.kieli isnt b.kieli
        (if a.kieli < b.kieli then -1 else 1)
      else
        0
    ).map((kielistys) ->
      kielistys.nimi
    ).join("; ")
  ).sort((a, b) ->
    if a.koodi and b.koodi and a.koodi isnt b.koodi
      (if a.koodi < b.koodi then -1 else 1)
    else
      0
  )


app.controller "HakeneetCtrl", [
  "$scope"
  "$http"
  "$modal"
  "MessageService"
  "aste"
  "haut"
  "hakukohdeData"
  "aikuhakukohdeData"
  "koutaPerusopetusHakukohdeData"
  "$interval"
  "LokalisointiService"
  ($scope, $http, $modal, MessageService, aste, haut, hakukohdeData, aikuhakukohdeData, koutaPerusopetusHakukohdeData, $interval, LokalisointiService) ->
    pollInterval = 1 * 1500 # every second

    $scope.handlePoll = (reply) ->
      if(reply.asiakirjaId)
        statusUrl = plainUrls.url("suoritusrekisteri.asiakirja.status",reply.asiakirjaId)
        $http.get(statusUrl).then ((response) ->
          status = response.status
          $scope.query = null
          if(status == 200)
            $scope.asiakirja = plainUrls.url("suoritusrekisteri.asiakirja",reply.asiakirjaId)
          else if (status == 204)
            messageKey = "suoritusrekisteri.poikkeus.eisisaltoa"
            asiakirjaError = LokalisointiService.getTranslation(messageKey) || "Siirtotiedoston luonnissa tapahtui odottamaton virhe!"
            $scope.asiakirjaError = asiakirjaError
          return
        ), ((error, status) ->
          body = error.data
          messageKey = body.message
          $scope.query = null
          asiakirjaError = LokalisointiService.getTranslation(messageKey) || "Siirtotiedoston luonnissa tapahtui odottamaton virhe!"
          if (body.parameter)
            asiakirjaError = asiakirjaError + " " + body.parameter
          $scope.asiakirjaError = asiakirjaError
          return
        )
      else if(reply.sijoitus)
        $scope.sijoitus = reply.sijoitus
        $scope.tyonalla = reply.tyonalla == true

    $scope.poll = (url) ->
      $http.post(url, $scope.query, {headers: {
        'Content-type': 'application/json'
      }}).success($scope.handlePoll)

    stop = $interval((->
      if($scope.query)
        $scope.poll(plainUrls.url("suoritusrekisteri.jonotus"))
    ), pollInterval)
    $scope.$on('$destroy', ->
      if(angular.isDefined(stop))
        $interval.cancel(stop)
    )

    $('#oppijanumero').placeholder()
    $('#hakukohdekoodi').placeholder()

    isKk = ->
      aste is "kk"

    tiedostotyypit = ->
      return [
        {
          value: "Json"
          text: "JSON"
        }
        {
          value: "Excel"
          text: "Excel"
        }
      ]  if isKk() || $scope.rajapinnanVersio > 1

      return [
        {
          value: "Json"
          text: "JSON"
        }
        {
          value: "Xml"
          text: "XML"
        }
        {
          value: "Excel"
          text: "Excel"
        }
      ]

    $scope.haut = []
    $scope.kaudet = []

    rajapintaVaihtoehdot = ->
      if(isKk())
        return [
          {
            value: 1,
            text: "1"
          }
          {
            value: 2,
            text: "2"
          }
          {
            value: 3,
            text: "3"
          }
          {
            value: 4,
            text: "4"
          }
          {
            value: 4,
            text: getOphMsg("suoritusrekisteri.tiedonsiirto.uusin")
          }
        ]
      else
        return [
          {
            value: 1,
            text: "1"
          }
          {
            value: 2,
            text: "2"
          }
          {
            value: 3,
            text: "3"
          }
          {
            value: 4,
            text: "4"
          }
          {
            value: 5,
            text: "5"
          }
          {
            value: 6,
            text: "6"
          }
          {
            value: 6,
            text: getOphMsg("suoritusrekisteri.tiedonsiirto.uusin")
          }
        ]

    $scope.rajapinnanVersiot = rajapintaVaihtoehdot()

    if(isKk())
      $scope.rajapinnanVersio = 4
    else
      $scope.rajapinnanVersio = 6

    $scope.hakuehdot = [
      {
        value: "Kaikki"
        text: LokalisointiService.getTranslation("suoritusrekisteri.tiedonsiirto.kaikkihakeneet") || "Kaikki hakeneet"
      }
      {
        value: "Hyvaksytyt"
        text: LokalisointiService.getTranslation("suoritusrekisteri.tiedonsiirto.hyvaksytyt") || "Hyväksytyt"
      }
      {
        value: "Vastaanottaneet"
        text: LokalisointiService.getTranslation("suoritusrekisteri.tiedonsiirto.vastaanottaneet") || "Paikan vastaanottaneet"
      }
    ]
    $scope.$watch("rajapinnanVersio", -> $scope.tiedostotyypit = tiedostotyypit())
    $scope.tiedostotyypit = tiedostotyypit()
    $scope.vainKkHaut = true if isKk()

    loadHakutiedot haut, $scope

    isValidTiedostotyyppi = () -> R.contains($scope.tiedostotyyppi, R.pluck('value', $scope.tiedostotyypit))


    $scope.search = ->
      MessageService.clearMessages()
      if isKk()
        if not $scope.oppijanumero and not $scope.hakukohdekoodi and not ($scope.hakukohderyhma and $scope.haku)
          unless $scope.oppijanumero
            MessageService.addMessage
              type: "danger"
              message: "Oppijanumeroa ei ole syötetty."
              messageKey: "suoritusrekisteri.hakeneet.hakunumeroaeisyotetty"
              description: "Syötä oppijanumero ja yritä uudelleen."
              descriptionKey: "suoritusrekisteri.hakeneet.hakunumeroaeisyotettyselite"

          unless $scope.hakukohdekoodi
            MessageService.addMessage
              type: "danger"
              message: "Hakukohdetta ei ole valittu."
              messageKey: "suoritusrekisteri.hakeneet.hakukohdettaeisyotetty"
              description: "Valitse hakukohde ja yritä uudelleen. Hakukohde on helpompi löytää, jos valitset ensin haun ja organisaation."
              descriptionKey: "suoritusrekisteri.hakeneet.hakukohdettaeisyotettyselite"

          unless ($scope.hakukohderyhma and $scope.haku)
            MessageService.addMessage
              type: "danger"
              message: "Hakukohderyhmää ja hakua ei ole valittu."
              messageKey: "suoritusrekisteri.hakeneet.hakukohderyhmaajahakuaeisyotetty"
              description: "Valitse hakukohderyhmä ja haku ja yritä uudelleen."
              descriptionKey: "suoritusrekisteri.hakeneet.hakukohderyhmaajahakuaeisyotetty"


          return
      else
        if not $scope.haku or not $scope.organisaatio or not $scope.hakuehto or not $scope.tiedostotyyppi or not isValidTiedostotyyppi()
          unless $scope.haku
            MessageService.addMessage
              type: "danger"
              message: "Hakua ei ole valittu."
              messageKey: "suoritusrekisteri.hakeneet.hakueivalittu"
              description: "Valitse haku ja yritä uudelleen."
              descriptionKey: "suoritusrekisteri.hakeneet.hakueivalittuselite"

          unless $scope.organisaatio
            MessageService.addMessage
              type: "danger"
              message: "Organisaatiota ei ole valittu."
              messageKey: "suoritusrekisteri.hakeneet.organisaatioeivalittu"
              description: "Valitse organisaatio ja yritä uudelleen."
              descriptionKey: "suoritusrekisteri.hakeneet.organisaatioeivalittuselite"

          unless isValidTiedostotyyppi()
            MessageService.addMessage
              type: "danger"
              message: "Tiedoston tyyppiä ei ole valittu"
              messageKey: "suoritusrekisteri.tiedonsiirto.tyyppiaeiolevalittu"

          return
      url = (if isKk() then "rest/v" + $scope.rajapinnanVersio + "/kkhakijat" else "rest/v" + $scope.rajapinnanVersio + "/hakijat")
      data = (if isKk() then {
        kk: true
        hakukohderyhma: (if $scope.hakukohderyhma then $scope.hakukohderyhma else null)
        oppijanumero: (if $scope.oppijanumero then $scope.oppijanumero else null)
        haku: (if $scope.haku then $scope.haku.oid else null)
        organisaatio: (if $scope.organisaatio then $scope.organisaatio.oid else null)
        hakukohde: (if $scope.hakukohdekoodi then $scope.hakukohdekoodi else null)
        hakuehto: $scope.hakuehto
        tyyppi: $scope.tiedostotyyppi
        tiedosto: true
        version: $scope.rajapinnanVersio
      }
      else {
        haku: (if $scope.haku then $scope.haku.oid else null)
        organisaatio: (if $scope.organisaatio then $scope.organisaatio.oid else null)
        hakukohdekoodi: (if $scope.hakukohdekoodi then $scope.hakukohdekoodiuri else null)
        hakukohdeoid: (if $scope.hakukohdeoid then $scope.hakukohdeoid else null)
        hakuehto: $scope.hakuehto
        tyyppi: $scope.tiedostotyyppi
        tiedosto: true
        version: $scope.rajapinnanVersio
      })
      $scope.asiakirja = null
      $scope.asiakirjaError = null
      $scope.query = data
      $scope.poll(plainUrls.url("suoritusrekisteri.jonotus.createNewIfErrors"))
      return

    $scope.reset = ->
      MessageService.clearMessages()
      delete $scope.kausi

      delete $scope.organisaatio

      delete $scope.hakukohdekoodi

      $scope.hakukohdenimi = ""
      $scope.hakukohdekoodiuri = ""
      $scope.hakuehto = "Kaikki"
      $scope.tiedostotyyppi = "Json"
      return

    $scope.reset()
    $scope.avaaOrganisaatiohaku = ->
      isolatedScope = $scope.$new(true)
      isolatedScope.modalInstance = $modal.open(
        templateUrl: "templates/organisaatiohaku.html"
        controller: "OrganisaatioCtrl"
        scope: isolatedScope
        size: "lg"
      )
      $scope.modalInstance = isolatedScope.modalInstance
      $scope.modalInstance.result.then ((valittu) ->
        $scope.organisaatio = valittu
        $scope.clearHakukohde()
        return
      ), ->

      return

    $scope.clearHakukohde = ->
      delete $scope.hakukohdenimi

      delete $scope.hakukohdekoodi

      $scope.updateHakukohteet()

      return

    $scope.updateHakukohteet = ->
      ## päivitetään hakukohteet listaan riippuen valitusta hausta
      if isKoutaHaku()
        loadHakukohteet koutaPerusopetusHakukohdeData, $scope
      else if isAikuHaku()
        loadHakukohteet aikuhakukohdeData, $scope
      else
        loadHakukohteet hakukohdeData, $scope

    isAikuHaku = ->
      return ($scope.haku && ($scope.haku.kohdejoukkoUri.indexOf("haunkohdejoukko_20") > -1))

    isKoutaHaku = ->
      return ($scope.haku && $scope.haku.oid.length > 30 && $scope.haku.oid.startsWith("1.2.246.562.29"))

    $scope.hakukohteet = []

    $scope.updateHakukohteet()

    sortByNimi = (a, b) ->
      return 0  if not a.nimi and not b.nimi
      return -1  unless a.nimi
      return 1  unless b.nimi
      return 0  if a.nimi.toLowerCase() is b.nimi.toLowerCase()
      (if a.nimi.toLowerCase() < b.nimi.toLowerCase() then -1 else 1)

    $http.get(window.url("organisaatio-service.ryhmat"),
      cache: true
    ).then ((res) ->
      return [] if not res.data or res.data.length is 0
      hakukohderyhmat = res.data.filter((r)-> r.ryhmatyypit.some((tyyppi) -> tyyppi == "hakukohde" or tyyppi == "hakukohderyhma")).map((r)->
        oid: r.oid
        nimi: (if r.nimi.fi then r.nimi.fi else if r.nimi.sv then r.nimi.sv else if r.nimi.en then r.nimi.en)
      )
      hakukohderyhmat.sort sortByNimi
      $scope.hakukohderyhmat = hakukohderyhmat
    ), ->
      $scope.hakukohderyhmat = []

    $scope.searchHakukohderyhma = (nimi) ->
      R.filter(((hkr) ->
        hkr.nimi.toLowerCase().indexOf(nimi.toLowerCase()) != -1), $scope.hakukohderyhmat)

    $scope.searchHakukohde = ->
      tarjontaResults = $http.get(window.url("tarjonta-service.hakukohde"),
        params:
          searchTerms: $scope.hakukohdenimi
          hakuOid: (if $scope.haku then $scope.haku.oid else null)
          organisationOid: (if $scope.organisaatio then $scope.organisaatio.oid else null)

        cache: true
      ).then ((res) ->
        console.log("Tarjonta result: ", res)
        return []  if not res.data.result or res.data.result.tuloksia is 0
        hakukohteet = res.data.result.tulokset.map((tarjoaja) ->
          tarjoaja.tulokset.map (hakukohde) ->
            oid: hakukohde.oid
            nimi: ((if tarjoaja.nimi.fi then tarjoaja.nimi.fi else ((if tarjoaja.nimi.sv then tarjoaja.nimi.sv else tarjoaja.nimi.en)))) + ": " + ((if hakukohde.nimi.fi then hakukohde.nimi.fi else ((if hakukohde.nimi.sv then hakukohde.nimi.sv else hakukohde.nimi.en)))) + ": " + hakukohde.vuosi + " " + ((if hakukohde.kausi.fi then hakukohde.kausi.fi else ((if hakukohde.kausi.sv then hakukohde.kausi.sv else hakukohde.kausi.en))))

        ).reduce((a, b) ->
          a.concat b
        )
        hakukohteet.sort sortByNimi
        console.log("Hakukohteet tarjonta ", hakukohteet)
        hakukohteet
      )

      #Kouta-internalin hakukohderajapinta haluaa, että joko haku tai organisaatio on määritelty aina,
      #joten käytetään oph-oidia jos kälistä ei ole valittu muuta
      koutaTarjoaja = if $scope.organisaatio then $scope.organisaatio.oid else if not $scope.haku then "1.2.246.562.10.00000000001" else null
      koutaResults = $http.get(window.url("kouta-internal.hakukohde.search"),
        params:
          q: $scope.hakukohdenimi
          haku: (if $scope.haku then $scope.haku.oid else null)
          tarjoaja: koutaTarjoaja

        cache: true
      ).then(((res) ->
        rawResult = res.data
        parsedResult = rawResult.map((hakukohde) ->
          oid: hakukohde.oid
          nimi: ((if hakukohde.organisaatioNimi.fi then hakukohde.organisaatioNimi.fi else ((if hakukohde.organisaatioNimi.sv then hakukohde.organisaatioNimi.sv else hakukohde.organisaatioNimi.en)))) + ": " + ((if hakukohde.nimi.fi then hakukohde.nimi.fi else ((if hakukohde.nimi.sv then hakukohde.nimi.sv else hakukohde.nimi.en))))
        )
        parsedResult
      ),
        -> console.log("Failed to get kouta hakukohtees")
        [])

      tarjontaResults.then((tarjontaHakukohtees) ->
        koutaResults.then((koutaHakukohtees) ->
          combined = [].concat(tarjontaHakukohtees).concat(koutaHakukohtees)
          combined.sort sortByNimi
          console.log("Combined results: ", combined)
          combined
        )
      )

    $scope.setHakukohderyhma = (item) ->
      $scope.hakukohderyhma = item.oid
      return

    $scope.setHakukohde = (item) ->
      $scope.hakukohdekoodi = item.oid
      return

    $scope.setHakukohdeOid = (item) ->
      console.log("Hakijatv6 set hakukohdeoid: ", item)
      $scope.hakukohdeoid = item.oid
      return

    $scope.searchHenkilo = ->
      if $scope.oppijanumero and $scope.oppijanumero.trim().match(/[0-9.]{11,30}/)
        $http.get(window.url("oppijanumerorekisteri-service.henkilo", $scope.oppijanumero.trim()),
          cache: true,
          headers: { 'External-Permission-Service': 'SURE' }
        ).then (res) ->
          $scope.henkilo = res.data
          return

      return

    $scope.searchHakukohdekoodi = (text) ->
      return [] unless text
      $scope.hakukohteet.filter (h) ->
        (h.koodi and h.koodi.indexOf(text) > -1) or (h.nimi and h.nimi.toLowerCase().indexOf(text.toLowerCase()) > -1)

    $scope.getHakukohdeWithKoodi = (koodi) ->
      return undefined unless koodi
      ($scope.hakukohteet.filter (h) ->
        h.koodi == koodi)[0]


    $scope.setHakukohdenimi = ->
      if $scope.hakukohdekoodi
        hakukohde = $scope.getHakukohdeWithKoodi($scope.hakukohdekoodi)
        if hakukohde
          $scope.hakukohdenimi = hakukohde.nimi
          $scope.hakukohdekoodiuri = hakukohde.koodiUri
        else
          $scope.hakukohdenimi = ""
          $scope.hakukohdekoodiuri = ""
      else
        $scope.hakukohdenimi = ""
        $scope.hakukohdekoodiuri = ""
      return

    $scope.hakuFilter = (haku, i) ->
      return true  if !$scope.kausi or ($scope.kausi and !$scope.kausi.kausi and !$scope.kausi.vuosi)
      $scope.kausi and haku.kausi is $scope.kausi.kausi and haku.vuosi is $scope.kausi.vuosi

    $scope.t = LokalisointiService.getTranslation
]