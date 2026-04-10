/**
 * ZoomBox - Renders an OpenLayers zoom control on the map.
 * @param {Object} map - OpenLayers map instance
 * @param {Object} container - jQuery container for the rendered control
 */
import { zoomlevels } from '@utils/ZoomLevels.js';

export function ZoomBox(map, container) {

  const element = `
    <div class="zoombar" data-position="2">
      <div class="plus"></div>
      <div class="minus"></div>
    </div>
  `;
  container.append(element);
  container.find('.plus').click(function () {
    const zoom = zoomlevels.getViewZoom(map);
    map.getView().animate({
      zoom: zoom + 1,
      duration: 150
    });
  });
  container.find('.minus').click(function () {
      const zoom = zoomlevels.getViewZoom(map);
      map.getView().animate({
        zoom: zoom - 1,
        duration: 150
      });
  });
}
