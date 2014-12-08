app.controller "ArvosanaCtrl", [
  "$scope"
  "$http"
  "$q"
  "$log"
  "Arvosanat"
  "Suoritukset"
  "suoritusId"
  ($scope, $http, $q, $log, Arvosanat, Suoritukset, suoritusId) ->
    $scope.arvosanataulukko = []
    $scope.oppiaineet = []
    $scope.valinnaisuudet = [
      {
        value: false
        text: getOphMsg("suoritusrekisteri.valinnaisuus.ei", "Ei")
      }
      {
        value: true
        text: getOphMsg("suoritusrekisteri.valinnaisuus.kylla", "Kyllä")
      }
    ]
    $scope.arvosanat = [
      {
        value: ""
        text: "Ei arvosanaa"
      }
      {
        value: "4"
        text: "4"
      }
      {
        value: "5"
        text: "5"
      }
      {
        value: "6"
        text: "6"
      }
      {
        value: "7"
        text: "7"
      }
      {
        value: "8"
        text: "8"
      }
      {
        value: "9"
        text: "9"
      }
      {
        value: "10"
        text: "10"
      }
    ]
    $scope.kielet = []
    $scope.aidinkieli = []
    getKoodistoAsOptionArray $http, "kielivalikoima", "fi", $scope.kielet, "koodiArvo"
    getKoodistoAsOptionArray $http, "aidinkielijakirjallisuus", "fi", $scope.aidinkieli, "koodiArvo"
    arvosanaSort =
      AI: 10
      A1: 20
      A12: 21
      A2: 30
      A22: 31
      B1: 40
      B2: 50
      B22: 51
      B23: 52
      B3: 53
      B32: 54
      B33: 55
      MA: 60
      BI: 70
      GE: 80
      FY: 90
      KE: 100
      TE: 110
      KT: 120
      HI: 130
      YH: 140
      MU: 150
      KU: 160
      KS: 170
      LI: 180
      KO: 190
      PS: 200
      FI: 210

    Suoritukset.get
      suoritusId: suoritusId
    , ((suoritus) ->
        pohjakoulutusFilter = "onperusasteenoppiaine_1"
        pohjakoulutusFilter = "onlukionoppiaine_1"  if suoritus.komo is komo.ylioppilastutkinto
        koodistoPromises = []
        # wait
        $http.get(koodistoServiceUrl + "/rest/json/oppiaineetyleissivistava/koodi/",
          cache: true
        ).success((koodit) ->
          angular.forEach koodit, (koodi) ->
            p = $http.get(koodistoServiceUrl + "/rest/json/relaatio/sisaltyy-alakoodit/" + koodi.koodiUri,
              cache: true
            ).success((alaKoodit) ->
              $scope.oppiaineet.push
                koodi: koodi
                alaKoodit: alaKoodit

              return
            )
            koodistoPromises.push p
            return

          while koodistoPromises.length < koodit.length
            setTimeout (->
            ), 100
          allDone = $q.all(koodistoPromises)
          allDone.then (->
            findArvosana = (aine, lisatieto, arvosanat, valinnainen) ->
              i = 0

              while i < arvosanat.length
                if not arvosanat[i].taken and arvosanat[i].aine is aine and arvosanat[i].lisatieto is lisatieto and arvosanat[i].valinnainen is valinnainen
                  arvosanat[i].taken = true
                  return arvosanat[i]
                i++
              null
            getOppiaineNimi = (oppiainekoodi) ->
              oppiainekoodi.koodi.metadata.sort((a, b) ->
                (if a.kieli < b.kieli then -1 else 1)
              )[0].nimi
            iterateArvosanat = (kouluArvosanat, arvosanataulukko, aine, oppiainekoodi) ->
              i = 0

              while i < kouluArvosanat.length
                if kouluArvosanat[i].aine is aine
                  lisatieto = kouluArvosanat[i].lisatieto
                  a = arvosanataulukko[aine + ";" + lisatieto]
                  a = {}  unless a
                  a.aine = aine
                  a.aineNimi = getOppiaineNimi(oppiainekoodi)
                  a.lisatieto = lisatieto
                  arvosana = findArvosana(aine, lisatieto, kouluArvosanat, false)
                  a.arvosana = (if arvosana then arvosana.arvio.arvosana else null)
                  a.arvosanaId = (if arvosana then arvosana.id else null)
                  valinnainen = findArvosana(aine, lisatieto, kouluArvosanat, true)
                  a.arvosanaValinnainen = (if valinnainen then valinnainen.arvio.arvosana else null)
                  a.valinnainenId = (if valinnainen then valinnainen.id else null)
                  toinenValinnainen = findArvosana(aine, lisatieto, kouluArvosanat, true)
                  a.arvosanaToinenValinnainen = (if toinenValinnainen then toinenValinnainen.arvio.arvosana else null)
                  a.toinenValinnainenId = (if toinenValinnainen then toinenValinnainen.id else null)
                  arvosanataulukko[aine + ";" + lisatieto] = a
                  return true
                i++
              false
            fetchArvosanat = ->
              Arvosanat.query
                suoritus: suoritusId
              , ((arvosanat) ->
                  hasRedundantArvosana = (kouluArvosanat) ->
                    kouluArvosanat.some (a) ->
                      not a.taken

                  kouluArvosanat = arvosanat.filter((a) ->
                    a.arvio.asteikko is "4-10"
                  )
                  oppiainekoodit = $scope.oppiaineet.filter((o) ->
                    o.alaKoodit.some (alakoodi) ->
                      alakoodi.koodiUri is pohjakoulutusFilter

                  )
                  arvosanataulukko = {}
                  j = 0

                  while j < oppiainekoodit.length
                    oppiainekoodi = oppiainekoodit[j]
                    aine = oppiainekoodi.koodi.koodiArvo
                    if iterateArvosanat(kouluArvosanat, arvosanataulukko, aine, oppiainekoodi)
                      j++
                      continue
                    arvosanataulukko[aine + ";"] =
                      aine: aine
                      aineNimi: getOppiaineNimi(oppiainekoodi)
                      arvosana: ""
                    j++
                  if hasRedundantArvosana(kouluArvosanat)
                    $scope.modalInstance.close kouluArvosanat
                    return
                  $scope.arvosanataulukko = Object.keys(arvosanataulukko).map((key) ->
                    arvosanataulukko[key]
                  ).sort((a, b) ->
                    return 0  if a.aine is b.aine
                    (if arvosanaSort[a.aine] < arvosanaSort[b.aine] then -1 else 1)
                  )
                  return
                ), ->
                $scope.modalInstance.close
                  type: "danger"
                  messageKey: "suoritusrekisteri.muokkaa.arvosanat.arvosanapalveluongelma"
                  message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."

                return

              return
            fetchArvosanat()
            return
          ), ->
            $log.error "some of the calls to koodisto service failed"
            $scope.modalInstance.close
              type: "danger"
              messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma"
              message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."

            return

          return
        ).error ->
          $scope.modalInstance.close
            type: "danger"
            messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma"
            message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."

          return

        return
      ), ->
      $scope.modalInstance.close
        type: "danger"
        messageKey: "suoritusrekisteri.muokkaa.arvosanat.taustapalveluongelma"
        message: "Taustapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."

      return

    $scope.isValinnainen = (aine) ->
      $scope.oppiaineet.some (o) ->
        o.koodi.koodiArvo is aine and o.alaKoodit.some((alakoodi) ->
          alakoodi.koodiUri is "oppiaineenvalinnaisuus_1"
        )


    $scope.isKielisyys = (aine) ->
      $scope.oppiaineet.some (o) ->
        o.koodi.koodiArvo is aine and o.alaKoodit.some((alakoodi) ->
          alakoodi.koodiUri is "oppiaineenkielisyys_1"
        )


    $scope.save = ->
      removeArvosana = (arvosana, d) ->
        arvosana.$remove (->
          d.resolve "remove ok"
          return
        ), (err) ->
          $log.error "error removing, retrying to remove: " + err
          arvosana.$remove (->
            d.resolve "retry remove ok"
            return
          ), (retryErr) ->
            $log.error "retry remove failed: " + retryErr
            d.reject "retry save failed"
            return

          return

        return
      saveArvosana = (arvosana, d) ->
        arvosana.$save ((saved) ->
          d.resolve "save ok: " + saved.id
          return
        ), (err) ->
          $log.error "error saving, retrying to save: " + err
          arvosana.$save ((retriedSave) ->
            d.resolve "retry save ok: " + retriedSave.id
            return
          ), (retryErr) ->
            $log.error "retry save failed: " + retryErr
            d.reject "retry save failed"
            return

          return

        return
      saveArvosanat = ->
        angular.forEach arvosanat, ((arvosana) ->
          d = $q.defer()
          @push d
          if arvosana.id and not arvosana.arvio.arvosana
            removeArvosana arvosana, d
          else
            saveArvosana arvosana, d
          return
        ), deferreds
        return
      arvosanat = []
      i = 0

      while i < $scope.arvosanataulukko.length
        a = $scope.arvosanataulukko[i]
        if a.aine and (a.arvosana or a.arvosanaId)
          arvosanat.push new Arvosanat(
            id: a.arvosanaId
            aine: a.aine
            lisatieto: a.lisatieto
            suoritus: suoritusId
            arvio:
              arvosana: a.arvosana
              asteikko: "4-10"
          )
        if a.aine and (a.arvosanaValinnainen or a.valinnainenId)
          arvosanat.push new Arvosanat(
            id: a.valinnainenId
            aine: a.aine
            lisatieto: a.lisatieto
            suoritus: suoritusId
            arvio:
              arvosana: a.arvosanaValinnainen
              asteikko: "4-10"

            valinnainen: true
          )
        if a.aine and (a.arvosanaToinenValinnainen or a.toinenValinnainenId)
          arvosanat.push new Arvosanat(
            id: a.toinenValinnainenId
            aine: a.aine
            lisatieto: a.lisatieto
            suoritus: suoritusId
            arvio:
              arvosana: a.arvosanaToinenValinnainen
              asteikko: "4-10"

            valinnainen: true
          )
        i++
      deferreds = []
      saveArvosanat()
      allSaved = $q.all(deferreds.map((d) ->
        d.promise
      ))
      allSaved.then (->
        $log.debug "all saved"
        $scope.modalInstance.close
          type: "success"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennettu"
          message: "Arvosanat tallennettu."

        return
      ), ->
        $log.error "saving failed"
        $scope.modalInstance.close
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennuseionnistunut"
          message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen."

        return

      return

    $scope.cancel = ->
      $scope.modalInstance.close()
      return
]