app.controller "MuokkaaArvosanatYo", [
  "$scope"
  "$q"
  "$log"
  "Arvosanat"
  "MessageService"
  ($scope, $q, $log, Arvosanat, MessageService) ->
    suoritusId = $scope.suoritus.id
    isEditable = (myonnetty) ->
      not myonnetty or myonnetty.match(/^[0-9.]*\.19[0-8][0-9]$/)
    tutkintokerrat = ->
      kerrat = []
      for i in [1989..1900]
        do (i) ->
          kerrat.push
            value: "21.12." + i
            text: "21.12." + i + " (" + i + "S)"
          kerrat.push
            value: "01.06." + i
            text: "01.06." + i + " (" + i + "K)"
          return
      kerrat
    getAineet = ->
      Object.keys(aineet).map((k) ->
        value: k
        text: aineKielistykset[k]
      ).sort (a, b) ->
        (if a.text is b.text then 0 else (if a.text < b.text then -1 else 1))

    arvosanatModified = []
    $scope.koetaulukko = []
    $scope.loading = true
    Arvosanat.query { suoritus: suoritusId }, ((arvosanat) ->
      $scope.koetaulukko = arvosanat.filter((a) ->
        a.arvio.asteikko is "YO"
      ).map((a) ->
        arvosanatModified.push changeDetection(a)
        {
          arvosana: a
          editable: isEditable(a.myonnetty)
        }
      )
      $scope.loading = false
      return
    ), ->
      $scope.loading = false
      MessageService.addMessage
        type: "danger"
        messageKey: "suoritusrekisteri.muokkaa.yoarvosanat.arvosanapalveluongelma"
        message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
      return

    $scope.addKoe = ->
      arvosana = new Arvosanat(
        suoritus: suoritusId
        valinnainen: false
        arvio:
          asteikko: "YO"
      )
      arvosanatModified.push changeDetection(arvosana)
      $scope.koetaulukko.push {
        arvosana: arvosana
        editable: true
      }

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
          $log.error "error saving " + err
          d.reject "save failed"
      saveArvosanat = (arvosanat) ->
        arvosanat.map (arvosanaM) ->
          arvosana = arvosanaM.object
          d = $q.defer()
          if arvosana["delete"] && arvosana.id
            removeArvosana arvosana, d
          else
            saveArvosana arvosana, d
          d.promise.then () ->
            arvosanaM.update()
          d.promise
      arvosanat = arvosanatModified.filter((arvosanaModified) -> arvosanaModified.hasChanged())
      p = $q.all(saveArvosanat(arvosanat)).then (->
      ), ->
        MessageService.addMessage
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.yoarvosanat.tallennuseionnistunut"
          message: "Arvosanojen tallentamisessa tapahtui virhe. Tarkista arvosanat ja tallenna tarvittaessa uudelleen."
      [p]

    $scope.$watch "koetaulukko", $scope.enableSave, true
    $scope.hasChanged = ->
      arvosanatModified.some (a) ->
        a.hasChanged() && notEmpty(a.object.aine) && notEmpty(a.object.arvio.arvosana) && notEmpty(a.object.myonnetty)

    $scope.addDataScope($scope)

    $scope.tutkintokerrat = tutkintokerrat()

    aineet =
      SA: [
        {
          value: "SAKSALKOUL"
          text: "Saksalaisen koulun oppimäärä"
        }
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
      ]
      IT: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
      ]
      PS: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      HI: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      LA: [
        {
          value: "D"
          text: "Lyhyt oppimäärä (LATINA)"
        }
        {
          value: "C"
          text: "Laajempi oppimäärä"
        }
      ]
      UN: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
      ]
      FY: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      MA: [
        {
          value: "PITKA"
          text: "Pitkä oppimäärä (MA)"
        }
        {
          value: "LYHYT"
          text: "Lyhyt oppimäärä (MA)"
        }
      ]
      IS: [
        {
          value: "AI"
          text: "Äidinkieli"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
      ]
      EN: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
        {
          value: "KYPSYYS"
          text: "Kypsyyskoe (VAIN EN)"
        }
      ]
      KE: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      VE: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
      ]
      YH: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      BI: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      RU: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "AI"
          text: "Äidinkieli"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "VI2"
          text: "toisena kielenä (FI/RU)"
        }
      ]
      FF: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      ES: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
      ]
      ZA: [
        {
          value: "AI"
          text: "Äidinkieli"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
      ]
      GE: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      UO: [
        {
          value: "REAALI"
          text: "Reaalikoe (VANHA)"
        }
        {
          value: "AINEREAALI"
          text: "Ainemuotoinen reaali"
        }
      ]
      FI: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "AI"
          text: "Äidinkieli"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
        {
          value: "VI2"
          text: "toisena kielenä (FI/RU)"
        }
      ]
      ET: [
        {
          value: "REAALI"
          text: "Reaalikoe (VANHA)"
        }
        {
          value: "AINEREAALI"
          text: "Ainemuotoinen reaali"
        }
      ]
      QS: [
        value: "C"
        text: "Lyhyt oppimäärä (KIELI)"
      ]
      KR: [
        value: "C"
        text: "Lyhyt oppimäärä (KIELI)"
      ]
      PG: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
      ]
      TE: [
        value: "AINEREAALI"
        text: "Ainemuotoinen reaali"
      ]
      RA: [
        {
          value: "A"
          text: "Pitkä oppimäärä (KIELI)"
        }
        {
          value: "B"
          text: "Keskipitkä oppimäärä (KIELI)"
        }
        {
          value: "C"
          text: "Lyhyt oppimäärä (KIELI)"
        }
      ]
      UE: [
        {
          value: "REAALI"
          text: "Reaalikoe (VANHA)"
        }
        {
          value: "AINEREAALI"
          text: "Ainemuotoinen reaali"
        }
      ]

    aineKielistykset =
      RU: "Ruotsi"
      FI: "Suomi"
      ZA: "Pohjoissaame"
      EN: "Englanti"
      RA: "Ranska"
      PG: "Portugali"
      UN: "Unkari"
      IS: "Inarinsaame"
      KR: "Kreikka"
      LA: "Latina"
      MA: "Matematiikka"
      ES: "Espanja"
      SA: "Saksa"
      IT: "Italia"
      VE: "Venäjä"
      QS: "Koltansaame"
      RR: "Reaali"
      UE: "Ev.lut. uskonto"
      UO: "Ortodoksiuskonto"
      ET: "Elämänkatsomustieto"
      FF: "Filosofia"
      PS: "Psykologia"
      HI: "Historia"
      YH: "Yhteiskuntaoppi"
      FY: "Fysiikka"
      KE: "Kemia"
      BI: "Biologia"
      GE: "Maantiede"
      TE: "Terveystieto"

    $scope.aineet = getAineet()
    $scope.getTasot = (yoAine) ->
      (if aineet[yoAine] then aineet[yoAine] else [])

    $scope.arvosanat = [
      {
        value: "L"
        text: "(L) Laudatur"
      }
      {
        value: "M"
        text: "(M) Magna cum laude approbatur"
      }
      {
        value: "C"
        text: "(C) Cum laude approbatur"
      }
      {
        value: "B"
        text: "(B) Lubenter approbatur"
      }
      {
        value: "A"
        text: "(A) Approbatur"
      }
      {
        value: "I"
        text: "(I) Improbatur"
      }
    ]
]