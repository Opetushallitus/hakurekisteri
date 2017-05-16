app.controller "MuokkaaSuorituksetObdCtrl", [
  "$scope"
  "$routeParams"
  "$location"
  "$log"
  "$http"
  "$q"
  "$cookies"
  "$window",
  "Opiskelijat"
  "RekisteriTiedot"
  "Suoritukset"
  "Arvosanat"
  "MessageService"
  "MuokkaaTiedot"
  ($scope, $routeParams, $location, $log, $http, $q, $cookies, $window, Opiskelijat, RekisteriTiedot, Suoritukset, Arvosanat, MessageService, MuokkaaTiedot) ->

    initializeSearch = ->
      MessageService.clearMessages()
      $('#henkiloTerm').placeholder()
      $('#organisaatioTerm').placeholder()
      $('#resultFilter').placeholder()

      angular.element($window).bind 'resize', ->
        $scope.setHiddenSpacerHeight()

      $scope.$on('$viewContentLoaded', ->
        $scope.setHiddenSpacerHeight()
      )

      $scope.loading = false
      $scope.showOnlyPuuttuvat = false
      $scope.filterParam = ""
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

      stickyElem = $('.sticky-scroller')
      stickyOrigTop = undefined
      $(window).bind "scroll", ->
        if !stickyOrigTop
          if stickyElem.position()
            stickyOrigTop = stickyElem.position().top
          else
            stickyOrigTop = 0
        offset = stickyOrigTop - this.pageYOffset
        if offset < 0 then offset = 0
        stickyElem.css("top", offset + "px")

      searchTerms = []
      if $scope.henkiloTerm
        henkiloTerm = $q.defer()
        searchTerms.push henkiloTerm
        henkiloOidPattern = new RegExp("^1\\.2\\.246\\.562\\.24\\.")
        trimmedHenkiloSearchTerm = $scope.henkiloTerm.trim().toUpperCase()
        if trimmedHenkiloSearchTerm.match(henkiloOidPattern)
          henkiloSearchUrl = window.url("oppijanumerorekisteri-service.henkilo", trimmedHenkiloSearchTerm)
        else
          henkiloSearchUrl = window.url("oppijanumerorekisteri-service.byHakutermi", trimmedHenkiloSearchTerm)
        $http.get(henkiloSearchUrl,
          cache: false,
          headers: { 'External-Permission-Service': 'SURE' }
        ).success((henkilo) ->
          $scope.henkilo = henkilo
          henkiloTerm.resolve()
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
      $http.post(window.url("oppijanumerorekisteri-service.henkilotByHenkiloOidList"), Object.keys(henkiloMap), { headers: { 'External-Permission-Service': 'SURE' } }
      ).success((henkiloList, status) ->
        if status != 200 || typeof henkiloList == "string"
          $scope.loading = false
          $log.error('error resolving henkilotByHenkiloOidList')
          return
        unsorted = []
        for henkiloTieto in henkiloList
          henkilo = henkiloMap[henkiloTieto.oidHenkilo]
          henkilo.henkilo = henkiloTieto.sukunimi + ", " + henkiloTieto.etunimet
          henkilo.hetu = (if henkiloTieto.hetu then henkiloTieto.hetu else henkiloTieto.syntymaaika)
          henkilo.luokka = henkilo.opiskelijat.map((o) -> o.luokka).join(" ")
          henkilo.sortBy = "#{henkilo.luokka};#{henkilo.henkilo};#{henkilo.hetu}"
          henkilo.hasArvosana = henkilo.opiskelijat[0].arvosanat != false # undefined temporarily accepted, because the flag is missing when searching by hetu
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

    $scope.getOppilaitos = (searchStr, obj) ->
      if (obj and typeof obj.organisaatio is "object") and obj.organisaatio.oppilaitosKoodi is searchStr
        return [{organisaatio: {nimi: {fi: "suomi"}}}]
      if searchStr and searchStr.length >= 3
        $http.get(window.url("organisaatio-service.haeV2"),
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

    $scope.setHiddenSpacerHeight = () ->
      elementHeight = $("#filterForm").height()
      $("#hiddenSpacer").height(elementHeight+50)

    $scope.reset = ->
      $location.path("/opiskelijat").search {}
      return

    $scope.search = ->
      $location.path("/opiskelijat").search
        henkilo: (if $scope.henkiloTerm then $scope.henkiloTerm else "")
        oppilaitos: (if $scope.organisaatioTerm then $scope.organisaatioTerm.oppilaitosKoodi else "")
        vuosi: (if $scope.vuosiTerm then $scope.vuosiTerm else "")
      return

    $scope.resultFilter = (filterParam) ->
      $scope.filterParam = filterParam
      updateList()

    $scope.arvosanatPuuttuuFilter = () ->
      $scope.showOnlyPuuttuvat = !$scope.showOnlyPuuttuvat
      updateList()

    customFilter = (input, func) -> x for x in input when func(x)

    updateList = () ->
      lowerCased = $scope.filterParam.toLocaleLowerCase()
      if($scope.showOnlyPuuttuvat)
        $scope.allRowsFiltered = customFilter($scope.allRows, (x) -> !x.hasArvosana and x.sortBy.toLocaleLowerCase().indexOf(lowerCased) > -1)
      else if ($scope.filterParam.length > 0)
        $scope.allRowsFiltered = customFilter($scope.allRows, (x) -> x.sortBy.toLocaleLowerCase().indexOf(lowerCased) > -1)
      else
        $scope.allRowsFiltered = $scope.allRows

    $scope.valitseHenkilo = (henkiloOid) ->
      MessageService.clearMessages()
      $scope.valittuHenkiloOid = henkiloOid
      MuokkaaTiedot.muokkaaHenkilo(henkiloOid, $scope)

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
        if(result.length > 0)
          o.resolve { opiskelijat: result }
        else
          o.resolve { opiskelijat:  [ { henkiloOid: query.henkilo} ] }
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