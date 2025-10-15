window.CrosshairToggle = function (parentElement, map) {
  if (!map) {
    console.error('Map instance is required for CrosshairToggle');
    return;
  }
  
  this.map = map;
  var crosshairToggle = $('<div class="crosshair-wrapper"><div class="checkbox"><label><input type="checkbox" name="crosshair" value="crosshair" checked="true"/> Kohdistin</label></div></div>');
  var crosshairSelector = '.crosshair';

  var render = function () {
    parentElement.append(crosshairToggle);
  };

  var bindEvents = function () {
    $('input', crosshairToggle).change(function () {
      $(crosshairSelector).toggle(this.checked);
    });

    // Keyboard shortcut: Shift + C to click at crosshair
    $(document).on('keydown', function(e) {
      if (e.key.toLowerCase() === 'c' && e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
        e.preventDefault();
        clickAtCrosshair();
      }
    });
  };

  var clickAtCrosshair = function() {

    if (!this.map) {
      console.warn('Map instance not found');
      return;
    }

    // Get the pixel position of the crosshair
    var crosshairPos = this.getCrosshairPosition();
    if (!crosshairPos) {
      console.warn('Crosshair position not found');
      return;
    }

    // Convert to map pixel coordinates
    var pixel = map.getEventPixel(new MouseEvent('mousemove', {
      clientX: crosshairPos.x,
      clientY: crosshairPos.y
    }));

    // Find features at the crosshair position
    var features = [];
    map.forEachFeatureAtPixel(pixel, function(feature) {

      console.log("Clicked feature: ", feature);

      if (feature.linkData) {
        features.push({
          feature: feature

        });
      }
      return false; // Continue searching for more features
    });

    if (features.length === 0) {
      console.log('No road features found at crosshair position');
      return;
    }

    // Print all link data from features by mapping
    console.log(features.map(function(f) {
      return f.feature.linkData;
    }));
    
    // Trigger a custom event for the test automation to listen to
    var event = new CustomEvent('crosshairFeatureClick', {
      detail: {
        feature: features,
        pixel: pixel
      }
    });
    window.dispatchEvent(event);
  }.bind(this);

  // Public API for test automation
  this.getCrosshairPosition = function() {
    var crosshair = document.querySelector(crosshairSelector);
    if (!crosshair) {
      return null;
    }
    var rect = crosshair.getBoundingClientRect();
    return {
      x: Math.round(rect.left + (rect.width / 2)),
      y: Math.round(rect.top + (rect.height / 2))
    };
  };

  var show = function () {
    render();
    bindEvents();
  };
  show();
};
