app.controller "MuokkaaArvosanatYo", [
  "$scope"
  "$q"
  "$log"
  "Arvosanat"
  "MessageService"
  "LokalisointiService"
  ($scope, $q, $log, Arvosanat, MessageService, LokalisointiService) ->
    suoritusId = $scope.suoritus.id
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
        text: LokalisointiService.getTranslation('yoAine.' + k)
      ).sort (a, b) ->
        (if a.text is b.text then 0 else (if a.text < b.text then -1 else 1))

    arvosanatModified = []
    $scope.koetaulukko = []
    $scope.populateKoetaulukkoAndModified = (arvosanatModified) ->
      ((arvosanat) ->
        isEditable = (myonnetty) ->
          not myonnetty or myonnetty.match(/^[0-9.]*\.19[0-8][0-9]$/)
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
      )
    $scope.loading = true
    Arvosanat.query { suoritus: suoritusId }, $scope.populateKoetaulukkoAndModified(arvosanatModified), ->
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
        Arvosanat.query { suoritus: suoritusId }, $scope.populateKoetaulukkoAndModified(arvosanatModified), ->
          MessageService.addMessage
            type: "danger"
            messageKey: "suoritusrekisteri.muokkaa.yoarvosanat.arvosanapalveluongelma"
            message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
          return
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
        }
        {
          value: "A"
        }
        {
          value: "B"
        }
        {
          value: "C"
        }
      ]
      IT: [
        {
          value: "A"
        }
        {
          value: "B"
        }
        {
          value: "C"
        }
      ]
      PS: [
        value: "AINEREAALI"
      ]
      HI: [
        value: "AINEREAALI"
      ]
      LA: [
        {
          value: "D"
        }
        {
          value: "C"
        }
      ]
      UN: [
        {
          value: "A"
        }
        {
          value: "B"
        }
      ]
      FY: [
        value: "AINEREAALI"
      ]
      MA: [
        {
          value: "PITKA"
        }
        {
          value: "LYHYT"
        }
      ]
      IS: [
        {
          value: "AI"
        }
        {
          value: "C"
        }
      ]
      EN: [
        {
          value: "A"
        }
        {
          value: "B"
        }
        {
          value: "C"
        }
        {
          value: "KYPSYYS"
        }
      ]
      KE: [
        value: "AINEREAALI"
      ]
      VE: [
        {
          value: "A"
        }
        {
          value: "B"
        }
        {
          value: "C"
        }
      ]
      YH: [
        value: "AINEREAALI"
      ]
      BI: [
        value: "AINEREAALI"
      ]
      RU: [
        {
          value: "A"
        }
        {
          value: "AI"
        }
        {
          value: "B"
        }
        {
          value: "VI2"
        }
      ]
      FF: [
        value: "AINEREAALI"
      ]
      ES: [
        {
          value: "A"
        }
        {
          value: "B"
        }
        {
          value: "C"
        }
      ]
      ZA: [
        {
          value: "AI"
        }
        {
          value: "C"
        }
      ]
      GE: [
        value: "AINEREAALI"
      ]
      UO: [
        {
          value: "REAALI"
        }
        {
          value: "AINEREAALI"
        }
      ]
      FI: [
        {
          value: "A"
        }
        {
          value: "AI"
        }
        {
          value: "B"
        }
        {
          value: "C"
        }
        {
          value: "VI2"
        }
      ]
      ET: [
        {
          value: "REAALI"
        }
        {
          value: "AINEREAALI"
        }
      ]
      QS: [
        value: "C"
      ]
      KR: [
        value: "C"
      ]
      PG: [
        {
          value: "A"
        }
        {
          value: "B"
        }
        {
          value: "C"
        }
      ]
      TE: [
        value: "AINEREAALI"
      ]
      RA: [
        {
          value: "A"
        }
        {
          value: "B"
        }
        {
          value: "C"
        }
      ]
      UE: [
        {
          value: "REAALI"
        }
        {
          value: "AINEREAALI"
        }
      ]

    $scope.aineet = getAineet()

    $scope.getTasot = (yoAine) ->
      (if aineet[yoAine] then aineet[yoAine] else [])

    $scope.translateTaso = (arvosana, taso) ->
      LokalisointiService.getTranslation('yoTutkinto.aineTaso.' + arvosana.lisatieto + '.' + taso) or
      LokalisointiService.getTranslation('yoTutkinto.aineTaso.' + taso) or
      taso

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