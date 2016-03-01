var assert = require('assert');

describe('oph_urls.js', function() {
    var ctx = require("../../../main/webapp/oph_urls.js")
    beforeEach(function() {
        ctx.urls.properties = {}
        ctx.urls.defaults = {
            encode: true
        }
        ctx.urls.override = {}
    });

    it('resolve url and throw error on unknown', function () {
        ctx.urls.properties = {
            "a.b": "1"
        }

        assert.equal(ctx.url("a.b"), "1");

        assert.throws(function() {
            ctx.url("b.b")
        }, function(err){
            assert.equal(err.message, "Could not resolve value for 'b.b'")
            return true;
        })
    });

    it('handle baseUrl', function () {
        ctx.urls.properties = {
            "a.a": "1",
            "b.b": "2",
            "c.c": "3",
            "a.baseUrl": "http://pow",
            "baseUrl": "http://bar"
        }

        assert.equal(ctx.url("a.a"), "http://pow/1");
        assert.equal(ctx.url("b.b"), "http://bar/2");

        // ctx.urls.override overrides baseUrl
        ctx.urls.override = {
            "baseUrl": "http://foo"
        }
        assert.equal(ctx.url("a.a"), "http://pow/1");
        assert.equal(ctx.url("b.b"), "http://foo/2");

        // window.urls() overrides baseUrl and ctx.urls.override
        var ctx2 = ctx.urls({"baseUrl": "http://zap"});
        assert.equal(ctx2.url("a.a"), "http://pow/1");
        assert.equal(ctx2.url("b.b"), "http://zap/2");
    });

    it('parameter replace', function () {
        ctx.urls.properties = {
            "a.a": "/a/$1",
            "b.b": "/b/$param"
        }

        assert.equal(ctx.url("a.a"), "/a/$1");
        assert.equal(ctx.url("a.a",1), "/a/1");
        assert.equal(ctx.url("b.b"), "/b/$param");
        assert.equal(ctx.url("b.b", {
            param: "pow"
        }), "/b/pow");
        // extra named parameters go to queryString
        assert.equal(ctx.url("b.b", {
            param: "pow",
            queryParameter: "123",
            queryParameter2: "123"
        }), "/b/pow?queryParameter=123&queryParameter2=123");
    });

    it('parameter encode', function () {
        ctx.urls.properties = {
            "a.a": "/a/$1",
            "b.b": "/b/$param"
        }
        assert.equal(ctx.url("a.a","1:"), "/a/1%3A");
        assert.equal(ctx.url("b.b", {
            param: "pow:"
        }), "/b/pow%3A");
        assert.equal(ctx.url("b.b", {
            param: "pow",
            "query Parameter": "1:23",
            "query Parameter2": "1:23"
        }), "/b/pow?query%20Parameter=1%3A23&query%20Parameter2=1%3A23");
        var ctx2 = ctx.urls({encode:false})
        assert.equal(ctx2.url("a.a","1:"), "/a/1:");
        assert.equal(ctx2.url("b.b", {
            param: "pow:"
        }), "/b/pow:");
        assert.equal(ctx2.url("b.b", {
            param: "pow",
            "query Parameter": "1:23",
            "query Parameter2": "1:23"
        }), "/b/pow?query Parameter=1:23&query Parameter2=1:23");
    });

    it('parameter and url lookup order', function() {
        ctx.urls.defaults["a.a"] = "b"
        assert.equal(ctx.url("a.a"), "b");

        ctx.urls.properties = {"a.a": "c"}
        assert.equal(ctx.url("a.a"), "c");

        ctx.urls.override["a.a"] = "d"
        assert.equal(ctx.url("a.a"), "d");

        var ctx2 = ctx.urls({"a.a": "e"});
        assert.equal(ctx2.url("a.a"), "e");
    })
});
