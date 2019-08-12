require.config({
  paths: {
    jquery:                           '../../node_modules/jquery/dist/jquery.min',
    chai:                             '../../node_modules/chai/chai',
    'chai-jquery':                    '../../node_modules/chai-jquery/chai-jquery',
    eventbus:                         '../src/utils/eventbus',
    RoadAddressTestData:              '../test_data/RoadAddressTestData',
    RoadLinkTestData:                 '../test_data/RoadLinkTestData',
    UserRolesTestData:                '../test_data/UserRolesTestData',
    RoadAddressProjectTestData:       '../test_data/RoadAddressProjectTestData'
    //SplittingTestData:                '../test_data/SplittingTestData'
  },
  shim: {
    'chai-jquery': ['jquery', 'chai'],
    'eventbus': { exports: 'eventbus' },
    'RoadAddressTestData': { exports: 'RoadAddressTestData' },
    'RoadAddressProjectTestData': { exports: 'RoadAddressProjectTestData' },
    'RoadLinkTestData': { exports: 'RoadLinkTestData' },
    'UserRolesTestData': { exports: 'UserRolesTestData' }
    //'SplittingTestData': { exports: 'SplittingTestData' }
  },
  waitSeconds: 10
});
require(['chai',
         'chai-jquery',
         'TestHelpers',
         //'integration-tests/FloatingRoadAddressSpec',
         //'integration-tests/SplittingRoadAddressSpec',
         'integration-tests/RoadAddressProjectSpec'
        ],
        function(chai, chaiJquery, testHelpers) {
  chai.use(chaiJquery);
  chai.config.includeStack = true;
  //Workaround to give PhantomJS openlayers support
  Function.prototype.bind = Function.prototype.bind || function (thisp) {
    var fn = this;
    return function () {
      return fn.apply(thisp, arguments);
    };
  };
  window.requestAnimationFrame = window.requestAnimationFrame || function(callback){
    window.setTimeout(callback, 1000 / 60);
  };
  //Workaround to get WMTSCapabilities
  window.fetch = function(url,options){
    return { then: function(callback){
      callback({ text: function(){return '';} });
        return {  then: function(requestCallback){
            requestCallback();
        }};
      }
    };
  };
  eventbus.once('map:initialized', function() {
    if (window.mochaPhantomJS) { mochaPhantomJS.run(); }
    else { mocha.run(); }
  });

  Application.start(testHelpers.defaultBackend(), false);
});

