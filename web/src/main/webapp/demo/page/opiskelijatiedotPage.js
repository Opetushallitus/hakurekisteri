function opiskelijatiedotPage() {
  function isLocalhost() {
    return location.host === 'localhost:8080'
  }
  var opiskelijatiedotPage = openPage((isLocalhost() ? '' : '/suoritusrekisteri') + "/#/muokkaa-obd", function() {
    return S("#filterForm").length === 1
  })

  var pageFunctions = {
    filterForm: function() {
      return S("#filterForm").first()
    },
    organizationSearch: function() {
      return S("#organisaatioTerm")
    },
    searchButton: function() {
      return Button(function() {
        return S("#filterForm button[type=submit]").first()
      })
    },
    dropDownMenu: function() {
      return S("#filterForm ul.dropdown-menu").first()
    },
    resultsTable: function() {
      return S("#table-scroller").find("tr")
    },
    openPage: function() {
      return opiskelijatiedotPage()
        .then(wait.until(function() {
          return pageFunctions.filterForm().length === 1
        }))
    }
  };

  return pageFunctions;
}