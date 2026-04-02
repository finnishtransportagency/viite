/**
 * ScaleBar - Renders an OpenLayers scale bar control on the map.
 */
export function ScaleBar(map, container) {
  const element = '<div class="scalebar"></div>';
  container.append(element);
  map.addControl(new ol.control.ScaleLine({
    target: container.find('.scalebar')[0],
    className: 'olScaleLine'
  }));
}

window.ScaleBar = ScaleBar;
