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
    $scope.info = { editable: false }

    getKoodistoAsOptionArray $http, "arvosanat", $scope.arvosanat, "koodiArvo"
    getKoodistoAsOptionArray $http, "kielivalikoima", $scope.kielet, "koodiArvo"
    getKoodistoAsOptionArray $http, "aidinkielijakirjallisuus", $scope.aidinkieli, "koodiArvo"

    arvosanaSort = {}
    arvosanaOrder = ["AI", "A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33", "MA", "BI", "GE",
                     "FY", "KE", "TE", "KT", "HI", "YH", "MU", "KU", "KS", "LI", "KO", "PS", "FI"]
    arvosanaOrder.forEach (k, i) -> arvosanaSort[k] = i

    resolveKoodistoNimi = (metadata) ->
      [metadataInUserLang] = metadata.filter (meta) -> meta.kieli.toLowerCase() == window.userLang
      if (metadataInUserLang)
        metadataInUserLang.nimi
      else
        metadata[0].nimi

    updateOppiaineLista = ->
      d = $q.defer()
      $http.get(window.url("koodisto-service.koodisByKoodisto", "oppiaineetyleissivistava"), {cache: true}).success((koodit) ->
        koodiPromises = koodit.map (koodi) ->
          koodi.metadata.sort((a, b) ->
            (if a.kieli < b.kieli then -1 else 1)
          )
          $http.get(window.url("koodisto-service.relaatio", "sisaltyy-alakoodit", koodi.koodiUri),
            {cache: true}).success((alaKoodit) ->
            koodistoOppiaineLista.push
              koodi: koodi
              alaKoodit: alaKoodit
          )
        $q.all(koodiPromises).then ( ->
          lukio = suoritus.komo is $scope.komo.lukio
          pohjakoulutusFilter = "onperusasteenoppiaine_1"
          if lukio
            pohjakoulutusFilter = "onlukionoppiaine_1"
          koodistoOppiaineLista = koodistoOppiaineLista.filter (o) ->
            o.alaKoodit.some (alakoodi) ->
              pohjakoulutusFilter == alakoodi.koodiUri
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
      nextJarjestys = ->
        jarjestykset = list.filter((a) -> a.valinnainen and typeof a.jarjestys isnt 'undefined').map((a) -> a.jarjestys)
        if (jarjestykset.length > 0)
          Math.max.apply(Math, jarjestykset) + 1
        else
          0
      if list.length >= maxCount || list.some( (a) -> ( a.arvio.arvosana == "Ei arvosanaa" ))
        return
      arvosana = new Arvosanat(
        aine: aineRivi.aine
        suoritus: suoritusId
        arvio:
          arvosana: "Ei arvosanaa"
          asteikko: "4-10"
        valinnainen: valinnainen
      )
      if (valinnainen)
        arvosana.jarjestys = nextJarjestys()
      if aineRivi.myonnetty
        arvosana.myonnetty = aineRivi.myonnetty
      list.push arvosana
      copyAineRiviInfoToArvosana(aineRivi, arvosana)
      arvosanatModified.push changeDetection(arvosana)

    filterArvosanatAndRemove = (list, fn) ->
      list.filter (i) ->
        include = fn(i)
        if !include
          arvosanatModified = arvosanatModified.filter (am) -> am.object != i
        include

    copyAineRiviInfoToArvosana = (aineRivi, arvosana) ->
      if arvosana.arvio.arvosana != "Ei arvosanaa"
        arvosana.aine = aineRivi.aine
        if aineRivi.lisatieto
          arvosana.lisatieto = aineRivi.lisatieto
        else
          delete arvosana.lisatieto
      else
        delete arvosana.lisatieto

    resolveLanguageFromList = (langId, isAI) ->
      list = if isAI
        $scope.aidinkieli
      else
        $scope.kielet
      $scope.resolveValueFromOptionArray(langId, list)

    updateAineRivi = (aineRivi, addNew) ->
      aineRivi.aineNimi = resolveAineNimi(aineRivi.aine)
      aineRivi.hasKielisyys = hasKielisyys(aineRivi.aine)
      if aineRivi.hasKielisyys || aineRivi.aine == 'AI'
        aineRivi.lisatietoText = resolveLanguageFromList(aineRivi.lisatieto, aineRivi.aine == 'AI')
      else
        delete aineRivi.lisatieto
        delete aineRivi.lisatietoText
      if aineRivi.hasValinnaisuus = hasValinnaisuus(aineRivi.aine)
        if addNew && $scope.suoritus.komo != $scope.komo.lukio
          addArvosanaIfNeeded aineRivi.valinnaiset, true, 3, aineRivi
      else
        aineRivi.valinnaiset = filterArvosanatAndRemove aineRivi.valinnaiset, (a) -> a.id
      if addNew
        addArvosanaIfNeeded aineRivi.pakolliset, false, 1, aineRivi
      aineRivi.hasArvosana = aineRivi.pakolliset.concat(aineRivi.valinnaiset).some (a) -> ( a.arvio.arvosana != "Ei arvosanaa" )
      for arvosana in aineRivi.pakolliset.concat(aineRivi.valinnaiset)
        copyAineRiviInfoToArvosana(aineRivi, arvosana)

    updateArvosanaTaulukko = () ->
      rowClass = arrayCarousel("oddRow", "")
      rowClass.next()
      previousAine = null
      maxValinnainenCount = 0
      for aineRivi in $scope.suorituksenArvosanataulukko
        aineRivi.rowClass = ""
        if aineRivi.aine == previousAine
          aineRivi.rowClass = "paddedRow "
        else
          rowClass.next()
        aineRivi.rowClass = aineRivi.rowClass + rowClass.value
        previousAine = aineRivi.aine
        updateAineRivi(aineRivi, $scope.info.editable)
        if maxValinnainenCount < aineRivi.valinnaiset.length
          maxValinnainenCount = aineRivi.valinnaiset.length
      $scope.maxValinnainenCount = maxValinnainenCount

    makeAineRivi = (aine, arvosanat, myonnetty, lisatieto) ->
        {
        aine: aine
        pakolliset: (arvosanat.filter (a) ->  !a.valinnainen)
        valinnaiset: (arvosanat.filter (a) -> a.valinnainen)
        myonnetty: myonnetty
        lisatieto: lisatieto
        }

    sortByAine = (arvosanataulukko) ->
      arvosanataulukko.sort (a, b) ->
        if a.aine is b.aine
          am = a.myonnetty || suoritusValmistuminen()
          bm = b.myonnetty || suoritusValmistuminen()
          if am && bm
            aPvm = $scope.parseFinDate(am)
            bPvm = $scope.parseFinDate(bm)
            if aPvm > bPvm then 1 else if aPvm < bPvm then -1 else 0
          else
            0
        else if arvosanaSort[a.aine] < arvosanaSort[b.aine]
          -1
        else
          1
      arvosanataulukko

    sortByValinnainenAndJarjestys = (list) ->
      list.sort((a, b) ->
        if (a.valinnainen is false and b.valinnainen)
          return -1
        if (a.valinnainen and b.valinnainen is false)
          return 1
        if (a.valinnainen and b.valinnainen)
          if (a.jarjestys is b.jarjestys)
            return 0
          if (a.jarjestys < b.jarjestys)
            -1
          else
            1
        else
          0
      )

    $q.all([updateOppiaineLista(), getSuorituksenArvosanat()]).then (->
      collectToMap = (list, keyFn) ->
        ret = {}
        for i in list
          k = keyFn(i)
          (ret[k] || (ret[k] = [])).push i
        ret

      createArvosanaTaulukko = (arvosanat) ->
        taulukko = []
        for oppiaine in koodistoOppiaineLista
          aine = oppiaine.koodi.koodiArvo
          aineenArvosanat = arvosanat.filter (a) -> a.aine is aine
          arvosanatByMyonnettyLisatieto = collectToMap(aineenArvosanat, ((a) -> "#{a.myonnetty};#{suoritusValmistuminen()};#{a.lisatieto}"))
          rivit = []
          aineHasArvosanaRiviForSuoritusDate = false
          for key of arvosanatByMyonnettyLisatieto
            list = arvosanatByMyonnettyLisatieto[key]
            first = list[0]
            sortByValinnainenAndJarjestys(list)
            dateForNewArvosanat = first.myonnetty || suoritusValmistuminen()
            if dateForNewArvosanat == suoritusValmistuminen()
              dateForNewArvosanat = null
              aineHasArvosanaRiviForSuoritusDate = true
            rivit.push makeAineRivi(aine, list, dateForNewArvosanat, first.lisatieto)
          if !aineHasArvosanaRiviForSuoritusDate
            taulukko.splice 0,0, makeAineRivi(aine, [], null, null)
          taulukko = taulukko.concat(rivit)
        sortByAine(taulukko)

      $scope.suorituksenArvosanataulukko = createArvosanaTaulukko(suorituksenArvosanat)
      $scope.korotusAineet = sortByAine koodistoOppiaineLista.map (oppiaine) ->
        {
          aine: oppiaine.koodi.koodiArvo
          text: resolveKoodistoNimi(oppiaine.koodi.metadata)
        }
      updateArvosanaTaulukko()
      $scope.$watch "suorituksenArvosanataulukko", $scope.enableSave, true
    )

    suoritusValmistuminen = () ->
      formatDateWithZeroPaddedNumbers($scope.suoritus.valmistuminen)

    pad = (n) ->
      if n<10
        '0'+n
      else
        n

    formatDateWithZeroPaddedNumbers = (date) ->
      if date instanceof Date
        ""+pad(date.getDate())+"."+pad(date.getMonth()+1)+"."+date.getFullYear()
      else
        date

    $scope.arvosanatCustomSort = (a) ->
      if(isNaN(a["value"]))
        (a["value"].length)
      else
        -parseFloat(a["value"])

    $scope.editArvosanat = () ->
      $scope.info.editable = true
      updateArvosanaTaulukko()

    $scope.showKorotus = () ->
      $scope.korotusRivi = makeAineRivi("AI", [], $scope.formatDateNoZeroPaddedNumbers(new Date()), null)
      updateKorotus()
      $scope.$watch "korotusRivi", updateKorotus, true

    updateKorotus = () ->
      if $scope.korotusRivi
        updateAineRivi($scope.korotusRivi, true)
      $scope.enableSave()

    addKorotus = () ->
      aineRivi = $scope.korotusRivi
      if aineRivi
        aineRivi.myonnetty = $scope.formatDateNoZeroPaddedNumbers(aineRivi.myonnetty)
        for arvosana in aineRivi.pakolliset.concat(aineRivi.valinnaiset)
          arvosana.myonnetty = aineRivi.myonnetty
        $scope.suorituksenArvosanataulukko.push aineRivi
        $scope.korotusRivi = null

    resolveAineNimi = (aine) ->
      for oppiaine in koodistoOppiaineLista
        if aine == oppiaine.koodi.koodiArvo
          return resolveKoodistoNimi(oppiaine.koodi.metadata)
      return aine

    hasValinnaisuus = (aine) ->
      koodistoOppiaineLista.some (o) ->
        o.koodi.koodiArvo is aine and o.alaKoodit.some (alakoodi) ->
          alakoodi.koodiUri is "oppiaineenvalinnaisuus_1"

    hasKielisyys = (aine) ->
      koodistoOppiaineLista.some (o) ->
        o.koodi.koodiArvo is aine and o.alaKoodit.some (alakoodi) ->
          alakoodi.koodiUri is "oppiaineenkielisyys_1"

    $scope.hasChanged = ->
      if $scope.suorituksenArvosanataulukko
        updateArvosanaTaulukko()
        hasModifications = arvosanatModified.some((a) -> a.hasChanged())
        hasValidKorotus = $scope.korotusRivi and $scope.korotusRivi.hasArvosana and not $scope.isKorotusDateInvalid(parseFinDate($scope.korotusRivi.myonnetty))
        hasNotKorotus = not $scope.korotusRivi
        return hasModifications and (hasNotKorotus or hasValidKorotus)
      false

    $scope.isKorotusDateInvalid = (date, mode) ->
      if date
        valmistuminen = parseFinDate(suoritusValmistuminen())
        d = new Date(date.getTime())
        d.setMinutes(0)
        d.setHours(0)
        d.setSeconds(0)
        d.setMilliseconds(0)
        return d <= valmistuminen
      true

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

      $scope.info.editable = false
      addKorotus()
      updateArvosanaTaulukko()
      $scope.suorituksenArvosanataulukko = sortByAine $scope.suorituksenArvosanataulukko
      p = $q.all(saveArvosanat()).then (->
      ), ->
        $scope.info.editable = true
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.arvosanat.tallennuseionnistunut"
          message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen."
      [p]

    $scope.addDataScope($scope)
]
