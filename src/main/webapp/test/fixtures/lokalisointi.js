function lokalisointiFixtures() {
    var httpBackend = testFrame().httpBackend
    httpBackend.when('GET', /.*\/lokalisointi\/cxf\/rest\/v1\/.*/).passThrough()
}