/**
 * Resolves the current deployment environment name and base URL from the browser location.
 * Provides localized environment labels for the main UI chrome.
 */
const urlParts = function () {
  const url = window.location.href.split('/');
  return _.filter(url, function (urlPart) {
    return !_.isEmpty(urlPart);
  });
};

const name = function () {
  const environmentName = {
    'viite.vaylapilvi.fi': 'production', // PROD
    'viitetest.testivaylapilvi.fi': 'integration', // QA
    'viitedev.testivaylapilvi.fi': 'staging' // DEV
  };

  return environmentName[urlParts()[1]] || 'unknown';
};

const urlPath = function () {
  const urlWithoutResource = _.initial(urlParts());
  return `${_.head(urlWithoutResource)}//${_.tail(urlWithoutResource).join('/')}`;
};

// Environment name shown next to the Viite logo
const localizedName = function () {
  const localizedEnvironmentName = {
    integration: 'Integraatiotestiympäristö', // Hyväksymistestausympäristö
    production: '',
    staging: 'Testiympäristö',
    unknown: 'Kehitysympäristö'
  };
  return localizedEnvironmentName[Environment.name()];
};

export const Environment = {
  name: name,
  urlPath: urlPath,
  localizedName: localizedName
};

window.Environment = Environment;

