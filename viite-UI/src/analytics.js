(function(root) {
  var environmentProperty = function() {
    var properties = {
      integration: 'UA-57190819-5',
      production: 'UA-57190819-4',
      staging: 'UA-57190819-2',
      unknown: 'UA-57190819-1'
    };
    return properties[Environment.name()];
  };

  var environmentConfiguration = function() {
    var configurations = {
      integration: 'auto',
      production: 'auto',
      staging: 'auto',
      unknown: 'none'
    };
    return configurations[Environment.name()];
  };

  var start = function() {
    ga('create', environmentProperty(), environmentConfiguration());
    ga('send', 'pageview');
    if(window.eventbus) {
      eventbus.on('all', function(eventName, eventParams) {
        var excludedEvents = [
          'map:mouseMoved',
          'map:refresh',
          'map:clicked',
          'asset:saving',
          'asset:moved',
          'roadLinks:beforeDraw',
          'roadLinks:afterDraw'];
        if (!_.contains(excludedEvents, eventName)) {
          var splitName = eventName.split(':');
          var category = splitName[0];
          var action = splitName[1];
          ga('send', 'event', category, action);
        }
      });
    }
  };

  root.Analytics = {
    start: start
  };
}(this));