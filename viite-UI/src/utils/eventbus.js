/* eslint-disable new-cap */
export const eventbus = Backbone.Events;

eventbus.on('all', function (eventName, entity) {
    if (window.DR2_LOGGING && eventName !== 'map:mouseMoved') {
      console.log(eventName, entity);
    }
  });

eventbus.oncePromise = function (eventName) {
  var eventReceived = $.Deferred();
  eventbus.once(eventName, function () {
    eventReceived.resolve();
  });
  return eventReceived;
};

window.eventbus = eventbus;

