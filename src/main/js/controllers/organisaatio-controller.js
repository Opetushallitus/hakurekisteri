'use strict';

app.controller('OrganisaatioCtrl', ['$scope', '$http', '$log', function($scope, $http, $log) {
    $scope.organisaatiotyypit = [];
    $scope.oppilaitostyypit = [];
    $scope.loading = false;
    getKoodistoAsOptionArray($http, 'organisaatiotyyppi', 'fi', $scope.organisaatiotyypit, 'nimi');
    getKoodistoAsOptionArray($http, 'oppilaitostyyppi', 'fi', $scope.oppilaitostyypit);
    $scope.myOrganisaatioOids = ["1.2.246.562.10.00000000001"];
    $http.get(getBaseUrl() + '/cas/myroles', {cache: true})
        .success(function(roles) {
            var oidPattern = /[0-9.]+/;
            var oids = roles.filter(function(role) {
                return role && oidPattern.test(role);
            }).map(function(role) {
                return oidPattern.exec(role);
            });
            var uniq = {};
            for (var i = 0; i < oids.length; i++) {
                uniq[oids[i]] = oids[i];
            }
            $scope.myOrganisaatioOids = Object.keys(uniq);
            $log.debug("oids: " + $scope.myOrganisaatioOids);
        });

    $scope.hae = function() {
        $scope.loading = true;
        $http.get(organisaatioServiceUrl + '/rest/organisaatio/hae',
            {
                params: {
                    searchstr: $scope.hakuehto,
                    organisaatiotyyppi: $scope.organisaatiotyyppi,
                    oppilaitostyyppi: $scope.oppilaitostyyppi,
                    vainLakkautetut: $scope.lakkautetut,
                    oidrestrictionlist: $scope.myOrganisaatioOids
                },
                cache: true
            })
            .then(function(result) {
                if (result.data && result.data.numHits > 0)
                    $scope.organisaatiot = result.data.organisaatiot;
                else
                    $scope.organisaatiot = [];
                $scope.loading = false;
            }, function() {
                $scope.organisaatiot =  [];
                $scope.loading = false;
            });
    };

    $scope.tyhjenna = function() {
        delete $scope.hakuehto;
        delete $scope.organisaatiotyyppi;
        delete $scope.oppilaitostyyppi;
        delete $scope.lakkautetut;
        delete $scope.organisaatiot;
    };

    $scope.valitse = function(organisaatio) {
        $scope.modalInstance.close(organisaatio);
    };

    $scope.showLakkautetut = function(organisaatio) {
        var hasLakkautettujaLapsia = function(o) {
            if (o.lakkautusPvm && o.lakkautusPvm < new Date().getTime()) {
                return true;
            }
            if (o.aliOrganisaatioMaara && o.aliOrganisaatioMaara > 0) {
                for (var i = 0; i < o.children.length; i++) {
                    return hasLakkautettujaLapsia(o.children[i]);
                }
            }
            return false;
        };

        if (!$scope.lakkautetut) {
            return true;
        }
        if (organisaatio) {
            return hasLakkautettujaLapsia(organisaatio);
        }
        return false;
    };
}]);
