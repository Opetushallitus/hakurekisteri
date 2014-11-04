function TiedonsiirtoCtrl($scope, $rootScope) {
    $rootScope.addToMurupolku({key: "suoritusrekisteri.tiedonsiirto.muru", text: "Tiedonsiirto"}, true);

    $scope.send = function() {
        $scope.sending = true;
        delete $scope.uploadResult;
    };

    $scope.uploadComplete = function(content) {
        if (isUploadResponse(content)) {
            $scope.uploadResult = typeof content === 'object' ? content : angular.fromJson(content);
            document.getElementById("uploadForm").reset();
        } else {
            $scope.uploadResult = {
                type: "danger",
                message: "Virhe lähettäessä tiedostoa",
                messageKey: "suoritusrekisteri.tiedonsiirto.virhe"
            }
        }
        delete $scope.sending;
    };

    $scope.reset = function() {
        document.getElementById("uploadForm").reset();
        delete $scope.uploadResult;
    };

    function isUploadResponse(content) {
        return (typeof content === 'string' && content.match(/.*"type":.*/g)) || (typeof content === 'object' && content.type && content.message)
    }
}