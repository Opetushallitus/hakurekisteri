app.controller "OpiskelijatCtrl", [
  "$scope"
  "$routeParams"
  "$location"
  "$log"
  "$http"
  "$q"
  "Opiskelijat"
  "Suoritukset"
  "Arvosanat"
  "MurupolkuService"
  "MessageService"
  ($scope, $routeParams, $location, $log, $http, $q, Opiskelijat, Suoritukset, Arvosanat, MurupolkuService, MessageService) ->
    getMyRoles = ->
      $http.get("/cas/myroles",
        cache: true
      ).success((data) ->
        $scope.myRoles = angular.fromJson(data)
        return
      ).error ->
        $log.error "cannot connect to CAS"
        return

      return
    showCurrentRows = (allRows) ->
      $scope.allRows = allRows
      $scope.currentRows = allRows.slice($scope.page * $scope.pageSize, ($scope.page + 1) * $scope.pageSize)
      enrichData()
      return
    enrichData = ->
      enrichHenkilo = (row) ->
        $http.get(henkiloServiceUrl + "/resources/henkilo/" + encodeURIComponent(row.henkiloOid),
          cache: false
        ).success (henkilo) ->
          row.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + " (" + ((if henkilo.hetu then henkilo.hetu else henkilo.syntymaaika)) + ")"  if henkilo
          return

        return
      getAndEnrichOpiskelijat = (row) ->
        Opiskelijat.query { henkilo: row.henkiloOid }, (opiskelijat) ->
          ((o) ->
            getOrganisaatio $http, o.oppilaitosOid, (oppilaitos) ->
              o.oppilaitos = ((if oppilaitos.oppilaitosKoodi then oppilaitos.oppilaitosKoodi + " " else "")) + ((if oppilaitos.nimi.fi then oppilaitos.nimi.fi else oppilaitos.nimi.sv))
              return

            return
          )(o) for o in opiskelijat

          row.opiskelijatiedot = opiskelijat
          return

        return
      getAndEnrichSuoritukset = (row) ->
        Suoritukset.query { henkilo: row.henkiloOid }, (suoritukset) ->
          ((o) ->
            getOrganisaatio $http, o.myontaja, (oppilaitos) ->
              o.oppilaitos = ((if oppilaitos.oppilaitosKoodi then oppilaitos.oppilaitosKoodi + " " else "")) + " " + ((if oppilaitos.nimi.fi then oppilaitos.nimi.fi else oppilaitos.nimi.sv))
              return

            if o.komo.match(/^koulutus_\d*$/)
              getKoulutusNimi $http, o.komo, (koulutusNimi) ->
                o.koulutus = koulutusNimi
                return

            return
          )(s) for s in suoritukset

          row.suoritustiedot = suoritukset
          ((s) ->
            Arvosanat.query { suoritus: s.id }, (arvosanat) ->
              if arvosanat.length > 0
                s.hasArvosanat = true
              else
                s.noArvosanat = true
              return

            return
          )(s) for s in suoritukset

          return

        return

      ((row) ->
        if row.henkiloOid
          enrichHenkilo row
          getAndEnrichOpiskelijat row
          getAndEnrichSuoritukset row
        return
      )(row) for row in $scope.currentRows

      return
    resetPageNumbers = ->
      $scope.pageNumbers = []
      i = 0

      while i < Math.ceil($scope.allRows.length / $scope.pageSize)
        $scope.pageNumbers.push i + 1  if i is 0 or (i >= ($scope.page - 3) and i <= ($scope.page + 3)) or i is (Math.ceil($scope.allRows.length / $scope.pageSize) - 1)
        i++
      return
    $scope.loading = false
    $scope.currentRows = []
    $scope.allRows = []
    $scope.pageNumbers = []
    $scope.page = 0
    $scope.pageSize = 10
    $scope.targetOrg = ""
    $scope.myRoles = []
    $scope.henkiloTerm = $routeParams.henkilo
    $scope.organisaatioTerm = oppilaitosKoodi: ((if $routeParams.oppilaitos then $routeParams.oppilaitos else ""))
    MurupolkuService.addToMurupolku
      key: "suoritusrekisteri.opiskelijat.muru"
      text: "Opiskelijoiden haku"
    , true
    getMyRoles()
    $scope.isOPH = ->
      Array.isArray($scope.myRoles) and ($scope.myRoles.indexOf("APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001") > -1 or $scope.myRoles.indexOf("APP_SUORITUSREKISTERI_READ_UPDATE_1.2.246.562.10.00000000001") > -1)

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
        henkilo: ((if $scope.henkiloTerm then $scope.henkiloTerm else ""))
        oppilaitos: ((if $scope.organisaatioTerm then $scope.organisaatioTerm.oppilaitosKoodi else ""))

      return

    $scope.fetch = ->
      startLoading = ->
        $scope.loading = true
        return
      stopLoading = ->
        $scope.loading = false
        return
      getUniqueHenkiloOids = (oids) ->
        oids.map((o) ->
          o.henkiloOid
        ).getUnique().map (o) ->
          henkiloOid: o

      doSearch = (query) ->
        searchOpiskelijat = (o) ->
          Opiskelijat.query query, ((result) ->
            o.resolve result
            return
          ), ->
            o.reject "opiskelija query failed"
            return

          return
        searchSuoritukset = (s) ->
          suoritusQuery =
            myontaja: query.oppilaitosOid
            henkilo: ((if query.henkilo then query.henkilo else null))

          Suoritukset.query suoritusQuery, ((result) ->
            s.resolve result
            return
          ), ->
            s.reject "suoritus query failed"
            return

          return
        MessageService.clearMessages()
        if query.oppilaitosOid
          o = $q.defer()
          searchOpiskelijat o
          s = $q.defer()
          searchSuoritukset s
          $q.all([
            o.promise
            s.promise
          ]).then ((resultArrays) ->
            showCurrentRows getUniqueHenkiloOids(resultArrays.reduce((a, b) ->
              a.concat b
            ))
            resetPageNumbers()
            stopLoading()
            return
          ), (errors) ->
            $log.error errors
            MessageService.addMessage
              type: "danger"
              messageKey: "suoritusrekisteri.opiskelijat.virhehaussa"
              message: "Haussa tapahtui virhe. Yritä uudelleen."

            stopLoading()
            return

        else if query.henkilo
          showCurrentRows [henkiloOid: query.henkilo]
          resetPageNumbers()
          stopLoading()
        return
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
        allTermsReady = $q.all(searchTerms.map((d) ->
          d.promise
        ))
        allTermsReady.then (->
          doSearch
            henkilo: ((if $scope.henkilo then $scope.henkilo.oidHenkilo else null))
            oppilaitosOid: ((if $scope.organisaatioTerm then $scope.organisaatioTerm.oid else null))

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

    $scope.fetch()
]