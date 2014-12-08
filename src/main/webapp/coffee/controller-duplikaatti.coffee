app.controller "DuplikaattiCtrl", [
  "$scope"
  "$log"
  "arvosanat"
  ($scope, $log, arvosanat) ->
    $scope.arvosanat = arvosanat.sort((a, b) ->
      if a.aine is b.aine
        if a.lisatieto is b.lisatieto
          (if a.valinnainen is true and b.valinnainen is false then 1 else ((if a.valinnainen is false and b.valinnainen is true then -1 else 0)))
        else
          (if a.lisatieto < b.lisatieto then -1 else 1)
      else
        (if a.aine < b.aine then -1 else 1)
    )

    $scope.remove = (arvosana) ->
      arvosana.$remove (->
        index = $scope.arvosanat.indexOf(arvosana)
        $scope.arvosanat.splice index, 1  if index isnt -1
        return
      ), ->
        $scope.modalInstance.close
          type: "danger"
          messageKey: "suoritusrekisteri.muokkaa.duplikaatti.virhepoistettaessaarvosanaa"
          message: "Virhe poistettaessa arvosanaa. Yritä uudelleen."
      return

    $scope.close = ->
      $scope.modalInstance.close()
      return
]