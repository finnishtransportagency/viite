(function (root) {
  root.RoadLayer = function (map) {
    Layer.call(this, map);

    window.ViiteState = window.ViiteState || {}; // Global variable for trackin state like node translation

    var me = this;
    var roadLinkStyler = new RoadLinkStyler();

    var roadVector = new ol.source.Vector({});

    var roadLayer = new ol.layer.Vector({
      source: roadVector,
      style: function (feature) {
        return roadLinkStyler.getRoadLinkStyles(feature.linkData, map);
      }
    });
    roadLayer.setVisible(true);
    roadLayer.set('name', 'roadLayer');

    var infoContainer = document.getElementById('popup');
    var infoContent = document.getElementById('popup-content');

    var overlay = new ol.Overlay(({
      element: infoContainer
    }));

    map.addOverlay(overlay);

    const displayRoadAddressInfo = (event, pixel) => {
      const featureAtPixel = map.forEachFeatureAtPixel(pixel, (feature) => feature);
      let coordinate;
      const popupBox = document.getElementById('popup-content').getBoundingClientRect();

      // Prevent update when cursor is in the box
      if (!(event.originalEvent.clientX < popupBox.right &&
          event.originalEvent.clientX > popupBox.left &&
          event.originalEvent.clientY > popupBox.top &&
          event.originalEvent.clientY < popupBox.bottom)) {

        // Ignore if target feature is marker
        if (!_.isNil(featureAtPixel) && featureAtPixel.linkData) {
          const roadData = featureAtPixel.linkData;

          if (infoContent !== null) {
            if (roadData.roadNumber !== 0 && roadData.roadPartNumber !== 0) {
              coordinate = map.getEventCoordinate(event.originalEvent);

              infoContent.innerHTML = `
                <div class="popup-line-div"><div>Tienumero:&nbsp;</div><div class="selectable">${roadData.roadNumber}</div></div>
                <div class="popup-line-div"><div>Tieosanumero:&nbsp;</div><div class="selectable">${roadData.roadPartNumber}</div></div>
                <div class="popup-line-div"><div>Ajorata:&nbsp;</div><div class="selectable">${roadData.trackCode}</div></div>
                <div class="popup-line-div"><div>AET:&nbsp;</div><div class="selectable">${roadData.addrMRange.start}</div></div>
                <div class="popup-line-div"><div>LET:&nbsp;</div><div class="selectable">${roadData.addrMRange.end}</div></div>
                <div class="popup-line-div"><div>Hall. luokka:&nbsp;</div><div class="selectable">${displayAdministrativeClass(roadData.administrativeClassId)}</div></div>
              `;

              const altShiftPressed = event.originalEvent.shiftKey && event.originalEvent.altKey;
              if (altShiftPressed) {
                infoContent.innerHTML += `<hr>`;

                if (!_.isUndefined(roadData.municipalityCode)) {
                  infoContent.innerHTML += `
                    <div class="popup-line-div"><div>MunicipalityCode:&nbsp;</div><div class="selectable">${roadData.municipalityCode}</div></div>
                  `;
                }

                infoContent.innerHTML += `
                  <div class="popup-line-div"><div>Ely:&nbsp;</div><div class="selectable">${roadData.elyCode}</div></div>
                  <div class="popup-line-div"><div>Link&nbsp;id:&nbsp;</div><div class="selectable">${roadData.linkId}</div></div>
                  <div class="popup-line-div"><div>LinearLocation&nbsp;id:&nbsp;</div><div class="selectable">${roadData.linearLocationId}</div></div>
                  <div class="popup-line-div"><div>Roadway&nbsp;id:&nbsp;</div><div class="selectable">${roadData.roadwayId}</div></div>
                  <div class="popup-line-div"><div>RoadwayNumber:&nbsp;</div><div class="selectable">${roadData.roadwayNumber}</div></div>
                `;
              }
            }
          }
        }
      }

      // Keep info box open with altkey
      if (!event.originalEvent.altKey) {
        overlay.setPosition(coordinate);
      }
    };

    var displayAdministrativeClass = function (administrativeClassCode) {
      var administrativeClass;
      switch (administrativeClassCode) {
        case ViiteEnumerations.AdministrativeClassShort.PublicRoad.value:
          administrativeClass = ViiteEnumerations.AdministrativeClassShort.PublicRoad.description;
          break;
        case ViiteEnumerations.AdministrativeClassShort.MunicipalityStreetRoad.value:
          administrativeClass = ViiteEnumerations.AdministrativeClassShort.MunicipalityStreetRoad.description;
          break;
        case ViiteEnumerations.AdministrativeClassShort.PrivateRoad.value:
          administrativeClass = ViiteEnumerations.AdministrativeClassShort.PrivateRoad.description;
          break;
        default:
          break;
      }
      return administrativeClass;
    };

    //Listen pointerMove and get pixel for displaying roadAddress feature info
    me.eventListener.listenTo(eventbus, 'overlay:update', function (event, pixel) {
      displayRoadAddressInfo(event, pixel);
    });

    var handleRoadsVisibility = function () {
      roadLayer.setVisible(applicationModel.getRoadVisibility() && zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForRoadLinks);
    };

    this.refreshMap = function (mapState) {
      if (mapState.zoom < zoomlevels.minZoomForRoadLinks) {
        roadLayer.getSource().clear();
        eventbus.trigger('map:clearLayers');
        applicationModel.removeSpinner();
      } else {
        /*
         This could be implemented also with eventbus.trigger(applicationModel.getSelectedLayer() + ':fetch');
         but this implementation makes it easier to find the eventbus call when needed.
        */
        switch (applicationModel.getSelectedLayer()) {
          case 'linkProperty':
            eventbus.trigger('linkProperty:fetch');
            break;
          case 'roadAddressProject':
            eventbus.trigger('roadAddressProject:fetch');
            break;
          case 'node':
            // Don't fetch nodes if one is currently being moved (translated)
            if (window.ViiteState && window.ViiteState.isTranslatingNode) break;
            eventbus.trigger('nodeLayer:fetch');
            break;
          default:
            break;
        }
        handleRoadsVisibility();
      }
    };

    this.eventListener.listenTo(eventbus, 'map:refresh', me.refreshMap, this);

    var clear = function () {
      roadLayer.getSource().clear();
    };

    return {
      layer: roadLayer,
      clear: clear
    };
  };
}(this));
