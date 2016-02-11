"use strict";

/**
 * window.url(key, param1, param2, {key3: value}
 * window.urls(baseUrl).url(key, param)
 * window.urls(baseUrl, {encode: false}).url(key, param)
 * window.urls({baseUrl: baseUrl, encode: false}).url(key, param)
 *
 * window.url_properties = {
 *   "service.status": "/rest/status",
 *   "service.payment": "/rest/payment/$1",
 *   "service.order": "/rest/payment/$orderId"
 *   }
 *
 * window.urls.defaults = {
 *   baseUrl: null,
 *   encode: true
 * }
 */
(function(window) {
    function joinUrl() {
        var args = Array.prototype.slice.call(arguments)
        if(args.length === 0) {
            throw new Error("no arguments");
        }
        var url = null
        args.forEach(function(i) {
            if(!url) {
                url = i
            } else if(url.endsWith("/") || i.startsWith("/")) {
                url = url + i;
            } else {
                url = url + +"/" + i
            }
        })
        return url
    }
    window.urls = function() {
        var baseUrl = window.urls.defaults.baseUrl, encode= window.urls.defaults.encode

        for (var i = 0; i < arguments.length;  i++) {
            var arg = arguments[i]
            if(typeof arg === "string" || arg == null) {
                baseUrl = arg
            } else {
                if(arg.baseUrl !== undefined) {
                    baseUrl = arg.baseUrl
                }
                if(arg.encode !== undefined) {
                    encode = arg.encode
                }
            }
        }

        var enc = function(arg) {
            arg = arg !== undefined ? arg : ""
            if(encode) {
                arg = encodeURIComponent(arg)
            }
            return arg
        }

        return {
            url: function() {
                var key = Array.prototype.shift.apply(arguments)
                var args = Array.prototype.slice.call(arguments)
                var queryString = "";
                var tmpUrl;
                if(!window.url_properties) {
                    throw new Error("window.url_properties not defined!");
                }
                if(!key) {
                    throw new Error("first parameter 'key' not defined!");
                }
                var url = window.url_properties[key]
                if(!url) {
                    throw new Error("window.url_properties does not define url for '"+key+"'");
                }
                for (var i = args.length; i > 0; i--) {
                    var arg = args[i-1];
                    if(typeof arg === "string") {
                        var value = enc(arg)
                        url = url.replace("$"+i, value)
                    } else {
                        Object.keys(arg).forEach(function(k){
                            var value = enc(arg[k])
                            tmpUrl = url.replace("$" + key, value)
                            if(tmpUrl === url) {
                                if(queryString.length > 0 ) {
                                    queryString = queryString + "&"
                                } else {
                                    queryString = "?"
                                }
                                queryString = queryString + encodeURIComponent(k) + "=" + value
                            }
                        })
                    }
                }
                if(baseUrl) {
                    url = joinUrl(baseUrl, url)
                }
                return url + queryString
            }
        }
    }

    window.urls.defaults = {
        baseUrl: null,
        decode: true
    }

    window.url = function() {
        var urlResolver = window.urls(null);
        return urlResolver.url.apply(urlResolver, arguments)
    }
})(window);
