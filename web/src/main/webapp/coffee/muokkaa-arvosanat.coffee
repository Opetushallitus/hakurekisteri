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
    suoritus = $scope.suoritus
    suoritusId = suoritus.id
    $scope.arvosanataulukko = []
    koodistoOppiaineLista = []
    suorituksenArvosanat = []
    arvosanatModified = []
    $scope.arvosanat = []
    $scope.kielet = []
    $scope.aidinkieli = []

    getKoodistoAsOptionArray $http, "arvosanat", "fi", $scope.arvosanat, "koodiArvo"
    getKoodistoAsOptionArray $http, "kielivalikoima", "fi", $scope.kielet, "koodiArvo"
    getKoodistoAsOptionArray $http, "aidinkielijakirjallisuus", "fi", $scope.aidinkieli, "koodiArvo"

    arvosanaSort = {}
    arvosanaOrder = ["AI", "A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33", "MA", "BI", "GE",
                     "FY", "KE", "TE", "KT", "HI", "YH", "MU", "KU", "KS", "LI", "KO", "PS", "FI"]
    arvosanaOrder.forEach (k, i) -> arvosanaSort[k] = i

    updateOppiaineLista = ->
      d = $q.defer()
      $http.get(koodistoServiceUrl + "/rest/json/oppiaineetyleissivistava/koodi/", {cache: true}).success((koodit) ->
        koodiPromises = koodit.map (koodi) ->
          koodi.metadata.sort((a, b) ->
            (if a.kieli < b.kieli then -1 else 1)
          )
          $http.get(koodistoServiceUrl + "/rest/json/relaatio/sisaltyy-alakoodit/" + koodi.koodiUri,
            {cache: true}).success((alaKoodit) ->
            koodistoOppiaineLista.push
              koodi: koodi
              alaKoodit: alaKoodit
          )
        $q.all(koodiPromises).then ( ->
          d.resolve "done"
        ), ->
          d.reject "error"
          MessageService.addMessage
            type: "danger"
            messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma"
            message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
      ).error ->
        d.reject "error"
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.koodistopalveluongelma"
          message: "Koodistopalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
      d.promise

    getSuorituksenArvosanat = ->
      d = $q.defer()
      Arvosanat.query {suoritus: suoritusId}, ((arvosanatData) ->
        suorituksenArvosanat = arvosanatData.map((a) -> new Arvosanat(a)).filter (a) ->
          a.arvio.asteikko is "4-10"
        arvosanatModified = suorituksenArvosanat.map (a) -> changeDetection(a)
        d.resolve "done"
      ), ->
        d.reject "error"
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.arvosanapalveluongelma"
          message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
      d.promise

    addArvosanaIfNeeded = (list, valinnainen, maxCount, aineRivi) ->
      if list.length >= maxCount || list.some( (a) -> ( a.arvio.arvosana == "Ei arvosanaa" ))
        return
      arvosanaTmp = new Arvosanat(
        aine: aineRivi.aine
        lisatieto: aineRivi.lisatieto
        suoritus: suoritusId
        myonnetty: aineRivi.myonnetty
        arvio:
          arvosana: "Ei arvosanaa"
          asteikko: "4-10"
        valinnainen: valinnainen
      )
      list.push arvosanaTmp
      arvosanatModified.push changeDetection(arvosanaTmp)

    updateArvosanaTaulukko = (taulukko) ->
      rowClass = arrayCarousel("oddRow", "")
      rowClass.next()
      previousAine = null
      for aineRivi in taulukko
        if aineRivi.aine != previousAine
          rowClass.next()
        aineRivi.rowClass = rowClass.value
        previousAine = aineRivi.aine
        for arvosana in aineRivi.pakolliset.concat(aineRivi.valinnaiset)
          arvosana.lisatieto = aineRivi.lisatieto
          arvosana.myonnetty = aineRivi.myonnetty
        addArvosanaIfNeeded aineRivi.pakolliset, false, 1, aineRivi
        if aineRivi.hasValinnaisuus
          addArvosanaIfNeeded aineRivi.valinnaiset, true, 3, aineRivi

    $q.all([updateOppiaineLista(), getSuorituksenArvosanat()]).then (->
      lukio = suoritus.komo is komo.lukio
      oppiainekoodit = koodistoOppiaineLista.filter (o) ->
          pohjakoulutusFilter = "onperusasteenoppiaine_1"
          if lukio
            pohjakoulutusFilter = "onlukionoppiaine_1"
          o.alaKoodit.some (alakoodi) ->
            pohjakoulutusFilter == alakoodi.koodiUri

      collectToMap = (list, keyFn) ->
        ret = {}
        for i in list
          k = keyFn(i)
          (ret[k] || (ret[k] = [])).push i
        ret

      resolveAineNimi = (aine) ->
        for oppiaine in oppiainekoodit
          if aine == oppiaine.koodi.koodiArvo
            return oppiaine.koodi.metadata[0].nimi
        return aine

      makeAineRivi = (aine, arvosanat, myonnetty, lisatieto) ->
        {
        aine: aine
        pakolliset: (arvosanat.filter (a) ->  !a.valinnainen)
        valinnaiset: (arvosanat.filter (a) -> a.valinnainen)
        myonnetty: myonnetty
        lisatieto: lisatieto
        hasKielisyys: hasKielisyys(aine)
        hasValinnaisuus: hasValinnaisuus(aine)
        aineNimi: resolveAineNimi(aine)
        }

      sortByAine = (arvosanataulukko) ->
        arvosanataulukko.sort (a, b) ->
          if a.aine is b.aine
            if a.myonnetty && b.myonnetty
              aPvm = $scope.parseFinDate(a.myonnetty)
              bPvm = $scope.parseFinDate(b.myonnetty)
              if aPvm > bPvm then 1 else if aPvm < bPvm then -1 else 0
            else
              0
          else if arvosanaSort[a.aine] < arvosanaSort[b.aine]
            -1
          else
            1
        arvosanataulukko

      createArvosanaTaulukko = (arvosanat) ->
        taulukko = []
        for oppiaine in oppiainekoodit
          aine = oppiaine.koodi.koodiArvo
          aineenArvosanat = arvosanat.filter (a) -> a.aine is aine
          arvosanatByMyonnettyLisatieto = collectToMap(aineenArvosanat, ((a) -> "#{a.myonnetty};#{a.lisatieto}"))
          rivit = []
          suoritusPvm = false
          for key of arvosanatByMyonnettyLisatieto
            list = arvosanatByMyonnettyLisatieto[key]
            first = list[0]
            rivit.push makeAineRivi(aine, list, first.myonnetty, first.lisatieto)
            if first.myonnetty == $scope.suoritus.valmistuminen
              suoritusPvm = true
          if !suoritusPvm
            taulukko.splice 0,0, makeAineRivi(aine, [], $scope.suoritus.valmistuminen, null)
          taulukko = taulukko.concat(rivit)
        sortByAine(taulukko)

      $scope.suorituksenArvosanataulukko = createArvosanaTaulukko(suorituksenArvosanat)
      $scope.korotusAineet = sortByAine oppiainekoodit.map (oppiaine) ->
        {
          aine: oppiaine.koodi.koodiArvo
          text: oppiaine.koodi.metadata[0].nimi
        }
      $scope.info.korotusPvm = $scope.formatDateNoZeroPaddedNumbers($scope.suoritus.valmistuminen)
      $scope.addKorotus = () ->
        console.log $scope.info.korotusAine, $scope.info.korotusPvm
        if  $scope.info.korotusAine && $scope.info.korotusPvm
          $scope.suorituksenArvosanataulukko.push makeAineRivi($scope.info.korotusAine, [], $scope.formatDateWithZeroPaddedNumbers($scope.info.korotusPvm), null)
          sortByAine $scope.suorituksenArvosanataulukko
          updateArvosanaTaulukko $scope.suorituksenArvosanataulukko

      updateArvosanaTaulukko $scope.suorituksenArvosanataulukko
      console.log $scope.suorituksenArvosanataulukko
      $scope.$watch "suorituksenArvosanataulukko", $scope.enableSave, true
    )

    hasValinnaisuus = (aine) ->
      koodistoOppiaineLista.some (o) ->
        o.koodi.koodiArvo is aine and o.alaKoodit.some (alakoodi) ->
          alakoodi.koodiUri is "oppiaineenvalinnaisuus_1"

    hasKielisyys = (aine) ->
      koodistoOppiaineLista.some (o) ->
        o.koodi.koodiArvo is aine and o.alaKoodit.some (alakoodi) ->
          alakoodi.koodiUri is "oppiaineenkielisyys_1"

    $scope.hasChanged = ->
      updateArvosanaTaulukko $scope.suorituksenArvosanataulukko
      arvosanatModified.some (a) -> a.hasChanged()

    $scope.saveData = ->
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

      saveArvosanat = () ->
        arvosanatModified.map (arvosanaModified) ->
          d = $q.defer()
          if arvosanaModified.hasChanged()
            arvosana = arvosanaModified.object
            if arvosana.arvio.arvosana is "Ei arvosanaa"
              if arvosana.id
                removeArvosana arvosana, d
              else
                d.resolve "not saved, don't remove"
            else
              saveArvosana arvosana, d
          else
            d.resolve "not modified"
          d.promise.then () ->
            arvosanaModified.update()
          d.promise

      updateArvosanaTaulukko $scope.suorituksenArvosanataulukko
      $q.all(saveArvosanat()).then (->
      ), ->
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennuseionnistunut"
          message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen."
      []

    $scope.addDataScope($scope)
]
