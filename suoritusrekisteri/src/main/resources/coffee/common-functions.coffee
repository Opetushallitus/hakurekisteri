getOrganisaatio = ($http, organisaatioOid, successCallback, errorCallback) ->
  $http.get(window.url("organisaatio-service.organisaatio", organisaatioOid),
    cache: true
  ).success(successCallback).error errorCallback
  return

getKoulutusNimi = ($http, koulutusUri, successCallback, errorCallback) ->
  $http.get(window.url("koodisto-service.koodiByUri", "koulutus", koulutusUri),
    cache: true
  ).success((koodi) ->
    if koodi.metadata
      i = 0
      while i < koodi.metadata.length
        meta = koodi.metadata[i]
        return successCallback(meta.nimi)  if meta.kieli is "FI"
        i++
    successCallback koulutusUri).error((error) ->
      if errorCallback
        errorCallback(error))
  return

getHakuNimi = ($http, hakuOid, successCallback) ->
  $http.get(window.url("tarjonta-service.hakuById", hakuOid),
    cache: true
  ).success((json) ->
    if json.result && json.result.nimi
      return successCallback(json.result.nimi.kieli_fi || json.result.nimi.kieli_sv || json.result.nimi.kieli_en)
    else
      console.log("getHakuNimi not found from tarjonta, trying from Kouta")
      $http.get(window.url("kouta-internal.hakuByOid", hakuOid),
      cache: true
      ).success((json) ->
        console.log("getHakuNimi Response from kouta: ", json)
        if json && json.nimi
          return successCallback(json.nimi.fi || json.nimi.sv || json.nimi.en)
      )
    successCallback "")
  return

getHakukohdeNimi = ($http, hakukohdeOid, successCallback) ->
  $http.get(window.url("tarjonta-service.hakukohdeById", hakukohdeOid),
    cache: true
  ).success((json) ->
    if json.result && json.result.hakukohteenNimet
      return successCallback(json.result.hakukohteenNimet.kieli_fi || json.result.hakukohteenNimet.kieli_sv || json.result.hakukohteenNimet.kieli_en)
    else
      console.log("getHakukohdeNimi not found from tarjonta, trying from Kouta")
      $http.get(window.url("kouta-internal.hakukohdeByOid", hakukohdeOid),
        cache: true
      ).success((json) ->
        console.log("getHakukohdeNimi Response from kouta: ", json)
        if json && json.nimi
          return successCallback(json.nimi.fi || json.nimi.sv || json.nimi.en)
      )
    successCallback "")
  return

getOphMsg = (key, def) ->
  if window.globalGetOphMsg
    window.globalGetOphMsg key, def
  else
    key

getKoodistoAsOptionArray = ($http, koodisto, options, valueFromField = "koodiArvo", capitalizeValues = false) ->
  $http.get(window.url("koodisto-service.koodisByKoodisto", koodisto), { cache: true }).then (response) ->
    if response.data
      for koodi in response.data
        do (koodi) ->
          metaLangMap = {}
          koodi.metadata.forEach (m) -> metaLangMap[m.kieli.toLowerCase()] = m

          meta = metaLangMap[window.userLang] or metaLangMap.fi

          value = koodi.koodiUri + "#" + koodi.versio
          value = meta.nimi  if valueFromField is "nimi"
          value = koodi.koodiArvo  if valueFromField is "koodiArvo"
          options.push
            value: if capitalizeValues then value.toLowerCase().capitalize() else value
            text: meta.nimi

    options.sort (a, b) ->
      return 0  if a.text is b.text
      (if a.text < b.text then -1 else 1)
    return
  return

collect = (arr) ->
  ret = {}
  len = arr.length
  i = 0
  while i < len
    for p of arr[i]
      ret[p] = arr[i][p]  if arr[i].hasOwnProperty(p)
    i++
  ret

parseFinDate = (date) ->
  if typeof date is "object"
    return date
  else if typeof date is "string" and date.trim().match(/[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{4}/)
    dateParts = date.trim().split(".")
    return new Date(parseInt(dateParts[2], 10), parseInt(dateParts[1], 10) - 1, parseInt(dateParts[0], 10))
  null

sortByFinDateDesc = (a, b) ->
  aDate = parseFinDate(a)
  aDate = new Date(1000, 0, 1)  unless aDate
  bDate = parseFinDate(b)
  bDate = new Date(1000, 0, 1)  unless bDate
  (if aDate > bDate then -1 else (if aDate < bDate then 1 else 0))

ensureConsoleMethods = ->
  method = undefined
  noop = ->

  methods = [
    "assert"
    "clear"
    "count"
    "debug"
    "dir"
    "dirxml"
    "error"
    "exception"
    "group"
    "groupCollapsed"
    "groupEnd"
    "info"
    "log"
    "markTimeline"
    "profile"
    "profileEnd"
    "table"
    "time"
    "timeEnd"
    "timeStamp"
    "trace"
    "warn"
  ]
  length = methods.length
  console = (window.console = window.console or {})
  while length--
    method = methods[length]
    console[method] = noop  unless console[method]
  return

msgCategory = "suoritusrekisteri"

String::hashCode = ->
  hash = 0
  return hash  if @length is 0
  i = 0

  while i < @length
    hash = ((hash << 5) - hash) + @charCodeAt(i)
    hash = hash & hash
    i++
  hash

String::capitalize = ->
  @charAt(0).toUpperCase() + @slice(1)

unless Object.keys
  Object.keys = (o) ->
    throw new TypeError("Object.keys called on a non-object")  if o isnt Object(o)
    k = []
    p = undefined
    for p of o
      continue
    k

unless Array::diff
  Array::diff = (a) ->
    @filter (i) ->
      a.indexOf(i) < 0

unless Array::getUnique
  Array::getUnique = ->
    u = {}
    a = []
    i = 0
    l = @length

    while i < l
      if u.hasOwnProperty(this[i])
        ++i
        continue
      a.push this[i]
      u[this[i]] = 1
      ++i
    a

unless Array.isArray
  Array.isArray = (arg) ->
    Object::toString.call(arg) is "[object Array]"

changeDetection = (object) ->
  json = JSON.stringify(object)
  {
  object: object
  hasChanged: () ->
    json != JSON.stringify(object)
  update: () ->
    json = JSON.stringify(object)
  original: () ->
    JSON.parse(json)
  }

arrayCarousel = (args...) ->
  if args.length == 0
    throw new Error("Args was empty array")
  index = 0
  o = {
    value: args[0]
    next: () ->
      o["value"] = args[index = (index + 1) % args.length]
  }

deleteFromArray = (obj, arr) ->
  index = arr.indexOf(obj)
  if index != -1
    arr.splice index, 1

notEmpty = (s) ->
  s && s.length > 0

lastIndex = (arr, fn) ->
  i = 0
  index = -1
  for o in arr
    if fn(o)
      index = i
    i = i + 1
  index

getCopyToClipboardFn = (clipboard) -> (text) -> clipboard.copyText(text)

(->
  ensureConsoleMethods()
)()