module.exports = function(config) {
    config.set({
        basePath: '../../',
        preprocessors: {
            "main/webapp/coffee/*.coffee": ["coffee"],
            "test/coffee/*.coffee": ["coffee"]
        },
        coffeePreprocessor: {
            // options passed to the coffee compiler
            options: {
                bare: true,
                sourceMap: false
            },
            // transforming the filenames
            transformPath: function(path) {
                return path.replace(/\.coffee$/, '.js');
            }
        },
        frameworks: ['jasmine'],
        plugins: [
            // these plugins will be require() by Karma
            'karma-coffee-preprocessor',
            'karma-jasmine',
            'karma-junit-reporter',
            'karma-requirejs',
            'karma-phantomjs-launcher'
        ],
        files: [
            'main/webapp/static/js/jquery-1.11.1.min.js',
            'main/webapp/static/js/jquery.fileDownload.js',
            'main/webapp/static/js/bootstrap-3.3.1.min.js',
            'main/webapp/static/js/Chart.min.js',
            'main/webapp/static/js/angular-1.3.7/angular.min.js',
            'main/webapp/static/js/angular-1.3.7/angular-resource.min.js',
            'main/webapp/static/js/angular-1.3.7/angular-route.min.js',
            'main/webapp/static/js/angular-1.3.7/angular-sanitize.min.js',
            'main/webapp/static/js/angular-1.3.7/angular-cookies.min.js',
            'main/webapp/static/js/angular-locale/angular-locale_fi.js',
            'main/webapp/static/js/angular-ui/ui-bootstrap-tpls-0.11.2.min.js',
            'main/webapp/static/js/ng-upload.min.js',

            'main/webapp/coffee/app.coffee',
            'main/webapp/coffee/oph-msg-directive.coffee',
            'main/webapp/coffee/common-functions.coffee',
            'main/webapp/coffee/controller-*.coffee',
            'main/webapp/coffee/configure.coffee',

            'test/js/angular-mocks.js',
            'test/coffee/**/*.coffee'
        ],
        exclude: [
        ],
        // test results reporter to use
        // possible values: 'dots', 'progress', 'junit', 'growl', 'coverage'
        reporters: ['progress','junit'],
        junitReporter: {
            outputFile: '../target/surefire-reports/coffee-test-results.xml'
        },
        port: 9876,
        colors: true,
        logLevel: config.LOG_INFO,
        autoWatch: true,
        // Start these browsers, currently available:
        // - Chrome
        // - ChromeCanary
        // - Firefox
        // - Opera
        // - Safari (only Mac)
        // - PhantomJS
        // - IE (only Windows)
        browsers: ['PhantomJS'],
        captureTimeout: 60000,
        singleRun: true
    });
};