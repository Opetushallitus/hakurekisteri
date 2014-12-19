getBaseUrl = ->
  return "https://itest-virkailija.oph.ware.fi"  if location.hostname is "localhost"
  ""

getOrganisaatio = ($http, organisaatioOid, successCallback, errorCallback) ->
  $http.get(organisaatioServiceUrl + "/rest/organisaatio/" + encodeURIComponent(organisaatioOid),
    cache: true
  ).success(successCallback).error errorCallback
  return

getKoulutusNimi = ($http, koulutusUri, successCallback) ->
  $http.get(koodistoServiceUrl + "/rest/json/koulutus/koodi/" + encodeURIComponent(koulutusUri),
    cache: true
  ).success (koodi) ->
    if koodi.metadata
      i = 0
      while i < koodi.metadata.length
        meta = koodi.metadata[i]
        return successCallback(meta.nimi)  if meta.kieli is "FI"
        i++
    successCallback ""
  return

getOphMsg = (key, def) ->
  if window.globalGetOphMsg
    window.globalGetOphMsg key, def
  else
    key

getKoodistoAsOptionArray = ($http, koodisto, kielikoodi, options, valueFromField = "koodiArvo", capitalizeValues = false) ->
  $http.get(getBaseUrl() + "/koodisto-service/rest/json/" + encodeURIComponent(koodisto) + "/koodi", { cache: true }).then (response) ->
    if response.data
      for koodi in response.data
        do (koodi) ->
          for meta in koodi.metadata
            do (meta) ->
              if meta.kieli.toLowerCase() is kielikoodi.toLowerCase()
                value = koodi.koodiUri + "#" + koodi.versio
                value = meta.nimi  if valueFromField is "nimi"
                value = koodi.koodiArvo  if valueFromField is "koodiArvo"
                options.push
                  value: if capitalizeValues then value.toLowerCase().capitalize() else value
                  text: meta.nimi
              return
          return
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

parseFinDate = (d) ->
  (if (d and d.match(/[0-3][0-9]\.[0-1][0-9]\.[0-9]{4}/)) then new Date(parseInt(d.substr(6, 4), 10), parseInt(d.substr(3, 2), 10) - 1, parseInt(d.substr(0, 2), 10)) else null)

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
henkiloServiceUrl = getBaseUrl() + "/authentication-service"
organisaatioServiceUrl = getBaseUrl() + "/organisaatio-service"
hakuAppServiceUrl = getBaseUrl() + "/haku-app"
koodistoServiceUrl = getBaseUrl() + "/koodisto-service"
tarjontaServiceUrl = getBaseUrl() + "/tarjonta-service"
komo =
  ulkomainen: "1.2.246.562.13.86722481404"
  peruskoulu: "1.2.246.562.13.62959769647"
  lisaopetus: "1.2.246.562.5.2013112814572435044876"
  ammattistartti: "1.2.246.562.5.2013112814572438136372"
  maahanmuuttaja: "1.2.246.562.5.2013112814572441001730"
  valmentava: "1.2.246.562.5.2013112814572435755085"
  ylioppilastutkinto: "1.2.246.562.5.2013061010184237348007"

ylioppilastutkintolautakunta = "1.2.246.562.10.43628088406"

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

(->
  ensureConsoleMethods()
  if window.globalInitOphMsg
    window.globalInitOphMsg ->

  return
)()