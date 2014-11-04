function TiedonsiirtoCtrl($scope, $rootScope) {
    $rootScope.addToMurupolku({key: "suoritusrekisteri.tiedonsiirto.muru", text: "Tiedonsiirto"}, true);

    $scope.messages = [];

    $scope.send = function() {
        $scope.sending = true;
        delete $scope.uploadResult;
        $scope.messages = [];
    };

    $scope.uploadComplete = function(content) {
        if (isUploadResponse(content)) {
            $scope.uploadResult = typeof content === 'object' ? content : angular.fromJson(content);
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
        document.getElementById("uploadForm").reset();
        delete $scope.uploadResult;
        $scope.messages = [];
    };

    $scope.removeMessage = function(message) {
        var index = $scope.messages.indexOf(message);
        if (index !== -1) $scope.messages.splice(index, 1);
    };

    function isUploadResponse(content) {
        return (typeof content === 'string' && content.match(/.*"type":.*/g)) || (typeof content === 'object' && content.type && content.message)
    }
}