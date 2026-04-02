/**
 * Adds a simple map overlay element and exposes show and hide helpers for it.
 * Used to block map interaction during modal or loading states.
 */
export function MapOverlay(container) {
    const element = '<div id="map-overlay" style="display: none"></div>';
    container.append(element);

    const show = function () {
      container.find('#map-overlay').show();
    };

    const hide = function () {
      container.find('#map-overlay').hide();
    };

  return {
    show: show,
    hide: hide
  };
}

window.MapOverlay = MapOverlay;
