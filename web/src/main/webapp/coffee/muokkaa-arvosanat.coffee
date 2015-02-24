app.controller "MuokkaaArvosanat", [
  "$scope"
  "$http"
  "$q"
  "$modal"
  "$log"
  "Arvosanat"
  "Suoritukset"
  "MessageService"
  ($scope, $http, $q, $modal, $log, Arvosanat, Suoritukset, MessageService) ->
    suoritusId = $scope.suoritus.id
    $scope.arvosanataulukko = []
    $scope.oppiaineet = []
    $scope.arvosanat = []
    $scope.kielet = []
    $scope.aidinkieli = []

    getKoodistoAsOptionArray $http, "arvosanat", "fi", $scope.arvosanat, "koodiArvo"
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

    openDuplicateModal = (arvosanaRet) ->
      isolatedScope = $scope.$new(true)
      isolatedScope.modalInstance = $modal.open(
        templateUrl: "templates/duplikaatti"
        controller: "DuplikaattiCtrl"
        scope: isolatedScope
        size: "lg"
        resolve:
          arvosanat: ->
            arvosanaRet
      )
      isolatedScope.modalInstance.result.then ((ret) ->
        MessageService.addMessage ret if ret
        return
      ), ->

    Suoritukset.get { suoritusId: suoritusId }, ((suoritus) ->
      pohjakoulutusFilter = "onperusasteenoppiaine_1"
      pohjakoulutusFilter = "onlukionoppiaine_1"  if suoritus.komo is komo.lukio

      $http.get(koodistoServiceUrl + "/rest/json/oppiaineetyleissivistava/koodi/", { cache: true }).success((koodit) ->
        koodistoPromises = koodit.map (koodi) ->
          $http.get(koodistoServiceUrl + "/rest/json/relaatio/sisaltyy-alakoodit/" + koodi.koodiUri,
            cache: true
          ).success((alaKoodit) ->
            $scope.oppiaineet.push
              koodi: koodi
              alaKoodit: alaKoodit
          )

        $q.all(koodistoPromises).then (->
          findArvosana = (aine, lisatieto, arvosanat, valinnainen) ->
            return ((arvosana) ->
              arvosana.taken = true
              return arvosana
            )(arvosana) for arvosana in arvosanat when not arvosana.taken and arvosana.aine is aine and arvosana.lisatieto is lisatieto and arvosana.valinnainen is valinnainen

            null

          getOppiaineNimi = (oppiainekoodi) ->
            oppiainekoodi.koodi.metadata.sort((a, b) ->
              (if a.kieli < b.kieli then -1 else 1)
            )[0].nimi

          iterateArvosanat = (kouluArvosanat, arvosanataulukko, aine, oppiainekoodi) ->
            return ((lisatieto) ->
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
            )(arvosana.lisatieto) for arvosana in kouluArvosanat when arvosana.aine is aine
            false

          fetchArvosanat = ->
            Arvosanat.query { suoritus: suoritusId }, ((arvosanat) ->
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

              for oppiainekoodi in oppiainekoodit
                do (oppiainekoodi) ->
                  aine = oppiainekoodi.koodi.koodiArvo
                  if iterateArvosanat(kouluArvosanat, arvosanataulukko, aine, oppiainekoodi)
                    return
                  arvosanataulukko[aine + ";"] =
                    aine: aine
                    aineNimi: getOppiaineNimi(oppiainekoodi)
                    arvosana: "Ei arvosanaa"

              if hasRedundantArvosana(kouluArvosanat)
                openDuplicateModal(kouluArvosanat)
                return

              $scope.arvosanataulukko = Object.keys(arvosanataulukko).map((key) ->
                arvosanataulukko[key]
              ).sort((a, b) ->
                return 0  if a.aine is b.aine
                (if arvosanaSort[a.aine] < arvosanaSort[b.aine] then -1 else 1)
              )
            ), ->
              MessageService.addMessage
                type: "danger"
                messageKey: "suoritusrekisteri.muokkaa.arvosanat.arvosanapalveluongelma"
                message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."

          fetchArvosanat()
        ), ->
          MessageService.addMessage
            type: "danger"
            messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma"
            message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
      ).error ->
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma"
          message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
    ), ->
      MessageService.addMessage
        type: "danger"
        messageKey: "suoritusrekisteri.muokkaa.arvosanat.taustapalveluongelma"
        message: "Taustapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."

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

    saveArvosanatNormaali = ->
      removeArvosana = (arvosana, d) ->
        arvosana.$remove (->
          d.resolve "remove ok"
        ), (err) ->
          $log.error "error removing " + err
          d.reject "remove failed"

      saveArvosana = (arvosana, d) ->
        arvosana.$save ((saved) ->
          d.resolve "save ok: " + saved.id
        ), (err) ->
          $log.error "error saving, retrying to save: " + err
          d.reject "save failed"

      saveArvosanat = (arvosanat) ->
        arvosanat.map (arvosana) ->
          d = $q.defer()
          if arvosana.id and arvosana.arvio.arvosana is "Ei arvosanaa"
            removeArvosana arvosana, d
          else
            saveArvosana arvosana, d
          d.promise

      arvosanat = []
      for a in $scope.arvosanataulukko
        if a.aine and ((a.arvosana and a.arvosana isnt "Ei arvosanaa") or a.arvosanaId)
          arvosanat.push new Arvosanat(
            id: a.arvosanaId
            aine: a.aine
            lisatieto: a.lisatieto
            suoritus: suoritusId
            arvio:
              arvosana: a.arvosana
              asteikko: "4-10"
          )
        if a.aine and ((a.arvosanaValinnainen and a.arvosanaValinnainen isnt "Ei arvosanaa") or a.valinnainenId)
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
        if a.aine and ((a.arvosanaToinenValinnainen and a.arvosanaToinenValinnainen isnt "Ei arvosanaa") or a.toinenValinnainenId)
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
      $q.all(saveArvosanat(arvosanat)).then (->
      ), ->
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennuseionnistunut"
          message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen."
      []

    $scope.addSave(saveArvosanatNormaali)
]
