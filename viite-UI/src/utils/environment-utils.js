(function (root) {
  var urlParts = function () {
    var url = window.location.href.split('/');
    return _.filter(url, function (urlPart) {
      return !_.isEmpty(urlPart);
    });
  };

  var name = function () {
    var environmentName = {
      'viite.vaylapilvi.fi': 'production', // PROD
      'viitetest.testivaylapilvi.fi': 'integration', // QA
      'viitedev.testivaylapilvi.fi': 'staging' // DEV
    };

    return environmentName[urlParts()[1]] || 'unknown';
  };

  var urlPath = function () {
    var urlWithoutResource = _.initial(urlParts());
    return _.head(urlWithoutResource) + '//' + _.tail(urlWithoutResource).join('/');
  };

  // Environment name shown next to the Viite logo
  var localizedName = function () {
    var localizedEnvironmentName = {
      integration: 'Integraatiotestiympäristö', // Hyväksymistestausympäristö
      production: '',
      staging: 'Testiympäristö',
      unknown: 'Kehitysympäristö'
    };
    return localizedEnvironmentName[Environment.name()];
  };

  root.Environment = {
    name: name,
    urlPath: urlPath,
    localizedName: localizedName
  };
}(this));
