/**
 * Creates a UI toggle and a crosshair overlay logic for OpenLayers maps. 
 * Primarily used to facilitate E2E testing by allowing programmatic clicks 
 * at the exact center of the map viewport.
 * The crosshair is created at MapView.js
 * 
 * * E2E USAGE:
 * Trigger click at crosshair:
 * await page.evaluate(() => window.crosshair.click());
 * 
 * Capture data of the clicked feature:
 * const data = await page.evaluate(() => window.crosshair.click());
 */
window.createCrosshairToggle = (parentElement, map, onFeatureClick = null) => {
  const crosshairSelector = '.crosshair';

  const performClick = (callback, isDoubleClick = false) => {
    const coords = getCrosshairCenter();
    if (!coords) return;

    // Extract data
    const clickData = getFeatureDataAtAtPixel(coords.x, coords.y);

    // Simulate events
    dispatchMapEvents(coords.x, coords.y, isDoubleClick);

    // Notifications
    console.log('Crosshair Click Data:', clickData);
    const finalCallback = callback || onFeatureClick;
    if (typeof finalCallback === 'function') finalCallback(clickData);

    window.dispatchEvent(new CustomEvent('crosshairFeatureClick', { detail: clickData }));
  };

  // Initialization
  const $crosshairElement = createUI();

  $(document).on('keydown', onKeyDown);
  parentElement.append($crosshairElement);

  // Returned interface API that other files can access
  return {
    click: () => performClick(), // Click the map at the crosshair coordinates
    doubleClick: () => performClick(null, true), // Double click at the crosshair coordinates
    getData: () => { // Returns link data for instance
      const coords = getCrosshairCenter();
      return coords ? getFeatureDataAtAtPixel(coords.x, coords.y) : null;
    },
    destroy: () => { // Clean up unused listeners
      $(document).off('keydown', onKeyDown);
      $crosshairElement.remove();
    }
  };

  // Helper functions

  function getFeatureDataAtAtPixel(x, y) {
    const viewport = map.getViewport();
    const viewRect = viewport.getBoundingClientRect();
    const pixel = [x - viewRect.left, y - viewRect.top];
    const features = [];

    map.forEachFeatureAtPixel(pixel, (feature, layer) => {
      features.push({
        feature: feature,
        layer: layer,
        properties: feature.getProperties(),
        linkData: feature.linkData || null
      });
    });

    return { features, pixel, coordinate: map.getCoordinateFromPixel(pixel), hasFeatures: features.length > 0 };
  }

  // Handles the canvas click
  function dispatchMapEvents(x, y, isDoubleClick = false) {
    const viewport = map.getViewport();
    const target = viewport.querySelector('canvas') || viewport;
    const eventInit = { clientX: x, clientY: y, bubbles: true, detail: 1 };

    if (isDoubleClick) {
      // For double click, dispatch two click events followed by a double click event
      target.dispatchEvent(new PointerEvent('pointerdown', { ...eventInit, buttons: 1 }));
      target.dispatchEvent(new PointerEvent('pointerup', { ...eventInit, buttons: 0 }));
      // console.log("Click");
      setTimeout(() => {
        target.dispatchEvent(new PointerEvent('pointerdown', { ...eventInit, buttons: 1 }));
        target.dispatchEvent(new PointerEvent('pointerup', { ...eventInit, buttons: 0 }));
        target.dispatchEvent(new PointerEvent('dblclick', { ...eventInit, detail: 2 }));
        // console.log("Click");
      }, 50); // Small delay between clicks to simulate double click
    } else {
      target.dispatchEvent(new PointerEvent('pointerdown', { ...eventInit, buttons: 1 }));
      target.dispatchEvent(new PointerEvent('pointerup', { ...eventInit, buttons: 0 }));
    }
  }

  function getCrosshairCenter() {
    const el = document.querySelector(crosshairSelector);
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    return {
      x: rect.left + (rect.width / 2),
      y: rect.top + (rect.height / 2)
    };
  }

  function createUI() {
    const $element = $(`
      <div class="crosshair-wrapper">
        <div class="checkbox">
          <label><input type="checkbox" name="crosshair" checked="true"/> Kohdistin</label>
        </div>
      </div>
    `);
    $element.find('input').on('change', (e) => $(crosshairSelector).toggle(e.target.checked));
    return $element;
  }

  // Debugging / alternate way to trigger click via shift + c or shift + x
  function onKeyDown(e) {
    if (e.shiftKey && e.key === 'ArrowRight') {
      e.preventDefault();
      performClick();
    } else if (e.shiftKey && e.key === 'ArrowLeft') {
      e.preventDefault();
      performClick(null, true);
    }
  }
};