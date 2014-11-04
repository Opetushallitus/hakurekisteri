function TiedonsiirtoCtrl($scope) {
    $scope.messages = [];

    $scope.send = function() {
        $scope.sending = true;
    };

    $scope.uploadComplete = function(content) {
        if (isUploadResponse(content)) {
            $scope.uploadResult = typeof content === 'string' ? angular.fromJson(content) : content;
            document.getElementById("uploadForm").reset();
        } else {
            $scope.messages.push({
                type: "alert",
                message: "Virhe lähettäessä tiedostoa",
                messageKey: "suoritusrekisteri.tiedonsiirto.virhe"
            })
        }
        delete $scope.sending;
    };

    $scope.reset = function() {
        delete $scope.tiedosto;
        delete $scope.sendResult;
    };

    $scope.removeMessage = function(message) {
        var index = $scope.messages.indexOf(message);
        if (index !== -1) $scope.messages.splice(index, 1);
    };

    function isUploadResponse(content) {
        return (typeof content === 'string' && content.match(/.*"type":.*/g)) || (typeof content === 'object' && content.type)
    }
}