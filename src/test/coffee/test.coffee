describe 'Controllers', ->
  beforeEach(module('myApp'))

  describe 'MuokkaaCtrl', ->
    scope = null

    afterEach inject (($httpBackend) ->
      $httpBackend.verifyNoOutstandingExpectation()
      $httpBackend.verifyNoOutstandingRequest()
    )

    beforeEach(inject ($httpBackend, $injector, $controller, $rootScope, $location, $log, $q, $modal, Opiskelijat, Suoritukset, Opiskeluoikeudet, LokalisointiService, MurupolkuService, MessageService) ->
      scope = $rootScope.$new()

      $httpBackend.whenGET('https://itest-virkailija.oph.ware.fi/authentication-service/buildversion.txt?auth').respond(200, "")
      $httpBackend.whenGET('https://itest-virkailija.oph.ware.fi/cas/myroles').respond(200, '["APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001"]')
      $httpBackend.whenGET('/cas/myroles').respond(200, '["APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001"]')
      $httpBackend.whenGET('https://itest-virkailija.oph.ware.fi/lokalisointi/cxf/rest/v1/localisation?category=suoritusrekisteri').respond(200, [])

      $httpBackend.whenGET('https://itest-virkailija.oph.ware.fi/koodisto-service/rest/json/kieli/koodi').respond(200, [])
      $httpBackend.whenGET('https://itest-virkailija.oph.ware.fi/koodisto-service/rest/json/luokkataso/koodi').respond(200, [])
      $httpBackend.whenGET('https://itest-virkailija.oph.ware.fi/koodisto-service/rest/json/yksilollistaminen/koodi').respond(200, [])
      $httpBackend.whenGET('https://itest-virkailija.oph.ware.fi/koodisto-service/rest/json/suorituksentila/koodi').respond(200, [])

      $httpBackend.whenGET('https://itest-virkailija.oph.ware.fi/authentication-service/resources/henkilo/1.2.3').respond(200, {oidHenkilo: "1.2.3"})

      $httpBackend.whenGET('rest/v1/suoritukset?henkilo=1.2.3').respond(200, [])
      $httpBackend.whenGET('rest/v1/opiskelijat?henkilo=1.2.3').respond(200, [])
      $httpBackend.whenGET('rest/v1/opiskeluoikeudet?henkilo=1.2.3').respond(200, [])

      $controller 'MuokkaaCtrl',
        $scope: scope
        $routeParams: {henkiloOid: "1.2.3"}
        $location: $location
        $log: $log
        $q: $q
        $modal: $modal
        Opiskelijat: Opiskelijat
        Suoritukset: Suoritukset
        Opiskeluoikeudet: Opiskeluoikeudet
        LokalisointiService: LokalisointiService
        MurupolkuService: MurupolkuService
        MessageService: MessageService
    )

    it 'should contain henkiloOid after init', inject ($httpBackend) ->
      $httpBackend.flush()
      expect(scope.henkiloOid).toEqual("1.2.3")

  describe 'MurupolkuCtrl', ->
    scope = null

    beforeEach(inject ($controller, $rootScope, MurupolkuService, $location) ->
        scope = $rootScope.$new()
        $controller 'MurupolkuCtrl',
          $scope: scope
          MurupolkuService: MurupolkuService
          $location: $location
    )

    it 'should contain murupolku after init', ->
      expect(scope.murupolku).toEqual([])