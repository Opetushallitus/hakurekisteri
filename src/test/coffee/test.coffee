describe 'Controllers', ->
  beforeEach(module('myApp'))

  describe 'MurupolkuCtrl', ->
    scope = null

    beforeEach(inject ($controller, $rootScope) ->
      scope = $rootScope.$new()
      MurupolkuService = {
        murupolku: ->
          []
      }
      location = {}
      $controller 'MurupolkuCtrl',
        $scope: scope
        MurupolkuService: MurupolkuService
        $location: location
    )

    it 'should contain murupolku', ->
      expect(scope.murupolku()).toEqual([])
