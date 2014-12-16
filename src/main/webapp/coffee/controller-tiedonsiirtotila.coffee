app.controller "TiedonsiirtotilaCtrl", [
  "$scope"
  "$http"
  "MurupolkuService"
  ($scope, $http, MurupolkuService) ->
    MurupolkuService.addToMurupolku
      key: "suoritusrekisteri.tiedonsiirtotila.muru"
      text: "Tiedonsiirtojen tila"
    , true

    $scope.batches = []

    enrichBatch = (b) ->
      $http.get(henkiloServiceUrl + "/resources/henkilo/" + encodeURIComponent(b.source), { cache: true }).success (henkilo) ->
        b.lahettaja = henkilo.etunimet + ' ' + henkilo.sukunimi

    $http.get("rest/v1/siirto/perustiedot/withoutdata", { cache: false }).success (batches) ->
      if batches
        $scope.batches = batches
        enrichBatch(b) for b in $scope.batches
        $scope.batches.sort (a, b) ->
          if a.status and b.status
            aSent = a.status.sentTime
            bSent = b.status.sentTime
            return -1  if aSent < bSent
            return 1  if aSent > bSend
          return 0

        classes =
          READY: '#D9EDF7'
          DONE: '#DFF0D8'
          FAILURE: '#F2DEDE'

        tempData = batches.map((b) -> b.state).reduce((prev, item) ->
          if item of prev
            prev[item]++
          else
            prev[item] = 1
          prev
        , {})
        data = Object.keys(tempData).map (key) ->
          return {
            value: tempData[key]
            label: key
            color: classes[key]
            hilight: classes[key]
          }
        ctx = document.getElementById("tilaChart").getContext("2d")
        new Chart(ctx).Pie(data)

    $scope.statusClass = (b) ->
      return "info"  if b.state is "READY"
      return "danger"  if b.state is "FAILURE"
      return "success"  if b.state is "DONE"
      return ""

]