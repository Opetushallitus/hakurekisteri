app.controller "OpiskelijatCtrl", [
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
  ($scope, $routeParams, $location, $log, $http, $q, $cookies, Opiskelijat, Suoritukset, Arvosanat, MurupolkuService, MessageService) ->
    startLoading = -> $scope.loading = true
    stopLoading = -> $scope.loading = false

    showCurrentRows = (allRows) ->
      startLoading()
      $scope.allRows = allRows
      $scope.currentRows = allRows.slice($scope.page * $scope.pageSize, ($scope.page + 1) * $scope.pageSize)
      enrichData()
      return

    enrichData = () ->
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

      enrichOpiskelijat = (row) ->
        if Array.isArray row.opiskelijat
          for o in row.opiskelijat
            do (o) ->
              d = $q.defer()
              enrichments.push d
              getOrganisaatio $http, o.oppilaitosOid, ((oppilaitos) ->
                o.oppilaitos = (if oppilaitos.oppilaitosKoodi then oppilaitos.oppilaitosKoodi + " " else "") + (if oppilaitos.nimi.fi then oppilaitos.nimi.fi else oppilaitos.nimi.sv)
                d.resolve()
              ), -> d.reject('error resolving oppilaitos for ' + o.oppilaitosOid)

      enrichSuoritukset = (row) ->
        if Array.isArray row.suoritukset
          for o in row.suoritukset
            do (o) ->
              od = $q.defer()
              enrichments.push od
              getOrganisaatio $http, o.myontaja, ((oppilaitos) ->
                o.oppilaitos = (if oppilaitos.oppilaitosKoodi then oppilaitos.oppilaitosKoodi + " " else "") + " " + (if oppilaitos.nimi.fi then oppilaitos.nimi.fi else oppilaitos.nimi.sv)
                od.resolve()
              ), -> od.reject('error resolving oppilaitos for ' + o.myontaja)
              if o.komo.match(/^koulutus_\d*$/)
                kd = $q.defer()
                getKoulutusNimi $http, o.komo, ((koulutusNimi) ->
                  o.koulutus = koulutusNimi
                  kd.resolve()
                ), ->
                  kd.reject('error resolving koulutus nimi for ' + o.komo)
              Arvosanat.query { suoritus: o.id }, (arvosanat) ->
                if arvosanat.length > 0
                  o.hasArvosanat = true
                else
                  o.noArvosanat = true
                return

      for row in $scope.currentRows
        do (row) ->
          if row.henkiloOid
            enrichHenkilo row
            enrichOpiskelijat row
            enrichSuoritukset row
          return

      $q.all(enrichments.map((d) -> d.promise)).then((->
        stopLoading()
      ), (errors) ->
        $log.error(errors)
        stopLoading()
      )

      return

    resetPageNumbers = ->
      $scope.pageNumbers = []
      max = Math.ceil($scope.allRows.length / $scope.pageSize)
      for i in [0...max]
        do (i) ->
          $scope.pageNumbers.push i + 1  if i is 0 or (i >= ($scope.page - 3) and i <= ($scope.page + 3)) or i is (Math.ceil($scope.allRows.length / $scope.pageSize) - 1)
          return
      return

    vuodet = () ->
      start = new Date().getFullYear() + 1
      end = new Date().getFullYear() - 50
      [""].concat([start..end]).map (v) ->
        "" + v

    pageSizeFromCookie = () ->
      cookieValue = $cookies.opiskelijatPageSize
      if typeof cookieValue is 'string'
        try
          parseInt(cookieValue)
        catch err
          $log.error("cookie value cannot be parsed to integer: " + cookieValue)
          10
      else
        10

    $scope.loading = false
    $scope.currentRows = []
    $scope.allRows = []
    $scope.pageNumbers = []
    $scope.page = 0
    $scope.pageSize = pageSizeFromCookie()
    $scope.vuodet = vuodet()

    $scope.henkiloTerm = $routeParams.henkilo
    $scope.organisaatioTerm = oppilaitosKoodi: (if $routeParams.oppilaitos then $routeParams.oppilaitos else "")
    $scope.vuosiTerm = $routeParams.vuosi

    MurupolkuService.addToMurupolku
      key: "suoritusrekisteri.opiskelijat.muru"
      text: "Opiskelijoiden haku"
    , true

    $scope.getOppilaitos = (searchStr) ->
      if searchStr and searchStr.length >= 3
        $http.get(organisaatioServiceUrl + "/rest/organisaatio/hae",
          params:
            searchstr: searchStr.trim()
            organisaatioTyyppi: "Oppilaitos"
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
      $location.path("/opiskelijat").search {}
      return

    $scope.search = ->
      $location.path("/opiskelijat").search
        henkilo: (if $scope.henkiloTerm then $scope.henkiloTerm else "")
        oppilaitos: (if $scope.organisaatioTerm then $scope.organisaatioTerm.oppilaitosKoodi else "")
        vuosi: (if $scope.vuosiTerm then $scope.vuosiTerm else "")
      return

    $scope.fetch = ->
      doSearch = (query) ->
        MessageService.clearMessages()

        searchOpiskelijat = (o) ->
          Opiskelijat.query query, ((result) ->
            o.resolve { opiskelijat: result }
          ), ->
            o.reject "opiskelija query failed"

        searchSuoritukset = (s) ->
          suoritusQuery =
            myontaja: query.oppilaitosOid
            henkilo: (if query.henkilo then query.henkilo else null)
            vuosi: (if query.vuosi then query.vuosi else null)
          Suoritukset.query suoritusQuery, ((result) ->
            s.resolve { suoritukset: result }
          ), ->
            s.reject "suoritus query failed"

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

        o = $q.defer()
        searchOpiskelijat o
        s = $q.defer()
        searchSuoritukset s
        $q.all([
          o.promise
          s.promise
        ]).then ((results) ->
          showCurrentRows groupByHenkiloOid(collect(results))
          resetPageNumbers()
        ), (errors) ->
          $log.error errors
          MessageService.addMessage
            type: "danger"
            messageKey: "suoritusrekisteri.opiskelijat.virhehaussa"
            message: "Haussa tapahtui virhe. Yritä uudelleen."
          stopLoading()

      $scope.currentRows = []
      $scope.allRows = []
      $scope.henkilo = null
      $scope.organisaatio = null

      startLoading()

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
          stopLoading()
          return
      else
        stopLoading()
      return

    $scope.nextPage = ->
      if ($scope.page + 1) * $scope.pageSize < $scope.allRows.length
        $scope.page++
      else
        $scope.page = 0
      resetPageNumbers()
      showCurrentRows $scope.allRows
      return

    $scope.prevPage = ->
      if $scope.page > 0 and ($scope.page - 1) * $scope.pageSize < $scope.allRows.length
        $scope.page--
      else
        $scope.page = Math.floor($scope.allRows.length / $scope.pageSize)
      resetPageNumbers()
      showCurrentRows $scope.allRows
      return

    $scope.showPageWithNumber = (pageNum) ->
      $scope.page = (if pageNum > 0 then (pageNum - 1) else 0)
      resetPageNumbers()
      showCurrentRows $scope.allRows
      return

    $scope.setPageSize = (newSize) ->
      $scope.pageSize = newSize
      $cookies.opiskelijatPageSize = "" + newSize
      $scope.page = 0
      resetPageNumbers()
      showCurrentRows $scope.allRows

    $scope.fetch()
]