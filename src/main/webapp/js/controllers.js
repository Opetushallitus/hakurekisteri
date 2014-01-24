'use strict';

var msgCategory = "suoritusrekisteri";
var baseurl = "";

/* Controllers */

/* not in use yet
 function HomeCtrl($scope, $http) {

 }
 */

function OpiskeluoikeudetCtrl($scope, $http, $routeParams, $log, Henkilo, Organisaatio) {
    $scope.loading = false;
    $scope.currentRows = [];
    $scope.sorting = { field: "", direction: "desc" };
    $scope.pageNumbers = [];
    $scope.page = 0;
    $scope.pageSize = 10;
    $scope.totalRows = 0;
    $scope.filter = {star: $routeParams.star ? $routeParams.star : ""};
    $scope.targetOrg = $.ajax({ // get the organisaatio that user has suoritusrekisteri roles to:
        url: baseurl + "/suoritusrekisteri/user/roles/target-organisaatio-for?role=ROLE_APP_SUORITUSREKISTERI_READ,ROLE_APP_SUORITUSREKISTERI_READ_UPDATE,ROLE_APP_SUORITUSREKISTERI_CRUD",
        async: false
    }).responseText;
    $log.info("targetOrg: " + $scope.targetOrg);

    $scope.fetch = function() {
        $scope.currentRows = [];
        $scope.loading = true;
        var url = baseurl + "/suoritusrekisteri/opiskeluoikeudet/search/*?q=" + encodeURIComponent($scope.filter.star)
            + "&page=" + encodeURIComponent($scope.page)
            + "&size=" + encodeURIComponent($scope.pageSize)
            + "&org=" + encodeURIComponent($scope.targetOrg);
        if ($scope.sorting) {
            if ($scope.sorting.field) {
                url = url + "&sort=" + encodeURIComponent($scope.sorting.field);
                if ($scope.sorting.direction && $scope.sorting.direction.match(/asc|desc/)) {
                    url = url + "," + encodeURIComponent($scope.sorting.direction);
                }
            }
        }
        $http.get(url, {cache: true})
            .success(function (data) {
                $scope.currentRows = data.content;
                enrichData();
                $scope.totalRows = data.page.totalElements ? data.page.totalElements : data.content.length;
                resetPageNumbers();
                $scope.loading = false;
            })
            .error(function() {
                $scope.loading = false;
            });
    };

    function enrichData() {
        angular.forEach($scope.currentRows, function(opiskeluoikeus) {
            if (opiskeluoikeus.oppilaitosOid) {
                $http.get("/organisaatio-service/rest/organisaatio/" + opiskeluoikeus.oppilaitosOid + "?" + getCacheEnvKey(), {cache: true}).success(function (data) {
                    if (data && data.oid === opiskeluoikeus.oppilaitosOid)
                        opiskeluoikeus.oppilaitoskoodi = data.oppilaitosKoodi + ' ' + data.nimi.fi;
                });
            }
            if (opiskeluoikeus.henkiloOid) {
                $http.get("/authentication-service/resources/henkilo/" + opiskeluoikeus.henkiloOid + "?" + getCacheEnvKey(), {cache: true}).success(function (henkilo) {
                    if (henkilo && henkilo.oidHenkilo === opiskeluoikeus.henkiloOid && henkilo.sukunimi && henkilo.etunimet) {
                        opiskeluoikeus.henkilo = henkilo.sukunimi + ", " + henkilo.etunimet + (henkilo.hetu ? " (" + henkilo.hetu + ")" : "");
                    }
                });
            }
        });
    }

    $scope.nextPage = function() {
        if (($scope.page + 1) * $scope.pageSize < $scope.totalRows) {
            $scope.page++;
        } else {
            $scope.page = 0;
        }
        $scope.fetch();
    };
    $scope.prevPage = function() {
        if ($scope.page > 0 && ($scope.page - 1) * $scope.pageSize < $scope.totalRows) {
            $scope.page--;
        } else {
            $scope.page = Math.floor($scope.totalRows / $scope.pageSize);
        }
        $scope.fetch();
    };
    $scope.showPageWithNumber = function(pageNum) {
        $scope.page = pageNum > 0 ? (pageNum - 1) : 0;
        $scope.fetch();
    };
    $scope.setPageSize = function(newSize) {
        $scope.pageSize = newSize;
        $scope.page = 0;
        resetPageNumbers();
        $scope.fetch();
    };
    $scope.sort = function(field, direction) {
        $scope.sorting.field = field;
        $scope.sorting.direction = direction.match(/asc|desc/) ? direction : 'asc';
        $scope.page = 0;
        $scope.fetch();
    };
    $scope.isDirectionIconVisible = function(field) {
        var isVisible = $scope.sorting.field === field;
        $log.debug("isVisible: " + isVisible + " field: " + field);
        return isVisible;
    };

    function resetPageNumbers() {
        $scope.pageNumbers = [];
        for (var i = 0; i < Math.ceil($scope.totalRows / $scope.pageSize); i++) {
            $scope.pageNumbers.push(i + 1);
        }
    }

    $scope.fetch();
}

function getKoodi(koodiArray, koodiArvo) {
    for (var i = 0; i < koodiArray.length; i++) {
        var koodi = koodiArray[i];
        if (koodi.koodiArvo == koodiArvo) {
            for (var m = 0; m < koodi.metadata.length; m++) {
                var metadata = koodi.metadata[m];
                if (metadata.kieli == "FI") {
                    return metadata.nimi;
                }
            }
        }
    }
    return koodiArvo;
}

function getCacheEnvKey() {
    return "_cachekey=" + encodeURIComponent(location.hostname);
}

/* not in use yet
 function SuorituksetCtrl($scope, $http, $log) {
 $scope.filter = {henkiloOid: ""};
 $scope.gridOptions = {
 columnDefs: [
 {field:'henkiloOid', displayName:'Henkilo OID'},
 {field:'komotoOid', displayName:'Komoto OID'},
 {field:'oppilaitos', displayName:'Oppilaitosnumero'},
 {field:'arvosana', displayName:'Arvosana'}
 ],
 data: "myData", // angular "promise"
 showGroupPanel: true
 };

 $scope.updateFilter = function() { // loads data to grid
 var url = "../suoritukset";
 if ($scope.filter.henkiloOid) url += "/search/henkiloOid?henkiloOid=" + $scope.filter.henkiloOid + "";
 $log.debug("updateFilter, url: "+url);
 var success = function (data) {
 $log.debug(data.content);
 $scope.myData = data.content;
 // $scope.$apply();
 };
 $http.get(url, {cache:true}, success);
 };
 $scope.updateFilter();
 }
 */