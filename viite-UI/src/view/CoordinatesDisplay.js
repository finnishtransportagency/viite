(function (root) {
    root.CoordinatesDisplay = function (map, container) {
        const element = `
          <div class="mapplugin coordinates" data-position="4">
            <div class="cbSpansWrapper">
              <div class="cbRow">
                <div class="cbCrsLabel hide-on-medium">ETRS89-TM35FIN</div>
              </div>
              <div class="cbRow">
                <div class="cbLabel cbLabelN" axis="lat">P:</div>
                <div class="cbValue" axis="lat">lat</div>
              </div>
              <div class="cbRow">
                <div class="cbLabel cbLabelE" axis="lon">I:</div>
                <div class="cbValue" axis="lon">lon</div>
              </div>
            </div>
            <button class="btn btn-sm btn-tertiary" id="mark-coordinates">Merkitse</button>
          </div>
        `;

        container.append(element);

        let centerLonLat = { lon: 0, lat: 0 };
        eventbus.on('map:refresh', (event) => {
            centerLonLat = event.center;
            if (centerLonLat) {
                container.find('.cbValue[axis="lat"]').text(Math.round(centerLonLat[1]));
                container.find('.cbValue[axis="lon"]').text(Math.round(centerLonLat[0]));
            }
        });

        $('#mark-coordinates').on('click', () => {
            eventbus.trigger('coordinates:marked', centerLonLat);
        });
    };
}(this));
