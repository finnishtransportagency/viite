export const eventbus = Backbone.Events;

eventbus.oncePromise = function (eventName) {
  const eventReceived = new $.Deferred();
  eventbus.once(eventName, function () {
    eventReceived.resolve();
  });
  return eventReceived;
};
