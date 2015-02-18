app.controller "MuokkaaSuorituksetObdCtrl", [
  "$scope"
  "$routeParams"
  "$location"
  "$log"
  "$http"
  "$q"
  "$cookies"
  "Opiskelijat"
  "RekisteriTiedot"
  "Suoritukset"
  "Arvosanat"
  "MurupolkuService"
  "MessageService"
  "MuokkaaTiedot"
  ($scope, $routeParams, $location, $log, $http, $q, $cookies, Opiskelijat, RekisteriTiedot, Suoritukset, Arvosanat, MurupolkuService, MessageService, MuokkaaTiedot) ->

    initializeSearch = ->
      MessageService.clearMessages()

      $('#henkiloTerm').placeholder();
      $('#organisaatioTerm').placeholder();
      $('#resultFilter').placeholder();
      $scope.loading = false
      $scope.showOnlyPuuttuvat = false
      $scope.vuodet = vuodet()
      $scope.henkiloTerm = $routeParams.henkilo
      $scope.organisaatioTerm = {
        oppilaitosKoodi: (if $routeParams.oppilaitos then $routeParams.oppilaitos else "")
        nimi: {fi: "", sv: "", en: "",}
      }
      $scope.vuosiTerm = if($routeParams.vuosi) then $routeParams.vuosi else currentYear()
      $scope.allRows = []
      $scope.henkilo = null
      $scope.organisaatio = null

      searchTerms = []
      if $scope.henkiloTerm
        henkiloTerm = $q.defer()
        searchTerms.push henkiloTerm
        henkiloSearchUrl = henkiloServiceUrl + "/resources/henkilo?index=0&count=1&no=true&p=false&s=true&q=" + encodeURIComponent($scope.henkiloTerm.trim().toUpperCase())
        $http.get(henkiloSearchUrl,
          cache: false
        ).success((henkilo) ->
          if henkilo.results and henkilo.results.length is 1
            $scope.henkilo = henkilo.results[0]
            henkiloTerm.resolve()
          else
            henkiloTerm.reject()
          return
        ).error ->
          henkiloTerm.reject()
          return

      if $scope.organisaatioTerm and $scope.organisaatioTerm.oppilaitosKoodi and not $scope.organisaatioTerm.oid
        organisaatioTerm = $q.defer()
        searchTerms.push organisaatioTerm
        if $scope.organisaatioTerm.oppilaitosKoodi
          getOrganisaatio $http, $scope.organisaatioTerm.oppilaitosKoodi, ((organisaatio) ->
            $scope.organisaatioTerm = organisaatio
            organisaatioTerm.resolve()
            return
          ), ->
            organisaatioTerm.reject()
            return

      if searchTerms.length > 0
        $q.all(searchTerms.map((d) ->
          d.promise
        )).then (->
          doSearch
            henkilo: (if $scope.henkilo then $scope.henkilo.oidHenkilo else null)
            oppilaitosOid: (if $scope.organisaatioTerm then $scope.organisaatioTerm.oid else null)
            vuosi: (if $scope.vuosiTerm then $scope.vuosiTerm else null)
          return
        ), ->
          return
      return

    currentYear = () ->
      currDate = new Date()
      if (currDate.getMonth() > 6)
        ""+(currDate.getFullYear()+1)
      else
        ""+currDate.getFullYear()

    doSearch = (query) ->
      $scope.allRows = []
      $scope.loading = true
      $q.all([
        if(query.oppilaitosOid)
          searchRekisteriTiedot(query).promise
        else
          searchOpiskelijat(query).promise
      ]).then ((results) ->
        showCurrentRows collectHenkilot(collect(results))
      ), (errors) ->
        $scope.loading = false
        $log.error errors
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.opiskelijat.virhehaussa"
          message: "Haussa tapahtui virhe. Yritä uudelleen."

    showCurrentRows = (henkiloMap) ->
      $http.post(henkiloServiceUrl + "/resources/henkilo/henkilotByHenkiloOidList", Object.keys(henkiloMap)
      ).success((henkiloList) ->
        unsorted = []
        for henkiloTieto in henkiloList
          henkilo = henkiloMap[henkiloTieto.oidHenkilo]
          henkilo.henkilo = henkiloTieto.sukunimi + ", " + henkiloTieto.etunimet + " (" + ((if henkiloTieto.hetu then henkiloTieto.hetu else henkiloTieto.syntymaaika)) + ")"
          henkilo.luokka = henkilo.opiskelijat.map((o) -> o.luokka).join(" ")
          henkilo.sortBy = "#{henkilo.luokka};#{henkilo.henkilo}"
          henkilo.hasArvosana = henkilo.opiskelijat[0].arvosanat
          unsorted.push henkilo
        allRows = unsorted.sort((a, b) -> a.sortBy.localeCompare(b.sortBy))
        $scope.loading = false
        $scope.allRows = allRows
        $scope.allRowsFiltered = $scope.allRows
        if(allRows.length > 0)
          $scope.valitseHenkilo(allRows[0].henkiloOid)
      ).error(->
        $scope.loading = false
        $log.error('error resolving henkilotByHenkiloOidList')
      )
      return

    vuodet = () ->
      start = new Date().getFullYear() + 1
      end = new Date().getFullYear() - 50
      [""].concat([start..end]).map (v) ->
        "" + v

    MurupolkuService.addToMurupolku
      key: "suoritusrekisteri.opiskelijat.muru"
      text: "Opiskelijoiden haku"
    , true

    $scope.getOppilaitos = (searchStr, obj) ->
      return [{organisaatio: {nimi: {fi: "suomi"}}}]  if (obj and typeof obj.organisaatio is "object") and obj.organisaatio.oppilaitosKoodi is searchStr
      if searchStr and searchStr.length >= 3
        $http.get(organisaatioServiceUrl + "/rest/organisaatio/v2/hae",
          params:
            searchStr: searchStr.trim()
            organisaatiotyyppi: "Oppilaitos"
            aktiiviset: true
            suunnitellut: true
            lakkautetut: false
        ).then ((result) ->
          if result.data and result.data.numHits > 0
            result.data.organisaatiot
          else
            []
        ), ->
          []
      else
        []

    $scope.reset = ->
      $location.path("/muokkaa-obd").search {}
      return

    $scope.search = ->
      $location.path("/muokkaa-obd").search
        henkilo: (if $scope.henkiloTerm then $scope.henkiloTerm else "")
        oppilaitos: (if $scope.organisaatioTerm then $scope.organisaatioTerm.oppilaitosKoodi else "")
        vuosi: (if $scope.vuosiTerm then $scope.vuosiTerm else "")
      return

    customFilter = (input, func) -> x for x in input when func(x)

    $scope.updateResultFilter = (filterInput) ->
      if(filterInput.length == 0)
        $scope.allRowsFiltered = $scope.allRows
      else
        lowerCased = filterInput.toLocaleLowerCase()
        $scope.allRowsFiltered = customFilter($scope.allRows, (x) -> x.sortBy.toLocaleLowerCase().indexOf(lowerCased) > -1)

    $scope.valitseHenkilo = (henkiloOid) ->
      $scope.valittuHenkiloOid = henkiloOid
      MuokkaaTiedot.muokkaaHenkilo(henkiloOid, $scope)

    $scope.showArvosanatPuuttuu = () ->
      $scope.showOnlyPuuttuvat = !$scope.showOnlyPuuttuvat
      if($scope.showOnlyPuuttuvat)
        $scope.allRowsFiltered = customFilter($scope.allRows, (x) -> !x.hasArvosana)
      else
        $scope.allRowsFiltered = $scope.allRows

    searchRekisteriTiedot = (query) ->
      r = $q.defer()
      rekisteriTiedotQuery =
        oppilaitosOid: (if query.oppilaitosOid then query.oppilaitosOid else "")
        vuosi: (if query.vuosi then query.vuosi else null)
      RekisteriTiedot.query rekisteriTiedotQuery, ((result) ->
        r.resolve { rekisteriTiedot: result }
      ), ->
        r.reject "rekisteri tiedot query failed"
      r

    searchOpiskelijat = (query) ->
      o = $q.defer()
      Opiskelijat.query query, ((result) ->
        o.resolve { opiskelijat: result }
      ), ->
        o.reject "opiskelija query failed"
      o

    collectHenkilot = (obj) ->
      res = {}
      if Array.isArray obj.rekisteriTiedot
        for o in obj.rekisteriTiedot
          oid = o.henkilo
          henkilo = res[oid] || (res[oid]= {henkiloOid: oid, opiskelijat: []})
          henkilo.opiskelijat.push o
      if Array.isArray obj.opiskelijat
        for o in obj.opiskelijat
          oid = o.henkiloOid
          henkilo = res[oid] || (res[oid]={henkiloOid: oid, opiskelijat: []})
          henkilo.opiskelijat.push o
      res

    initializeSearch()
]