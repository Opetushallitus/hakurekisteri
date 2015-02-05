app.controller "MuokkaaSuorituksetObdCtrl", [
  "$scope"
  "$routeParams"
  "$location"
  "$log"
  "$http"
  "$q"
  "$cookies"
  "Opiskelijat"
  "Suoritukset"
  "Arvosanat"
  "MurupolkuService"
  "MessageService"
  "MuokkaaService"
  ($scope, $routeParams, $location, $log, $http, $q, $cookies, Opiskelijat, Suoritukset, Arvosanat, MurupolkuService, MessageService, MuokkaaService) ->

    initializeSearch = ->
      MessageService.clearMessages()

      $scope.vuodet = vuodet()
      $scope.henkiloTerm = $routeParams.henkilo
      $scope.organisaatioTerm = oppilaitosKoodi: (if $routeParams.oppilaitos then $routeParams.oppilaitos else "")
      $scope.vuosiTerm = $routeParams.vuosi
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

    doSearch = (query) ->
      $q.all([
        searchOpiskelijat(query).promise
        searchSuoritukset(query).promise
      ]).then ((results) ->
        showCurrentRows groupByHenkiloOid(collect(results))
      ), (errors) ->
        $log.error errors
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.opiskelijat.virhehaussa"
          message: "Haussa tapahtui virhe. Yritä uudelleen."

    showCurrentRows = (allRows) ->
      $scope.allRows = allRows
      if(allRows.length > 0)
        $scope.valitseHenkilo(allRows[0].henkiloOid)
      enrichData(allRows)
      return

    enrichData = (allRows) ->
      enrichments = []

      enrichHenkilo = (row) ->
        d = $q.defer()
        enrichments.push d
        $http.get(henkiloServiceUrl + "/resources/henkilo/" + encodeURIComponent(row.henkiloOid),
          cache: false
        ).success((henkilo) ->
          row.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + " (" + ((if henkilo.hetu then henkilo.hetu else henkilo.syntymaaika)) + ")"  if henkilo
          d.resolve()
        ).error(->
          d.reject('error resolving name for ' + row.henkiloOid)
        )

      for row in allRows
        do (row) ->
          if row.henkiloOid
            enrichHenkilo row
          return

      $q.all(enrichments.map((d) -> d.promise)).then((->
        ), (errors) ->
        $log.error(errors)
      )

    vuodet = () ->
      start = new Date().getFullYear() + 1
      end = new Date().getFullYear() - 50
      [""].concat([start..end]).map (v) ->
        "" + v

    MurupolkuService.addToMurupolku
      key: "suoritusrekisteri.opiskelijat.muru"
      text: "Opiskelijoiden haku"
    , true

    $scope.getOppilaitos = (searchStr) ->
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

    $scope.valitseHenkilo = (henkiloOid) ->
      $scope.valittuHenkiloOid = henkiloOid
      MuokkaaService.muokkaaHenkilo(henkiloOid, $scope)

    searchOpiskelijat = (query) ->
      o = $q.defer()
      Opiskelijat.query query, ((result) ->
        o.resolve { opiskelijat: result }
      ), ->
        o.reject "opiskelija query failed"
      o

    searchSuoritukset = (query) ->
      s = $q.defer()
      suoritusQuery =
        myontaja: query.oppilaitosOid
        henkilo: (if query.henkilo then query.henkilo else null)
        vuosi: (if query.vuosi then query.vuosi else null)
      Suoritukset.query suoritusQuery, ((result) ->
        s.resolve { suoritukset: result }
      ), ->
        s.reject "suoritus query failed"
      s

    groupByHenkiloOid = (obj) ->
      res = {}
      if Array.isArray obj.opiskelijat
        for o in obj.opiskelijat
          do (o) ->
            oid = o.henkiloOid
            if res[oid]
              if res[oid].opiskelijat
                res[oid].opiskelijat.push o
              else
                res[oid].opiskelijat = [o]
            else
              res[oid] =
                opiskelijat: [o]
      if Array.isArray obj.suoritukset
        for s in obj.suoritukset
          do (s) ->
            oid = s.henkiloOid
            if res[oid]
              if res[oid].suoritukset
                res[oid].suoritukset.push s
              else
                res[oid].suoritukset = [s]
            else
              res[oid] =
                suoritukset: [s]
      Object.keys(res).map (oid) ->
        obj = res[oid]
        obj.henkiloOid = oid
        obj

    initializeSearch()
]