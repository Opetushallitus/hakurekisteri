app.run(function($log) {
    if (window.globalInitOphMsg) window.globalInitOphMsg(function() { $log.info("messages loaded") });
});