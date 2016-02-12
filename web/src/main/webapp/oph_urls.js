"use strict";

/**
 * window.url("service.info", param1, param2, {key3: value})
 *
 * window.urls(baseUrl).url(key, param)
 * window.urls(baseUrl, {encode: false}).url(key, param)
 * window.urls({baseUrl: baseUrl, encode: false}).url(key, param)
 *
 * Config lookup order: urls_config, window.urls.defaults.override, window.url_properties, window.urls.defaults
 * Lookup key order:
 * * for main url window.url's first parameter: "service.info" from all configs
 * * baseUrl: "service.baseUrl" from all configs and "baseUrl" from all configs
 *
 * window.url_properties = {
 *   "service.status": "/rest/status",
 *   "service.payment": "/rest/payment/$1",
 *   "service.order": "/rest/payment/$orderId"
 *   }
 *
 * window.urls.defaults = {
 *   encode: true,
 *   override: {}
 * }
 */

(function(exportDest) {
    function joinUrl() {
        var args = Array.prototype.slice.call(arguments)
        if(args.length === 0) {
            throw new Error("no arguments");
        }
        var url = null
        args.forEach(function(arg) {
            if(!url) {
                url = arg
            } else {
                if(url.endsWith("/") || arg.startsWith("/")) {
                    url = url + arg
                } else {
                    url = url + "/" + arg
                }
            }
        })
        return url
    }
    exportDest.urls = function() {
        var urls_config = {}

        for (var i = 0; i < arguments.length;  i++) {
            var arg = arguments[i]
            if(typeof arg === "string" || arg == null) {
                urls_config.baseUrl = arg
            } else {
                Object.keys(arg).forEach(function(key){
                    urls_config[key] = arg[key]
                })
            }
        }

        var resolveConfig = function(key, defaultValue) {
            var configs = [urls_config, exportDest.urls.defaults.override, exportDest.url_properties, exportDest.urls.defaults]
            for (var i = 0; i < configs.length; i++) {
                var c = configs[i]
                if(c.hasOwnProperty(key)) {
                    return c[key]
                }
            }
            if(typeof defaultValue == 'function') {
                return defaultValue()
            }
            if(typeof defaultValue == 'undefined') {
                throw new Error("Could not resolve value for '"+key+"'")
            }
            return defaultValue
        }

        var enc = function(arg) {
            arg = arg !== undefined ? arg : ""
            if(resolveConfig("encode")) {
                arg = encodeURIComponent(arg)
            }
            return arg
        }

        var parseService = function(key) {
            return key.substring(0, key.indexOf("."))
        }

        return {
            url: function() {
                var key = Array.prototype.shift.apply(arguments)
                var args = Array.prototype.slice.call(arguments)
                var queryString = "";
                var tmpUrl;
                if(!exportDest.url_properties) {
                    throw new Error("window.url_properties not defined!");
                }
                if(!key) {
                    throw new Error("first parameter 'key' not defined!");
                }
                var url = resolveConfig(key)
                for (var i = args.length; i > 0; i--) {
                    var arg = args[i-1];
                    if(typeof arg === "object") {
                        Object.keys(arg).forEach(function(k){
                            var value = enc(arg[k])
                            tmpUrl = url.replace("$" + k, value)
                            if(tmpUrl == url) {
                                if(queryString.length > 0 ) {
                                    queryString = queryString + "&"
                                } else {
                                    queryString = "?"
                                }
                                queryString = queryString + enc(k) + "=" + value
                            }
                            url = tmpUrl
                        })
                    } else {
                        var value = enc(arg)
                        url = url.replace("$"+i, value)
                    }
                }
                var baseUrl = resolveConfig(parseService(key)+".baseUrl", function(){return resolveConfig("baseUrl", null)})
                if(baseUrl) {
                    url = joinUrl(baseUrl, url)
                }
                return url + queryString
            }
        }
    }

    exportDest.urls.defaults = {
        encode: true,
        override: {}
    }

    exportDest.url = function() {
        var urlResolver = exportDest.urls();
        return urlResolver.url.apply(urlResolver, arguments)
    }
})(typeof window === 'undefined' ? module.exports : window);

// polyfills for IE

if (!String.prototype.startsWith) {
    String.prototype.startsWith = function(searchString, position){
        position = position || 0;
        return this.substr(position, searchString.length) === searchString;
    };
}

if (!String.prototype.endsWith) {
    String.prototype.endsWith = function(searchString, position) {
        var subjectString = this.toString();
        if (typeof position !== 'number' || !isFinite(position) || Math.floor(position) !== position || position > subjectString.length) {
            position = subjectString.length;
        }
        position -= searchString.length;
        var lastIndex = subjectString.indexOf(searchString, position);
        return lastIndex !== -1 && lastIndex === position;
    };
}
