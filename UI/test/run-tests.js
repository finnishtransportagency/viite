require.config({
  paths: {
    'underscore': '../../node_modules/underscore/underscore',
    'jquery': '../../node_modules/jquery/dist/jquery.min',
    'lodash': '../../node_modules/lodash/index',
    'moment': '../../node_modules/moment/moment',
    'backbone': '../../node_modules/backbone/backbone',
    'chai': '../../node_modules/chai/chai',
    'EventBus': '../src/utils/eventbus',
    'Backend': '../src/utils/backend-utils',
    'GeometryUtils': '../src/utils/GeometryUtils',
    'RoadCollection': '../src/model/RoadCollection',
    'zoomlevels': '../src/utils/zoom-levels',
    'geometrycalculator': '../src/utils/geometry-calculations',
    'dateutil': '../src/utils/date-utils',
    'LocationInputParser': '../src/utils/LocationInputParser',
    'RoadAddressTestData': '../test_data/RoadAddressTestData',
    'RoadLinkTestData': '../test_data/RoadLinkTestData',
    'UserRolesTestData': '../test_data/UserRolesTestData',
    'SplittingTestData': '../test_data/SplittingTestData'
  },
  shim: {
    'jquery': {exports: '$'},
    'lodash': {exports: '_'},
    'backbone': {
      deps: ['jquery', 'underscore'],
      exports: 'Backbone'
    },
    'EventBus': {
      deps: ['backbone']
    },
    'Layer': { exports: 'Layer' },
    'RoadCollection': { exports: 'RoadCollection' },
    'geometrycalculator': { exports: 'geometrycalculator' },
    'moment': { exports: 'moment'},
    'dateutil': { exports: 'dateutil' },
    'LocationInputParser': { exports: 'LocationInputParser' },
    'GeometryUtils': { exports: 'GeometryUtils' },
    'RoadAddressTestData': { exports: 'RoadAddressTestData' },
    'RoadLinkTestData': { exports: 'RoadLinkTestData' },
    'UserRolesTestData': { exports: 'UserRolesTestData' },
    'validitydirections': { exports: 'validitydirections' },
    'SplittingTestData': { exports: 'SplittingTestData' }
  },
  waitSeconds: 10
});
require(['lodash', 'moment', 'GeometryUtils',
  'unit-tests/geometry-calculations-spec',
  'unit-tests/GeometryUtilsSpec',
  'unit-tests/date-utils-spec',
  'unit-tests/LocationInputParserSpec'],
  function (lodash, moment) {
    window._ = lodash;
    window.moment = moment;
    mocha.checkLeaks();
    if (window.mochaPhantomJS) {
      mochaPhantomJS.run();
    } else {
      mocha.run();
    }
  }
);
