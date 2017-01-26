app.controller "TiedostonMuodostusCtrl", [
  "$scope"
  "$http"
  "$log"
  "socket"
  ($scope, $http, $log, socket) ->

    socket.subscribe((response) ->
      if(response.connectionEvent)
        response.socket(JSON.stringify({'newClient': true}))
      else if(response.messageEvent)
        console.log(response.message)
    )

]