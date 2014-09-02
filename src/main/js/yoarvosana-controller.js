'use strict';

function YoarvosanaCtrl($scope, $rootScope, $http, $q, $log, Arvosanat, suoritusId) {
    Arvosanat.query({ suoritus: suoritusId }, function(arvosanat) {

    }, function() {
        $rootScope.modalInstance.close({
            type: "danger",
            messageKey: "suoritusrekisteri.muokkaa.arvosanat.arvosanapalveluongelma",
            message: "Arvosanapalveluun ei juuri nyt saada yhteyttä. Yritä myöhemmin uudelleen."
        })
    });

    $scope.save = function() {
        function removeArvosana(arvosana, d) {
            arvosana.$remove(function () {
                d.resolve("remove ok")
            }, function (err) {
                $log.error("error removing, retrying to remove: " + err);
                arvosana.$remove(function () {
                    d.resolve("retry remove ok");
                }, function (retryErr) {
                    $log.error("retry remove failed: " + retryErr);
                    d.reject("retry save failed");
                });
            })
        }

        function saveArvosana(arvosana, d) {
            arvosana.$save(function (saved) {
                d.resolve("save ok: " + saved.id)
            }, function (err) {
                $log.error("error saving, retrying to save: " + err);
                arvosana.$save(function (retriedSave) {
                    d.resolve("retry save ok: " + retriedSave.id);
                }, function (retryErr) {
                    $log.error("retry save failed: " + retryErr);
                    d.reject("retry save failed");
                });
            })
        }
    };

    $scope.cancel = function() {
        $rootScope.modalInstance.close()
    };

    $scope.aineyhdistelmaroolit = [
        {value: "11", text: "äidinkieli"},
        {value: "12", text: "äidinkieli, saame"},
        {value: "13", text: "kypsyyskoe"},
        {value: "14", text: "äidinkielen tilalla suoritettava suomen tai ruotsin koe"},
        {value: "21", text: "pakollinen toisen kotimaisen kielen koe tai erivapaudella suoritettava muu kieli tai reaali tai matematiikka"},
        {value: "22", text: "pakollinen toisen kotimaisen sijaan suoritettu äidinkieli tässä kielessä"},
        {value: "31", text: "pakollisena kokeena suoritettu vieras kieli"},
        {value: "32", text: "pakollisena kokeen suoritettu vieras kieli"},
        {value: "41", text: "pakollisena kokeena suoritettu reaaliaine"},
        {value: "42", text: "pakollisena kokeena suoritettu matematiikka"},
        {value: "60", text: "ylimääräinen äidinkieli"},
        {value: "61", text: "ylimääräisenä kokeena suoritettu vieras kieli"},
        {value: "62", text: "Ylimääräisenä suoritettu toisen kotimaisen kielen koe"},
        {value: "71", text: "ylimääräisenä kokeena suoritettu reaaliaine"},
        {value: "81", text: "ylimääräisenä kokeena suoritettu matematiikka"}
    ];
}

