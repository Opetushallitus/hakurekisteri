app.controller "MuokkaaSuorituksetCtrl", [
  "$scope"
  "$routeParams"
  "$location"
  ($scope, $routeParams, $location) ->

    $scope.back = ->
      if history and history.back
        history.back()
      else
        $location.path "/opiskelijat"
      return

]