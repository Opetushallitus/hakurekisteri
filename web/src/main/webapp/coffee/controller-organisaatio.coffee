app.controller "OrganisaatioCtrl", [
  "$scope"
  "$http"
  "$log"
  ($scope, $http, $log) ->
    $scope.organisaatiotyypit = []
    $scope.oppilaitostyypit = []
    $scope.loading = false
    getKoodistoAsOptionArray $http, "organisaatiotyyppi", "fi", $scope.organisaatiotyypit, "nimi"
    getKoodistoAsOptionArray $http, "oppilaitostyyppi", "fi", $scope.oppilaitostyypit
    $scope.myOrganisaatioOids = ["1.2.246.562.10.00000000001"]
    $http.get(getBaseUrl() + "/cas/myroles",
      cache: true
    ).success (roles) ->
      oidPattern = /[0-9.]+/
      oids = roles.filter((role) ->
        role and oidPattern.test(role)
      ).map((role) ->
        oidPattern.exec role
      )
      uniq = {}
      i = 0

      while i < oids.length
        uniq[oids[i]] = oids[i]
        i++
      $scope.myOrganisaatioOids = Object.keys(uniq)
      $log.debug "oids: " + $scope.myOrganisaatioOids
      return

    $scope.hae = ->
      $scope.loading = true
      $http.get(organisaatioServiceUrl + "/rest/organisaatio/hae",
        params:
          searchstr: $scope.hakuehto
          organisaatiotyyppi: $scope.organisaatiotyyppi
          oppilaitostyyppi: $scope.oppilaitostyyppi
          vainLakkautetut: $scope.lakkautetut
          oidrestrictionlist: $scope.myOrganisaatioOids

        cache: true
      ).then ((result) ->
        if result.data and result.data.numHits > 0
          $scope.organisaatiot = result.data.organisaatiot
        else
          $scope.organisaatiot = []
        $scope.loading = false
        return
      ), ->
        $scope.organisaatiot = []
        $scope.loading = false
        return

      return

    $scope.tyhjenna = ->
      delete $scope.hakuehto

      delete $scope.organisaatiotyyppi

      delete $scope.oppilaitostyyppi

      delete $scope.lakkautetut

      delete $scope.organisaatiot

      return

    $scope.valitse = (organisaatio) ->
      $scope.modalInstance.close organisaatio
      return

    $scope.showLakkautetut = (organisaatio) ->
      hasLakkautettujaLapsia = (o) ->
        return true  if o.lakkautusPvm and o.lakkautusPvm < new Date().getTime()
        if o.aliOrganisaatioMaara and o.aliOrganisaatioMaara > 0
          i = 0

          while i < o.children.length
            return hasLakkautettujaLapsia(o.children[i])
            i++
        false

      return true  unless $scope.lakkautetut
      return hasLakkautettujaLapsia(organisaatio)  if organisaatio
      false
]