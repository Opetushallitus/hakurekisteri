'use strict';

app.controller('TiedonsiirtoCtrl', ['$scope', 'MurupolkuService', function($scope, MurupolkuService) {
    MurupolkuService.addToMurupolku({key: "suoritusrekisteri.tiedonsiirto.muru", text: "Tiedonsiirto"}, true);

    $scope.send = function() {
        $scope.sending = true;
        delete $scope.uploadResult;
    };

    $scope.uploadComplete = function(content) {
        if (isImportBatchResponse(content)) {
            var response = typeof content === 'object' ? content : angular.fromJson(content);
            $scope.uploadResult = {
                type: "success",
                message: "Tiedosto lähetetty.",
                messageKey: "suoritusrekisteri.tiedonsiirto.tiedostolahetetty",
                id: response.id
            }
        }
        else if (isIncidentResponse(content)) {
            var response = typeof content === 'object' ? content : angular.fromJson(content);
            $scope.uploadResult = {
                type: "danger",
                message: "Virhe lähettäessä tiedostoa.",
                messageKey: "suoritusrekisteri.tiedonsiirto.virhe",
                description: response.message
            }
        }
        delete $scope.sending;
    };

    $scope.reset = function() {
        document.getElementById("uploadForm").reset();
        delete $scope.uploadResult;
    };

    function isImportBatchResponse(content) {
        return (typeof content === 'string' && content.match(/.*"batchType".*/g)) || (typeof content === 'object' && content.batchType)
    }

    function isIncidentResponse(content) {
        return (typeof content === 'string' && content.match(/.*"incidentId".*/g)) || (typeof content === 'object' && content.incidentId)
    }
}]);