describe 'common-functions', ->
  describe 'getBaseUrl', ->
    it 'should return luokka', ->
      expect(getBaseUrl()).toEqual("https://itest-virkailija.oph.ware.fi")