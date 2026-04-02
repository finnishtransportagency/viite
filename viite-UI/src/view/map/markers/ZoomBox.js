/**
 * ZoomBox - Renders an OpenLayers zoom control on the map.
 */
import { zoomlevels } from '@utils/ZoomLevels.js';

export function ZoomBox(map, container, appModel) {
  const applicationModel = appModel || window.applicationModel;
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
    if (applicationModel.canZoomOut() && applicationModel.canZoomOutEditMode()) {
      const zoom = zoomlevels.getViewZoom(map);
      map.getView().animate({
        zoom: zoom - 1,
        duration: 150
      });
    } else {
      new Confirm();
    }
  });
}

window.ZoomBox = ZoomBox;
