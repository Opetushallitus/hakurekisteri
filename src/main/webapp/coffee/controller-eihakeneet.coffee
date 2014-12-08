app.controller "EihakeneetCtrl", [
  "$scope"
  "MurupolkuService"
  "MessageService"
  "$routeParams"
  "$http"
  "$q"
  ($scope, MurupolkuService, MessageService, $routeParams, $http, $q) ->
    enrichOpiskelijat = ->
      deferredEnrichments = []
      angular.forEach $scope.allRows, (opiskelija) ->
        if opiskelija.henkiloOid
          deferredEnrichment = $q.defer()
          deferredEnrichments.push deferredEnrichment
          $http.get(henkiloServiceUrl + "/resources/henkilo/" + encodeURIComponent(opiskelija.henkiloOid),
            cache: false
          ).success((henkilo) ->
            if henkilo and henkilo.oidHenkilo is opiskelija.henkiloOid
              opiskelija.sukunimi = henkilo.sukunimi
              opiskelija.etunimet = henkilo.etunimet
            deferredEnrichment.resolve "done"
            return
          ).error ->
            deferredEnrichment.reject "error"
            return

        if opiskelija.oppilaitosOid
          deferredEnrichment = $q.defer()
          deferredEnrichments.push deferredEnrichment
          getOrganisaatio $http, opiskelija.oppilaitosOid, ((organisaatio) ->
            opiskelija.oppilaitos = organisaatio.oppilaitosKoodi + " " + ((if organisaatio.nimi.fi then organisaatio.nimi.fi else organisaatio.nimi.sv))
            deferredEnrichment.resolve "done"
            return
          ), ->
            deferredEnrichment.reject "error"
            return

        return

      enrichmentsDonePromise = $q.all(deferredEnrichments.map((enrichment) ->
        enrichment.promise
      ))
      enrichmentsDonePromise.then ->
        $scope.allRows.sort (a, b) ->
          if a.sukunimi is b.sukunimi
            if a.etunimet is b.sukunimi
              0
            else
              (if a.etunimet < b.etunimet then -1 else 1)
          else
            (if a.sukunimi < b.sukunimi then -1 else 1)

        return

      return
    fetchData = ->
      if hakuOid and oppilaitosOid
        $scope.loading = true
        deferredOpiskelijat = $q.defer()
        luokanOpiskelijat = []
        opiskelijatConfig = params:
          oppilaitosOid: oppilaitosOid

        opiskelijatConfig.params.luokka = luokka  if luokka
        $http.get("rest/v1/opiskelijat", opiskelijatConfig).success((opiskelijat) ->
          luokanOpiskelijat = opiskelijat  if opiskelijat
          deferredOpiskelijat.resolve "done"
          return
        ).error (data, status) ->
          deferredOpiskelijat.reject status
          return

        deferredHakemukset = $q.defer()
        luokanHakemukset = []
        hakemusConfig = params:
          discretionaryOnly: false
          checkAllApplications: false
          start: 0
          rows: 500
          sendingSchoolOid: oppilaitosOid
          asId: hakuOid

        hakemusConfig.params.sendingClass = luokka  if luokka
        $http.get(hakuAppServiceUrl + "/applications/listshort", hakemusConfig).success((hakemukset) ->
          if hakemukset and hakemukset.results
            luokanHakemukset = hakemukset.results.filter((h) ->
              h.state is "ACTIVE" or h.state is "INCOMPLETE"
            )
          deferredHakemukset.resolve "done"
          return
        ).error (data, status) ->
          deferredHakemukset.reject status
          return

        bothPromise = $q.all([
          deferredOpiskelijat.promise
          deferredHakemukset.promise
        ])
        bothPromise.then (->
          $scope.allRows = luokanOpiskelijat.filter(hasNoHakemus = (h) ->
              i = 0

              while i < luokanHakemukset.length
                return false  if h.henkiloOid is luokanHakemukset[i].personOid
                i++
              true
          )
          enrichOpiskelijat()
          $scope.loading = false
          return
        ), (errors) ->
          MessageService.addMessage
            type: "danger"
            message: "Virhe ladattaessa tietoja: " + errors
            description: ""

          $scope.loading = false
          return

      else
        MessageService.addMessage
          type: "danger"
          message: "Virheelliset parametrit:"
          description: "haku=" + hakuOid + ", oppilaitos=" + oppilaitosOid + ", luokka=" + luokka

      return
    hakuOid = $routeParams.haku
    oppilaitosOid = $routeParams.oppilaitos
    luokka = $routeParams.luokka
    $scope.loading = false
    $scope.allRows = []
    MurupolkuService.hideMurupolku()
    fetchData()
]