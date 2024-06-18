function vastaanottotiedotFixtures() {
    var httpBackend = testFrame().httpBackend
    httpBackend.when('GET', /.*vastaanottotiedot\/1.2.246.562.24.*/).passThrough()
}