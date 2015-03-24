/* master at https://github.com/Opetushallitus/haku/tree/master/haku-app/src/main/webapp/test/util/test-dsl.js */

testDslDebug = false
function dslDebug() {
    if(testDslDebug) {
        var args = Array.prototype.slice.call(arguments);
        args.splice(0, 0, "test-dsl -")
        console.log.apply(console, args);
    }
}

function wrap(elementDefinition) {
    switch (typeof(elementDefinition)) {
        case 'string':
            return function() {
                dslDebug("S("+elementDefinition+")")
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
        dslDebug("visible", fn().is(':visible'))
        return fn().is(':visible');
    })
}

function input1(fn, value) {
    return seq(
        visible(fn),
        function() {
            dslDebug("element visible and ready for input1", value)
            return fn().val(value).change().blur();
        });
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
    var fns =  Array.prototype.slice.call(arguments);
    return function() {
        dslDebug("click")
        var clickSequence = fns.map(function(fn) {
            return seq(
                visible(fn),
                function() {
                    dslDebug("element visible and ready to click. matched elements: ", fn().length)
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

function select(fn, value) {
    return seq(
        visible(fn),
        wait.until(function() {
            var matches = fn().find('option[value="' + value + '"]').length;
            if (matches > 1) {
                throw new Error('Value "' + value + '" matches ' + matches + ' <option>s from <select> ' + fn().selector)
            }
            return matches === 1;
        }),
        input(fn, value));
}

