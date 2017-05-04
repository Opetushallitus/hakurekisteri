app.controller "EihakeneetCtrl", [
  "$scope"
  "MessageService"
  "$routeParams"
  "$http"
  "$q"
  ($scope, MessageService, $routeParams, $http, $q) ->
    enrichOpiskelijat = ->
      deferredEnrichments = []

      for opiskelija in $scope.allRows
        do (opiskelija) ->
          if opiskelija.henkiloOid
            deferredEnrichment = $q.defer()
            deferredEnrichments.push deferredEnrichment

            $http.get(window.url("oppijanumerorekisteri-service.henkilo", opiskelija.henkiloOid), { cache: false, headers: { 'External-Permission-Service': 'SURE' } }).success((henkilo) ->
              if henkilo and henkilo.oidHenkilo is opiskelija.henkiloOid
                opiskelija.sukunimi = henkilo.sukunimi
                opiskelija.etunimet = henkilo.etunimet
              deferredEnrichment.resolve "done"
            ).error ->
              deferredEnrichment.reject "error"

          if opiskelija.oppilaitosOid
            deferredEnrichment = $q.defer()
            deferredEnrichments.push deferredEnrichment

            getOrganisaatio $http, opiskelija.oppilaitosOid, ((organisaatio) ->
              opiskelija.oppilaitos = organisaatio.oppilaitosKoodi + " " + ((if organisaatio.nimi.fi then organisaatio.nimi.fi else organisaatio.nimi.sv))
              deferredEnrichment.resolve "done"
            ), ->
              deferredEnrichment.reject "error"

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
        opiskelijatConfig =
          params:
            oppilaitosOid: oppilaitosOid

        opiskelijatConfig.params.vuosi = vuosi if vuosi
        opiskelijatConfig.params.kausi = kausi if kausi
        opiskelijatConfig.params.luokka = luokka  if luokka

        $http.get(window.url("suoritusrekisteri.opiskelijat"), opiskelijatConfig)
          .success((opiskelijat) ->
            if opiskelijat
              luokanOpiskelijat = opiskelijat.filter((o) ->
                o.luokkataso in ["9", "10"]
              )
            deferredOpiskelijat.resolve "done"
          ).error (data, status) ->
            deferredOpiskelijat.reject status

        deferredHakemukset = $q.defer()
        luokanHakemukset = []
        hakemusConfig =
          params:
            discretionaryOnly: false
            checkAllApplications: false
            start: 0
            rows: 500
            sendingSchoolOid: oppilaitosOid
            asId: hakuOid

        hakemusConfig.params.sendingClass = luokka  if luokka

        $http.get(window.url("haku-app.listshort"), hakemusConfig)
          .success((hakemukset) ->
            if hakemukset and hakemukset.results
              luokanHakemukset = hakemukset.results.filter((h) ->
                h.state is "ACTIVE" or h.state is "INCOMPLETE"
              )
            deferredHakemukset.resolve "done"
          ).error (data, status) ->
            deferredHakemukset.reject status

        bothPromise = $q.all([
          deferredOpiskelijat.promise
          deferredHakemukset.promise
        ])
        bothPromise.then (->
          $scope.allRows = luokanOpiskelijat.filter((henkilo) ->
            not luokanHakemukset.some((hakemus) ->
              hakemus.personOid is henkilo.henkiloOid
            )
          )
          enrichOpiskelijat()
          $scope.loading = false
        ), (errors) ->
          MessageService.addMessage
            type: "danger"
            message: "Virhe ladattaessa tietoja: " + errors
            messageKey: "suoritusrekisteri.eihakeneet.virhelatauksessa"
          $scope.loading = false
      else
        MessageService.addMessage
          type: "danger"
          message: "Virheelliset parametrit:"
          messageKey: "suoritusrekisteri.eihakeneet.virheellisetparametrit"
          description: "haku=" + hakuOid + ", oppilaitos=" + oppilaitosOid + ", luokka=" + luokka
      return

    hakuOid = $routeParams.haku
    oppilaitosOid = $routeParams.oppilaitos
    vuosi = $routeParams.vuosi
    kausi = $routeParams.kausi
    luokka = $routeParams.luokka

    $scope.loading = false

    $scope.allRows = []

    fetchData()
]
