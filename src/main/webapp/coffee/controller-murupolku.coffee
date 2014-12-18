app.controller "MurupolkuCtrl", [
  "$scope"
  "MurupolkuService"
  "$location"
  ($scope, MurupolkuService, $location) ->
    $scope.murupolku = MurupolkuService.murupolku
    $scope.isHidden = MurupolkuService.isHidden
    $scope.goHome = ->
      if $location.path().match(/tiedonsiirto/)
        $location.path "/tiedonsiirto"
      else
        $location.path "/"
      return
]