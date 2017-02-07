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


loadHakukohdekoodit = (data, $scope) ->
  $scope.hakukohdekoodit = data.map((koodi) ->
    koodi: koodi.koodiArvo
    nimi: koodi.metadata.sort((a, b) ->
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
  "hakukohdekoodit"
  "$interval"
  "LokalisointiService"
  ($scope, $http, $modal, MessageService, aste, haut, hakukohdekoodit, $interval, LokalisointiService) ->
    pollInterval = 1 * 1500 # every second

    $scope.handlePoll = (reply) ->
      if(reply.asiakirjaId)
        statusUrl = plainUrls.url("suoritusrekisteri.asiakirja.status",reply.asiakirjaId)
        $http.get(statusUrl).then((response) ->
          status = response.status
          statusText = response.statusText
          if(status == 200)
            $scope.query = null
            $scope.asiakirja = plainUrls.url("suoritusrekisteri.asiakirja",reply.asiakirjaId)
          else
            $scope.query = null
            asiakirjaError = LokalisointiService.getTranslation(statusText)
            if(asiakirjaError)
              $scope.asiakirjaError = asiakirjaError
            else
              $scope.asiakirjaError = "Siirtotiedoston luonnissa tapahtui odottamaton virhe!"
        )
      else if(reply.sijoitus)
        $scope.sijoitus = reply.sijoitus

    stop = $interval((->
      if($scope.query)
        $http.post(plainUrls.url("suoritusrekisteri.jonotus"), $scope.query, {headers: {
          'Content-type': 'application/json'
        }}).success($scope.handlePoll)
    ), pollInterval)
    $scope.$on('$destroy', ->
      if(angular.isDefined(stop))
        $interval.cancel(stop)
    )

    $('#oppijanumero').placeholder()
    $('#hakukohde').placeholder()

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
      ]  if isKk() || $scope.rajapinnanVersio is 2

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
    $scope.rajapinnanVersiot = [
      {
        value: 1,
        text: "1"
      }
      {
        value: 2,
        text: getOphMsg("suoritusrekisteri.tiedonsiirto.uusin")
      }
    ]
    $scope.rajapinnanVersio = 2
    $scope.hakuehdot = [
      {
        value: "Kaikki"
        text: "Kaikki hakeneet"
      }
      {
        value: "Hyvaksytyt"
        text: "Hyväksytyt"
      }
      {
        value: "Vastaanottaneet"
        text: "Paikan vastaanottaneet"
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
        if not $scope.oppijanumero and not $scope.hakukohde and not $scope.hakukohderyhma
          unless ($scope.oppijanumero or $scope.hakukohderyhma)
            MessageService.addMessage
              type: "danger"
              message: "Oppijanumeroa ei ole syötetty."
              messageKey: "suoritusrekisteri.hakeneet.hakunumeroaeisyotetty"
              description: "Syötä oppijanumero ja yritä uudelleen."
              descriptionKey: "suoritusrekisteri.hakeneet.hakunumeroaeisyotettyselite"

          unless ($scope.hakukohde or $scope.hakukohderyhma)
            MessageService.addMessage
              type: "danger"
              message: "Hakukohdetta ei ole valittu."
              messageKey: "suoritusrekisteri.hakeneet.hakukohdettaeisyotetty"
              description: "Valitse hakukohde ja yritä uudelleen. Hakukohde on helpompi löytää, jos valitset ensin haun ja organisaation."
              descriptionKey: "suoritusrekisteri.hakeneet.hakukohdettaeisyotettyselite"

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
      url = (if isKk() then "rest/v1/kkhakijat" else "rest/v" + $scope.rajapinnanVersio + "/hakijat")
      data = (if isKk() then {
        kk: true
        hakukohderyhma: (if $scope.hakukohderyhma then $scope.hakukohderyhma else null)
        oppijanumero: (if $scope.oppijanumero then $scope.oppijanumero else null)
        haku: (if $scope.haku then $scope.haku.oid else null)
        organisaatio: (if $scope.organisaatio then $scope.organisaatio.oid else null)
        hakukohde: (if $scope.hakukohde then $scope.hakukohde else null)
        hakuehto: $scope.hakuehto
        tyyppi: $scope.tiedostotyyppi
        tiedosto: true
      }
      else {
        haku: (if $scope.haku then $scope.haku.oid else null)
        organisaatio: (if $scope.organisaatio then $scope.organisaatio.oid else null)
        hakukohdekoodi: (if $scope.hakukohde then $scope.hakukohde else null)
        hakuehto: $scope.hakuehto
        tyyppi: $scope.tiedostotyyppi
        tiedosto: true
      })
      $scope.asiakirja = null
      $scope.asiakirjaError = null
      $scope.query = data
      return

    $scope.reset = ->
      MessageService.clearMessages()
      delete $scope.kausi

      delete $scope.organisaatio

      delete $scope.hakukohde

      $scope.hakukohdenimi = ""
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

      delete $scope.hakukohde

      return

    $scope.hakukohdekoodit = []
    loadHakukohdekoodit hakukohdekoodit, $scope

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
        hakukohderyhmat = res.data.filter((r)->r.ryhmatyypit[0] == "hakukohde").map((r)->
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
      $http.get(window.url("tarjonta-service.hakukohde"),
        params:
          searchTerms: $scope.hakukohdenimi
          hakuOid: (if $scope.haku then $scope.haku.oid else null)
          organisationOid: (if $scope.organisaatio then $scope.organisaatio.oid else null)

        cache: true
      ).then ((res) ->
        return []  if not res.data.result or res.data.result.tuloksia is 0
        hakukohteet = res.data.result.tulokset.map((tarjoaja) ->
          tarjoaja.tulokset.map (hakukohde) ->
            oid: hakukohde.oid
            nimi: ((if tarjoaja.nimi.fi then tarjoaja.nimi.fi else ((if tarjoaja.nimi.sv then tarjoaja.nimi.sv else tarjoaja.nimi.en)))) + ": " + ((if hakukohde.nimi.fi then hakukohde.nimi.fi else ((if hakukohde.nimi.sv then hakukohde.nimi.sv else hakukohde.nimi.en)))) + ": " + hakukohde.vuosi + " " + ((if hakukohde.kausi.fi then hakukohde.kausi.fi else ((if hakukohde.kausi.sv then hakukohde.kausi.sv else hakukohde.kausi.en))))

        ).reduce((a, b) ->
          a.concat b
        )
        hakukohteet.sort sortByNimi

        hakukohteet
      ), ->
        []

    $scope.setHakukohderyhma = (item) ->
      $scope.hakukohderyhma = item.oid
      return

    $scope.setHakukohde = (item) ->
      $scope.hakukohde = item.oid
      return

    $scope.searchHenkilo = ->
      if $scope.oppijanumero and $scope.oppijanumero.trim().match(/[0-9.]{11,30}/)
        $http.get(window.url("authentication-service.henkilo", $scope.oppijanumero.trim()),
          cache: true,
          headers: { 'External-Permission-Service': 'SURE' }
        ).then (res) ->
          $scope.henkilo = res.data
          return

      return

    $scope.searchHakukohdekoodi = (text) ->
      $scope.hakukohdekoodit.filter (h) ->
        return false  unless text
        (h.koodi and h.koodi.indexOf(text) > -1) or (h.nimi and h.nimi.toLowerCase().indexOf(text.toLowerCase()) > -1)


    $scope.setHakukohdenimi = ->
      if $scope.hakukohde
        nimet = $scope.searchHakukohdekoodi($scope.hakukohde)
        if nimet.length is 1
          $scope.hakukohdenimi = nimet[0].nimi
        else
          $scope.hakukohdenimi = ""
      else
        $scope.hakukohdenimi = ""
      return

    $scope.hakuFilter = (haku, i) ->
      return true  if !$scope.kausi or ($scope.kausi and !$scope.kausi.kausi and !$scope.kausi.vuosi)
      $scope.kausi and haku.kausi is $scope.kausi.kausi and haku.vuosi is $scope.kausi.vuosi

]