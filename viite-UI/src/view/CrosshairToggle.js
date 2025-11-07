/**
 * CrosshairToggle - Displays a crosshair in the center of the map and provides click simulation for E2E testing.
 *
 * E2E Testing Usage:

 * Example:
 *   // Wait for map to load, then click at crosshair
 *   await page.evaluate(() => window.__crosshairToggle.clickAtCrosshair());
 */

window.CrosshairToggle = function (parentElement, map) {
  if (!map) {
    console.error('Map instance is required for CrosshairToggle');
    return;
  }

  const self = this;
  self.map = map;
  const crosshairSelector = '.crosshair';

  const crosshairToggle = $(
    '<div class="crosshair-wrapper">' +
      '<div class="checkbox">' +
        '<label>' +
          '<input type="checkbox" name="crosshair" value="crosshair" checked="true"/> Kohdistin' +
        '</label>' +
      '</div>' +
    '</div>'
  );

  const render = () => {
    parentElement.append(crosshairToggle);
  };

  const bindEvents = () => {
    $('input', crosshairToggle).change(function () {
      $(crosshairSelector).toggle(this.checked);
    });

    // Keyboard shortcut: Shift + C
    $(document).on('keydown', function (e) {
      if (e.key.toLowerCase() === 'c' && e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
        e.preventDefault();
        self.clickAtCrosshair();
      }
    });
  };

  // Public method to get crosshair screen position
  self.getCrosshairPosition = function () {
    const crosshairDom = document.querySelector(crosshairSelector);
    if (!crosshairDom) {
      console.warn('Crosshair element not found');
      return null;
    }
    const rect = crosshairDom.getBoundingClientRect();
    return {
      x: Math.round(rect.left + (rect.width / 2)),
      y: Math.round(rect.top + (rect.height / 2))
    };
  };

  // Helper to create a native browser click event
  const createNativeClickEvent = (x, y) => {
    const eventInit = {
      bubbles: true,
      cancelable: true,
      view: window,
      clientX: x,
      clientY: y,
      screenX: x,
      screenY: y,
      pageX: x + window.pageXOffset,
      pageY: y + window.pageYOffset,
      button: 0,
      buttons: 0,
      detail: 1
    };

    let clickEvent;
    try {
      // Modern approach
      clickEvent = new MouseEvent('click', eventInit);
    } catch (e) {
      // Fallback for older browsers
      clickEvent = document.createEvent('MouseEvent');
      clickEvent.initMouseEvent(
        'click',
        true, // bubbles
        true, // cancelable
        window,
        1, // detail (click count)
        x, // screenX
        y, // screenY
        x, // clientX
        y, // clientY
        false, // ctrlKey
        false, // altKey
        false, // shiftKey
        false, // metaKey
        0, // button (left click)
        null // relatedTarget
      );
    }

    return clickEvent;
  };

  // Helper to create pointer events (OpenLayers uses these)
  const createPointerEvent = (type, x, y) => {
    const eventInit = {
      bubbles: true,
      cancelable: true,
      view: window,
      clientX: x,
      clientY: y,
      screenX: x,
      screenY: y,
      pageX: x + window.pageXOffset,
      pageY: y + window.pageYOffset,
      button: 0,
      buttons: type === 'pointerup' ? 0 : 1,
      pointerId: 1,
      pointerType: 'mouse',
      isPrimary: true
    };

    let event;
    try {
      event = new PointerEvent(type, eventInit);
    } catch (e) {
      // Fallback to MouseEvent if PointerEvent not supported
      try {
        event = new MouseEvent(type, eventInit);
      } catch (e2) {
        event = document.createEvent('MouseEvent');
        event.initMouseEvent(
          type,
          true, true, window, 1,
          x, y, x, y,
          false, false, false, false,
          0, null
        );
      }
    }

    return event;
  };

  // Public method to simulate click at crosshair
  self.clickAtCrosshair = function () {
    if (!self.map) {
      console.warn('Map instance not found');
      return;
    }

    const crosshairPos = self.getCrosshairPosition();
    if (!crosshairPos) {
      console.warn('Crosshair position not found');
      return;
    }

    // Get the map viewport element (where OpenLayers listens for events)
    const viewport = self.map.getViewport();
    const rect = viewport.getBoundingClientRect();
    const pixel = [crosshairPos.x - rect.left, crosshairPos.y - rect.top];
    const coordinate = self.map.getCoordinateFromPixel(pixel);

    // console.log('Simulating click at crosshair:', {
    //   crosshairPos: crosshairPos,
    //   pixel: pixel,
    //   coordinate: coordinate
    // });

    // Get the actual canvas or target element inside viewport
    const target = viewport.querySelector('canvas') || viewport;

    // Simulate the full click sequence that OpenLayers expects
    // 1. pointerdown
    const pointerDownEvent = createPointerEvent('pointerdown', crosshairPos.x, crosshairPos.y, target);
    target.dispatchEvent(pointerDownEvent);

    const pointerUpEvent = createPointerEvent('pointerup', crosshairPos.x, crosshairPos.y, target);
    target.dispatchEvent(pointerUpEvent);

    // 3. click event
    setTimeout(function () {
      const clickEvent = createNativeClickEvent(crosshairPos.x, crosshairPos.y, target);
      target.dispatchEvent(clickEvent);

      // Collect features for reporting
      const features = [];
      self.map.forEachFeatureAtPixel(pixel, function (feature, layer) {
        features.push({
          feature: feature,
          layer: layer,
          linkData: feature.linkData || null
        });
      });

      // Dispatch custom event for E2E test automation
      window.dispatchEvent(new CustomEvent('crosshairFeatureClick', {
        detail: {
          features: features,
          pixel: pixel,
          coordinate: coordinate,
          hasFeatures: features.length > 0
        }
      }));
    }, 10);
  };

  const show = () => {
    render();
    bindEvents();
  };

  show();

  // Expose for E2E testing
  window.__crosshairToggle = self;
};