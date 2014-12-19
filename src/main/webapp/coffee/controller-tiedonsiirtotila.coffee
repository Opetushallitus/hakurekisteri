app.controller "TiedonsiirtotilaCtrl", [
  "$scope"
  "$http"
  "$log"
  "MurupolkuService"
  "LokalisointiService"
  ($scope, $http, $log, MurupolkuService, LokalisointiService) ->
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
            return 1  if aSent < bSent
            return -1  if aSent > bSent
          return 0

        initChart = () ->
          classes =
            READY: '#D9EDF7'
            DONE: '#DFF0D8'
            FAILED: '#F2DEDE'
            PROCESSING: '#FCF8E3'
          tempData = batches.map((b) -> b.state).reduce((prev, item) ->
            if item of prev
              prev[item]++
            else
              prev[item] = 1
            prev
          , {})
          chartData = Object.keys(tempData).map (key) ->
            return {
              value: tempData[key]
              label: getOphMsg("suoritusrekisteri.tiedonsiirtotila.tila." + key)
              color: classes[key]
              hilight: classes[key]
            }
          ctx = document.getElementById("tilaChart").getContext("2d")
          $scope.chart = new Chart(ctx).Pie(chartData,
            animationEasing: 'linear'
            animationSteps: 50
            animateScale: true
            legendTemplate: "<ul class=\"<%=name.toLowerCase()%>-legend list-unstyled\"><% for (var i=0; i<segments.length; i++){%><li class=\"text-nowrap\"><div style=\"background-color:<%=segments[i].fillColor%>\"></div> <%=segments[i].label%></li><%}%></ul>"
          )
          legend = $scope.chart.generateLegend()
          $log.debug("legend: " + legend)
          $scope.legend = legend

        LokalisointiService.loadMessages(initChart)

    $scope.statusClass = (b) ->
      return "info"  if b.state is "READY"
      return "danger"  if b.state is "FAILED"
      return "success"  if b.state is "DONE"
      return "warning"  if b.state is "PROCESSING"
      return ""

    $scope.hasMessages = (b) ->
      b and b.status and b.status.messages and Object.keys(b.status.messages).length > 0

    $scope.$on '$destroy', () ->
      $scope.chart.destroy()  if $scope.chart and typeof $scope.chart.destroy is 'function'
      return
]