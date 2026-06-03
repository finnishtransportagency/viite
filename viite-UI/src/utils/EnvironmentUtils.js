/**
 * Resolves the current deployment environment name and base URL from the browser location.
 * Provides localized environment labels for the main UI chrome.
 */
const currentUrl = function () {
  return new URL(window.location.href);
};

const name = function () {
  const environmentName = {
    'viite.vaylapilvi.fi': 'production', // PROD
    'viitetest.testivaylapilvi.fi': 'integration', // QA
    'viitedev.testivaylapilvi.fi': 'staging' // DEV
  };

  return environmentName[currentUrl().hostname] || 'unknown';
};

// Environment name shown next to the Viite logo
const localizedName = function () {
  const localizedEnvironmentName = {
    integration: 'Integraatiotestiympäristö', // Hyväksymistestausympäristö
    production: '',
    staging: 'Testiympäristö',
    unknown: 'Kehitysympäristö'
  };
  const environment = name();
  return localizedEnvironmentName[environment];
};

const browserTitle = function () {
  const environmentTitle = {
    integration: 'Viite - QA',
    production: 'Viite',
    staging: 'Viite - DEV',
    unknown: 'Viite - LOCAL'
  };
  const environment = name();
  return environmentTitle[environment] || 'Viite';
};

export const Environment = {
  name: name,
  localizedName: localizedName,
  browserTitle: browserTitle
};
