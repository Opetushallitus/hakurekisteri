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
  "MurupolkuService"
  "MessageService"
  "aste"
  "haut"
  "hakukohdekoodit"
  ($scope, $http, $modal, MurupolkuService, MessageService, aste, haut, hakukohdekoodit) ->
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
      ]  if isKk()

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
    $scope.tiedostotyypit = tiedostotyypit()
    $scope.vainKkHaut = true  if isKk()

    if isKk()
      MurupolkuService.addToMurupolku
        key: "suoritusrekisteri.hakeneet.muru.kk"
        text: "Hakeneet ja valitut opiskelijat (KK)"
      , true
    else
      MurupolkuService.addToMurupolku
        key: "suoritusrekisteri.hakeneet.muru"
        text: "Hakeneet ja valitut opiskelijat"
      , true

    loadHakutiedot haut, $scope

    $scope.search = ->
      MessageService.clearMessages()
      if isKk()
        if not $scope.oppijanumero and not $scope.hakukohde
          unless $scope.oppijanumero
            MessageService.addMessage
              type: "danger"
              message: "Oppijanumeroa ei ole syötetty."
              description: "Syötä oppijanumero ja yritä uudelleen."

          unless $scope.hakukohde
            MessageService.addMessage
              type: "danger"
              message: "Hakukohdetta ei ole valittu."
              description: "Valitse hakukohde ja yritä uudelleen. Hakukohde on helpompi löytää, jos valitset ensin haun ja organisaation."

          return
      else
        if not $scope.haku or not $scope.organisaatio or not $scope.hakuehto or not $scope.tiedostotyyppi
          unless $scope.haku
            MessageService.addMessage
              type: "danger"
              message: "Hakua ei ole valittu."
              description: "Valitse haku ja yritä uudelleen."

          unless $scope.organisaatio
            MessageService.addMessage
              type: "danger"
              message: "Organisaatiota ei ole valittu."
              description: "Valitse organisaatio ja yritä uudelleen."

          return
      url = (if isKk() then "rest/v1/kkhakijat" else "rest/v1/hakijat")
      data = (if isKk() then {
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
      $scope.fileLoading = true
      $.fileDownload(url,
        data: data
      ).done(->
        $scope.$apply ->
          delete $scope.fileLoading

          return

        return
      ).fail ->
        $scope.$apply ->
          MessageService.addMessage
            type: "danger"
            message: "Tiedoston lataaminen epäonnistui."
            description: "Palvelussa saattaa olla kuormaa. Yritä hetken kuluttua uudelleen."

          delete $scope.fileLoading

          return

        return

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
        templateUrl: "templates/organisaatiohaku"
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
    $scope.searchHakukohde = ->
      $http.get(tarjontaServiceUrl + "/rest/v1/hakukohde/search",
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
        hakukohteet.sort (a, b) ->
          return 0  if not a.nimi and not b.nimi
          return -1  unless a.nimi
          return 1  unless b.nimi
          return 0  if a.nimi.toLowerCase() is b.nimi.toLowerCase()
          (if a.nimi.toLowerCase() < b.nimi.toLowerCase() then -1 else 1)

        hakukohteet
      ), ->
        []


    $scope.setHakukohde = (item) ->
      $scope.hakukohde = item.oid
      return

    $scope.searchHenkilo = ->
      if $scope.oppijanumero and $scope.oppijanumero.trim().match(/[0-9.]{11,30}/)
        $http.get(henkiloServiceUrl + "/resources/henkilo/" + encodeURIComponent($scope.oppijanumero.trim()),
          cache: true
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