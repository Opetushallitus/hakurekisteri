app.controller "TiedonsiirtotilaCtrl", [
  "$scope"
  "$http"
  "$log"
  "$q"
  "$cookies"
  "MurupolkuService"
  "LokalisointiService"
  "MessageService"
  ($scope, $http, $log, $q, $cookies, MurupolkuService, LokalisointiService, MessageService) ->

    $scope.loading = false
    $scope.currentRows = []
    $scope.allRows = []

    MurupolkuService.addToMurupolku
      key: "suoritusrekisteri.tiedonsiirtotila.muru"
      text: "Tiedonsiirtojen tila"
    , true

    pageSizeFromCookie = () ->
      cookieValue = $cookies.tiedonsiirtotilaPageSize
      if typeof cookieValue is 'string'
        try
          parseInt(cookieValue)
        catch err
          $log.error("cookie value cannot be parsed to integer: " + cookieValue)
          10
      else
        10

    startLoading = -> $scope.loading = true
    stopLoading = -> $scope.loading = false

    enrichBatch = (b, d) ->
      $http.get(henkiloServiceUrl + "/resources/henkilo/" + encodeURIComponent(b.source), { cache: true }).success((henkilo) ->
        b.lahettaja = henkilo.etunimet + ' ' + henkilo.sukunimi
        d.resolve()
      ).error(->
        d.reject('cannot resolve name for ' + b.source)
      )

    enrichData = ->
      enrichments = []
      for b in $scope.currentRows
        do (b) ->
          d = $q.defer()
          enrichments.push d
          enrichBatch(b, d)
      $q.all(enrichments.map((d) -> d.promise)).then((->
          stopLoading()
        ), (errors) ->
        $log.error("errors during enrichment: " + errors)
        stopLoading()
      )
      return

    showCurrentRows = (allRows) ->
      startLoading()
      $scope.allRows = allRows
      $scope.currentRows = allRows.slice(($scope.currentPage - 1) * $scope.pageSize, ($scope.currentPage) * $scope.pageSize)
      enrichData()
      return

    # paging
    $scope.currentPage = 1
    $scope.pageSizes = ["10", "20", "50"]
    $scope.pageSize = pageSizeFromCookie()
    $scope.pageChanged = (p) ->
      $log.debug("page changed to " + p)
      $scope.currentPage = p
      showCurrentRows $scope.allRows
    $scope.setPageSize = (s) ->
      startLoading()
      $scope.pageSize = s
      $cookies.tiedonsiirtotilaPageSize = "" + $scope.pageSize
      $scope.currentPage = 1
      showCurrentRows $scope.allRows
      return

    getBatches = ->
      startLoading()
      $scope.chart.destroy()  if $scope.chart and typeof $scope.chart.destroy is 'function'
      $http.get("rest/v1/siirto/perustiedot/withoutdata", { cache: false }).success (batches) ->
        if batches
          batches.sort (a, b) ->
            if a.status and b.status
              aSent = a.status.sentTime
              bSent = b.status.sentTime
              return 1  if aSent < bSent
              return -1  if aSent > bSent
            return 0

          showCurrentRows batches

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
          return

        stopLoading()
        return

    getBatches()

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

    $scope.reprocess = (id) ->
      startLoading()
      $http.post('rest/v1/siirto/perustiedot/reprocess/' + encodeURIComponent(id)).success(-> getBatches()).error(->
        MessageService.addMessage
          type: "danger"
          message: "Tiedonsiirron tilan muuttaminen ei onnistunut. Yritä uudelleen hetken kuluttua."
          messageKey: "suoritusrekisteri.tiedonsiirtotila.tilanmuuttamineneionnistunut"
        getBatches()
      )

]