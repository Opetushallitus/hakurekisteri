/* master at https://github.com/Opetushallitus/haku/tree/master/haku-app/src/main/webapp/test/util/test-dsl.js */

function wrap(elementDefinition) {
    switch (typeof(elementDefinition)) {
        case 'string':
            return function() {
                return S(elementDefinition);
            };
        case 'function':
            return function() {
                var args = arguments;
                return function() {
                    return S(elementDefinition.apply(this, args));
                };
            };
        default:
            throw new Error("Element definitions need to be strings or functions that return strings")
    }
}

function initSelectors(elements) {
    return Object.keys(elements).reduce(function(agg, key) {
        agg[key] = wrap(elements[key]);
        return agg;
    }, {})
}

function seq(/* ...promises */) {
    var promises = arguments;
    return function() {
        return Array.prototype.slice.call(promises).reduce(Q.when, Q());
    }
}

function seqDone(/* ...promises */) {
    var promiseArgs = arguments;
    return function(done) {
        return seq.apply(this, promiseArgs)().then(function() { return done(); }, done);
    }
}

function visible(fn) {
    if (typeof(fn) !== 'function') {
        throw new Error('visible() got a non-function: ' + fn);
    }
    return wait.until(function() {
        return fn().is(':visible');
    })
}

function input1(fn, value) {
    return seq(
        visible(fn),
        function() { return fn().val(value).change().blur(); });
}

function input(/* fn, value, fn, value, ... */) {
    var argv = Array.prototype.slice.call(arguments);
    if (argv % 2 === 0) {
        throw new Error("inputs() got odd number of arguments. Give input function and value argument for each input.")
    }
    var sequence = [];
    for (var i = 0; i < argv.length; i += 2) {
        sequence.push(input1(argv[i], argv[i + 1]));
    }
    return seq.apply(this, sequence);
}

function click(/* ...promises */) {
    var fns = arguments;
    return function() {
        var clickSequence = Object.keys(fns).map(function(i) {
            var fn = fns[i];
            return seq(
                visible(fn),
                function() {
                    fn().click();
                });
        });
        return clickSequence.reduce(Q.when, Q());
    }
}

function sleep(ms) {
    return function() {
        return Q.delay(ms);
    }
}

