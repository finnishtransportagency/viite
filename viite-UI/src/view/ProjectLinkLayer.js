(function (root) {
  root.ProjectLinkLayer = function (map, projectCollection, selectedProjectLinkProperty, roadLayer) {
    var layerName = 'roadAddressProject';
    var vectorLayer;
    var calibrationPointVector = new ol.source.Vector({});
    var directionMarkerVector = new ol.source.Vector({});
    var suravageProjectDirectionMarkerVector = new ol.source.Vector({});
    var suravageRoadVector = new ol.source.Vector({});
    var cachedMarker = null;
    var layerMinContentZoomLevels = {};
    var currentZoom = 0;
    var LinkStatus = LinkValues.LinkStatus;
    var Anomaly = LinkValues.Anomaly;
    var SideCode = LinkValues.SideCode;
    var RoadLinkType = LinkValues.RoadLinkType;
    var LinkGeomSource = LinkValues.LinkGeomSource;
    var RoadClass = LinkValues.RoadClass;
    var RoadZIndex = LinkValues.RoadZIndex;
    var isNotEditingData = true;
    Layer.call(this, layerName, roadLayer);
    var me = this;
    var styler = new RoadLinkStyler(true);

    var projectLinkStyler = new ProjectLinkStyler();

    var vectorSource = new ol.source.Vector({
      loader: function () {
        var nonSuravageRoads = _.partition(projectCollection.getAll(), function (projectRoad) {
          return projectRoad.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value;
        })[1];
        var features = _.map(nonSuravageRoads, function (projectLink) {
          var points = _.map(projectLink.points, function (point) {
            return [point.x, point.y];
          });
          var feature = new ol.Feature({
            geometry: new ol.geom.LineString(points)
          });
          feature.linkData = projectLink;
          feature.linkId = projectLink.linkId;
          return feature;
        });
        loadFeatures(features);
      },
      strategy: ol.loadingstrategy.bbox
    });

    var calibrationPointLayer = new ol.layer.Vector({
      source: calibrationPointVector,
      name: 'calibrationPointLayer',
      zIndex: RoadZIndex.CalibrationPointLayer.value
    });

    var directionMarkerLayer = new ol.layer.Vector({
      source: directionMarkerVector,
      name: 'directionMarkerLayer',
      zIndex: RoadZIndex.DirectionMarkerLayer.value
    });

    var suravageRoadProjectLayer = new ol.layer.Vector({
      source: suravageRoadVector,
      name: 'suravageRoadProjectLayer',
      style: function (feature) {
        return projectLinkStyler.getProjectLinkStyle().getStyle( feature.linkData, {zoomLevel: currentZoom});
      },
      zIndex: RoadZIndex.SuravageLayer.value
    });

    var suravageProjectDirectionMarkerLayer = new ol.layer.Vector({
      source: suravageProjectDirectionMarkerVector,
      name: 'suravageProjectDirectionMarkerLayer',
      zIndex: RoadZIndex.DirectionMarkerLayer.value
    });

    vectorLayer = new ol.layer.Vector({
      source: vectorSource,
      name: layerName,
      style: function(feature) {
        var status = feature.linkData.status;
        if (status === LinkStatus.NotHandled.value || status === LinkStatus.Terminated.value || status  === LinkStatus.New.value || status === LinkStatus.Transfer.value || status === LinkStatus.Unchanged.value || status === LinkStatus.Numbering.value) {
          return projectLinkStyler.getProjectLinkStyle().getStyle( feature.linkData, {zoomLevel: currentZoom});
        } else {
          return styler.getRoadLinkStyle().getStyle(feature.linkData, currentZoom);
        }
      },
      zIndex: RoadZIndex.VectorLayer.value
    });

    var getSelectedId = function (selected) {
      if (!_.isUndefined(selected.id) && selected.id > 0) {
        return selected.id;
      } else {
        return selected.linkId;
      }
    };

    var showChangesAndSendButton = function () {
      selectedProjectLinkProperty.clean();
      $('.wrapper').remove();
      $('#actionButtons').html('<button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button><button disabled id ="send-button" class="send btn btn-block btn-send">Lähetä muutosilmoitus Tierekisteriin</button>');
    };

    var fireDeselectionConfirmation = function (ctrlPressed, selection, clickType) {
      new GenericConfirmPopup('Haluatko poistaa tien valinnan ja hylätä muutokset?', {
        successCallback: function () {
          eventbus.trigger('roadAddressProject:discardChanges');
          isNotEditingData = true;
          clearHighlights();
          if (!_.isUndefined(selection)) {
            if (clickType === 'single')
              showSingleClickChanges(ctrlPressed, selection);
            else
              showDoubleClickChanges(ctrlPressed, selection);
          } else {
            showChangesAndSendButton();
          }
        },
        closeCallback: function () {
          isNotEditingData = false;
        }
      });
    };

    var possibleStatusForSelection = [LinkStatus.NotHandled.value, LinkStatus.New.value, LinkStatus.Terminated.value, LinkStatus.Transfer.value, LinkStatus.Unchanged.value, LinkStatus.Numbering.value];

    var selectSingleClick = new ol.interaction.Select({
      layer: [vectorLayer, suravageRoadProjectLayer],
      condition: ol.events.condition.singleClick,
      style: function (feature) {
        if(!_.isUndefined(feature.linkData))
          if(projectLinkStatusIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value || feature.linkData.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value) {
           return projectLinkStyler.getSelectionLinkStyle().getStyle( feature.linkData, {zoomLevel: currentZoom});
          }
      }
    });

    selectSingleClick.set('name', 'selectSingleClickInteractionPLL');

    selectSingleClick.on('select', function (event) {
      var ctrlPressed = event.mapBrowserEvent !== undefined ?
          event.mapBrowserEvent.originalEvent.ctrlKey : false;
      removeCutterMarkers();
      var selection = _.find(event.selected.concat(selectSingleClick.getFeatures().getArray()), function (selectionTarget) {
        return (applicationModel.getSelectedTool() !== 'Cut' && !_.isUndefined(selectionTarget.linkData) && (
          projectLinkStatusIn(selectionTarget.linkData, possibleStatusForSelection) ||
          (selectionTarget.linkData.anomaly === Anomaly.NoAddressGiven.value && selectionTarget.linkData.roadLinkType !== RoadLinkType.FloatingRoadLinkType.value) ||
          selectionTarget.linkData.roadClass === RoadClass.NoClass.value || selectionTarget.linkData.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value )
        );
      });
      if (isNotEditingData) {
        showSingleClickChanges(ctrlPressed, selection);
      } else {
        var selectedFeatures = event.deselected.concat(selectDoubleClick.getFeatures().getArray());
        clearHighlights();
        addFeaturesToSelection(selectedFeatures);
        fireDeselectionConfirmation(ctrlPressed, selection, 'single');
      }
    });

    var showSingleClickChanges = function (ctrlPressed, selection) {
      if(applicationModel.getSelectedTool() === 'Cut')
        return;
      if (ctrlPressed && !_.isUndefined(selectedProjectLinkProperty.get())) {
        if (!_.isUndefined(selection) && canItBeAddToSelection(selection.linkData)) {
          var clickedIds = projectCollection.getMultiProjectLinks(getSelectedId(selection.linkData));
          var previouslySelectedIds = _.map(selectedProjectLinkProperty.get(), function (selected) {
            return selected.linkId;
          });
          if (_.contains(previouslySelectedIds, getSelectedId(selection.linkData))) {
            previouslySelectedIds = _.without(previouslySelectedIds, clickedIds);
          } else {
            previouslySelectedIds = _.union(previouslySelectedIds, clickedIds);
          }
          selectedProjectLinkProperty.openShift(previouslySelectedIds);
        }
        highlightFeatures();
      } else {
        selectedProjectLinkProperty.clean();
        projectCollection.setTmpDirty([]);
        projectCollection.setDirty([]);
        eventbus.trigger('projectLink:mapClicked');
        $('[id^=editProject]').css('visibility', 'visible');
        $('#closeProjectSpan').css('visibility', 'visible');
        if (!_.isUndefined(selection) && !selectedProjectLinkProperty.isDirty()){
          if(!_.isUndefined(selection.linkData.connectedLinkId)){
            selectedProjectLinkProperty.openSplit(selection.linkData.linkId, true);
          } else {
            selectedProjectLinkProperty.open(getSelectedId(selection.linkData), true);
          }
        }
        else selectedProjectLinkProperty.cleanIds();
      }
    };

    var selectDoubleClick = new ol.interaction.Select({
      layer: [vectorLayer, suravageRoadProjectLayer],
      condition: ol.events.condition.doubleClick,
      style: function(feature) {
        if(projectLinkStatusIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value || feature.linkData.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value) {
          return projectLinkStyler.getSelectionLinkStyle().getStyle( feature.linkData, {zoomLevel: currentZoom});
        }
      }
    });

    selectDoubleClick.set('name', 'selectDoubleClickInteractionPLL');

    selectDoubleClick.on('select', function (event) {
      var ctrlPressed = event.mapBrowserEvent.originalEvent.ctrlKey;
      var selection = _.find(event.selected, function (selectionTarget) {
        return (applicationModel.getSelectedTool() !== 'Cut' && !_.isUndefined(selectionTarget.linkData) && (
          projectLinkStatusIn(selectionTarget.linkData, possibleStatusForSelection) ||
          (selectionTarget.linkData.anomaly === Anomaly.NoAddressGiven.value && selectionTarget.linkData.roadLinkType !== RoadLinkType.FloatingRoadLinkType.value) ||
          selectionTarget.linkData.roadClass === RoadClass.NoClass.value || selectionTarget.linkData.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value || (selectionTarget.getProperties().type && selectionTarget.getProperties().type === "marker"))
        );
      });
      if (isNotEditingData) {
        showDoubleClickChanges(ctrlPressed, selection);
      } else {
        var selectedFeatures = event.deselected.concat(selectSingleClick.getFeatures().getArray());
        clearHighlights();
        addFeaturesToSelection(selectedFeatures);
        fireDeselectionConfirmation(ctrlPressed, selection, 'double');
      }
    });

    var showDoubleClickChanges = function (shiftPressed, selection) {
      if (shiftPressed && !_.isUndefined(selectedProjectLinkProperty.get())) {
        if (!_.isUndefined(selection) && canItBeAddToSelection(selection.linkData)) {
          var selectedLinkIds = _.map(selectedProjectLinkProperty.get(), function (selected) {
            return getSelectedId(selected);
          });
          if (_.contains(selectedLinkIds, getSelectedId(selection.linkData))) {
            selectedLinkIds = _.without(selectedLinkIds, getSelectedId(selection.linkData));
          } else {
            selectedLinkIds = selectedLinkIds.concat(getSelectedId(selection.linkData));
          }
          selectedProjectLinkProperty.openShift(selectedLinkIds);
        }
        highlightFeatures();
      } else {
        selectedProjectLinkProperty.clean();
        projectCollection.setTmpDirty([]);
        projectCollection.setDirty([]);
        if (!_.isUndefined(selection) && !selectedProjectLinkProperty.isDirty()){
          if(!_.isUndefined(selection.linkData.connectedLinkId)){
            selectedProjectLinkProperty.openSplit(selection.linkData.linkId, true);
          } else {
            selectedProjectLinkProperty.open(getSelectedId(selection.linkData));
          }
        }
        else selectedProjectLinkProperty.cleanIds();
      }
    };

    var drawIndicators = function (links) {
      var features = [];

      var markerContainer = function (link, position) {
        var imageSettings = {src: 'images/center-marker2.svg'};
        var textSettings = {
          text: link.marker,
          fill: new ol.style.Fill({
            color: '#ffffff'
          }),
          font: '12px sans-serif'
        };
        var style = new ol.style.Style({
          image: new ol.style.Icon(imageSettings),
          text: new ol.style.Text(textSettings),
          zIndex: 11
        });
        var marker = new ol.Feature({
          geometry: new ol.geom.Point([position.x, position.y]),
          type: 'cutter'
        });
        marker.setStyle(style);
        features.push(marker);
      };

      var indicatorsForSplit = function () {
        return _.map(_.filter(links, function (fl) {
          return !_.isUndefined(fl.middlePoint);
        }), function (link) {
          markerContainer(link, link.middlePoint);
        });
      };

      var indicators = function () {
        return indicatorsForSplit();
      };
      indicators();
      addFeaturesToSelection(features);
    };

    var canItBeAddToSelection = function(selectionData) {
      if (selectedProjectLinkProperty.get().length === 0) {
        return true;
      }
      var currentlySelectedSample = _.first(selectedProjectLinkProperty.get());
      return selectionData.roadNumber === currentlySelectedSample.roadNumber &&
          selectionData.roadPartNumber === currentlySelectedSample.roadPartNumber &&
          selectionData.trackCode === currentlySelectedSample.trackCode &&
          selectionData.roadType === currentlySelectedSample.roadType;
    };
    
    var clearHighlights = function () {
      if (applicationModel.getSelectedTool() == 'Cut') {
        if (selectDoubleClick.getFeatures().getLength() !== 0) {
          selectDoubleClick.getFeatures().clear();
        }
        if (selectSingleClick.getFeatures().getLength() !== 0) {
          selectSingleClick.getFeatures().clear();
        }
      } else {
        if (selectDoubleClick.getFeatures().getLength() !== 0) {
          selectDoubleClick.getFeatures().clear();
        }
        if (selectSingleClick.getFeatures().getLength() !== 0) {
          selectSingleClick.getFeatures().clear();
        }
      }
    };

    var clearLayers = function () {
      calibrationPointLayer.getSource().clear();
      directionMarkerLayer.getSource().clear();
      suravageProjectDirectionMarkerLayer.getSource().clear();
      suravageRoadProjectLayer.getSource().clear();
    };
    clearLayers();
    vectorLayer.getSource().clear();

    var highlightFeatures = function () {
      clearHighlights();
      var featuresToHighlight = [];
      var suravageFeaturesToHighlight = [];
      _.each(vectorLayer.getSource().getFeatures(), function (feature) {
        var canIHighlight = ((!_.isUndefined(feature.linkData.linkId) && _.isUndefined(feature.linkData.connectedLinkId)) ||
        (!_.isUndefined(feature.linkData.connectedLinkId) && feature.linkData.status === LinkStatus.Terminated.value) ?
          selectedProjectLinkProperty.isSelected(getSelectedId(feature.linkData)) : false);
        if (canIHighlight) {
          featuresToHighlight.push(feature);
        }
      });
      addFeaturesToSelection(featuresToHighlight);
      _.each(suravageRoadProjectLayer.getSource().getFeatures(), function (feature) {
        var canIHighlight = (!_.isUndefined(feature.linkData) && !_.isUndefined(feature.linkData.linkId)) ?
            selectedProjectLinkProperty.isSelected(getSelectedId(feature.linkData)) : false;
        
        if (canIHighlight) {
          suravageFeaturesToHighlight.push(feature);
        }
      });
      if (suravageFeaturesToHighlight.length !== 0) {
        addFeaturesToSelection(suravageFeaturesToHighlight);
      }

      var suravageResult = _.filter(suravageProjectDirectionMarkerLayer.getSource().getFeatures(), function (item) {
        return _.find(suravageFeaturesToHighlight, function (sf) {
          return sf.linkData.linkId === item.linkData.linkId;
        });
      });

      _.each(suravageResult, function (featureMarker) {
        selectSingleClick.getFeatures().push(featureMarker);
      });

      var result = _.filter(directionMarkerLayer.getSource().getFeatures(), function (item) {
        return _.find(featuresToHighlight, {linkId: item.id});
      });

      _.each(result, function (featureMarker) {
        selectSingleClick.getFeatures().push(featureMarker);
      });
    };

    /**
     * Simple method that will add various open layers 3 features to a selection.
     * @param ol3Features
     */
    var addFeaturesToSelection = function (ol3Features) {
      var olUids = _.map(selectSingleClick.getFeatures().getArray(), function (feature) {
        return feature.ol_uid;
      });
      _.each(ol3Features, function (feature) {
        if (!_.contains(olUids, feature.ol_uid)) {
          selectSingleClick.getFeatures().push(feature);
          olUids.push(feature.ol_uid); // prevent adding duplicate entries
        }
      });
    };

    var addCutLine = function (cutGeom) {
      var points = _.map(cutGeom.geometry, function (point) {
        return [point.x, point.y];
      });
      var cutFeature = new ol.Feature({
        geometry: new ol.geom.LineString(points),
        type: 'cut-line'
      });
      var style = new ol.style.Style({
        stroke: new ol.style.Stroke({color: [20, 20, 255, 1], width: 9}),
        zIndex: 11
      });
      cutFeature.setStyle(style);
      removeFeaturesByType('cut-line');
      addFeaturesToSelection([cutFeature]);
    };

    var addTerminatedFeature = function (terminatedLink) {
      var points = _.map(terminatedLink.geometry, function (point) {
        return [point.x, point.y];
      });
      var terminatedFeature = new ol.Feature({
        projectLinkData: terminatedLink,
        geometry: new ol.geom.LineString(points),
        type: 'pre-split'
      });
      var style = new ol.style.Style({
        stroke: new ol.style.Stroke({color: '#c6c00f', width: 13, lineCap: 'round'}),
        zIndex: 11
      });
      terminatedFeature.setStyle(style);
      removeFeaturesByType('pre-split');
      addFeaturesToSelection([terminatedFeature]);
    };

    var removeFeaturesByType = function (match) {
      _.each(selectSingleClick.getFeatures().getArray().concat(selectDoubleClick.getFeatures().getArray()), function(feature){
        if(feature && feature.getProperties().type === match) {
          selectSingleClick.getFeatures().remove(feature);
        }
      });
    };

    eventbus.on('projectLink:clicked projectLink:split projectLink:errorClicked', function () {
      highlightFeatures();
    });

    eventbus.on('layer:selected', function (layer) {
      if (layer === 'roadAddressProject') {
        vectorLayer.setVisible(true);
        calibrationPointLayer.setVisible(true);
      } else {
        clearHighlights();
        vectorLayer.setVisible(false);
        calibrationPointLayer.setVisible(false);
        eventbus.trigger('roadLinks:fetched');
      }
    });

    var zoomDoubleClickListener = function (event) {
      _.defer(function () {
        if (applicationModel.getSelectedTool() !== 'Cut' && !event.originalEvent.ctrlKey && selectedProjectLinkProperty.get().length === 0 &&
          applicationModel.getSelectedLayer() === 'roadAddressProject' && map.getView().getZoom() <= 13) {
          map.getView().setZoom(map.getView().getZoom() + 1);
        }
      });
    };
    //This will control the double click zoom when there is no selection that activates
    map.on('dblclick', zoomDoubleClickListener);

    var infoContainer = document.getElementById('popup');
    var infoContent = document.getElementById('popup-content');

    var overlay = new ol.Overlay(({
      element: infoContainer
    }));

    map.addOverlay(overlay);

    //Listen pointerMove and get pixel for displaying roadAddress feature info
    eventbus.on('map:mouseMoved', function (event, pixel) {
      if (event.dragging) {
        return;
      }
      if (applicationModel.getSelectedTool() === 'Cut' && suravageCutter) {
        suravageCutter.updateByPosition(event.coordinate);
      } else {
        displayRoadAddressInfo(event, pixel);
      }
    });

    var displayRoadAddressInfo = function (event, pixel) {

      var featureAtPixel = map.forEachFeatureAtPixel(pixel, function (feature) {
        return feature;
      });

      //Ignore if target feature is marker
      if (isDefined(featureAtPixel) && (isDefined(featureAtPixel.linkData) || isDefined(featureAtPixel.linkData))) {
        var roadData;
        var coordinate = map.getEventCoordinate(event.originalEvent);

        if (isDefined(featureAtPixel.linkData)) {
          roadData = featureAtPixel.linkData;
        }
        else {
          roadData = featureAtPixel.linkData;
        }
        //TODO roadData !== null is there for test having no info ready (race condition where hover often loses) should be somehow resolved
        if (infoContent !== null) {
          if (roadData !== null || (roadData.roadNumber !== 0 && roadData.roadPartNumber !== 0 && roadData.roadPartNumber !== 99)) {
            infoContent.innerHTML = '<p>' +
                'Tienumero: ' + roadData.roadNumber + '<br>' +
                'Tieosanumero: ' + roadData.roadPartNumber + '<br>' +
                'Ajorata: ' + roadData.trackCode + '<br>' +
                'AET: ' + roadData.startAddressM + '<br>' +
                'LET: ' + roadData.endAddressM + '<br>' + '</p>';
          } else {
            infoContent.innerHTML = '<p>' +
                'Tuntematon tien segmentti' + '</p>';
          }
        }

        overlay.setPosition(coordinate);

      } else {
        overlay.setPosition(undefined);
      }
    };

    var isDefined = function (variable) {
      return !_.isUndefined(variable);
    };

    //Add defined interactions to the map.
    map.addInteraction(selectSingleClick);
    map.addInteraction(selectDoubleClick);

    var mapMovedHandler = function (mapState) {
      if(applicationModel.getSelectedTool() === 'Cut' && selectSingleClick.getFeatures().getArray().length > 0)
          return;
      var projectId = _.isUndefined(projectCollection.getCurrentProject()) ? undefined : projectCollection.getCurrentProject().project.id;
      if (mapState.zoom !== currentZoom) {
        currentZoom = mapState.zoom;
      }
      if (mapState.zoom < minimumContentZoomLevel()) {
        vectorSource.clear();
        eventbus.trigger('map:clearLayers');
      } else if (mapState.selectedLayer === layerName) {
        projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), currentZoom + 1, projectId, projectCollection.getPublishableStatus());
        handleRoadsVisibility();
      }
    };

    /**
     * This will add all the following interactions from the map:
     * -selectDoubleClick
     * -selectSingleClick
     */

    var addSelectInteractions = function () {
      map.addInteraction(selectDoubleClick);
      map.addInteraction(selectSingleClick);
    };

    /**
     * This will remove all the following interactions from the map:
     * -selectDoubleClick
     * -selectSingleClick
     */

    var removeSelectInteractions = function () {
      map.removeInteraction(selectDoubleClick);
      map.removeInteraction(selectSingleClick);
    };

    /**
     * This will deactivate the following interactions from the map:
     * -selectDoubleClick
     * -selectSingleClick - only if demanded with the Both
     */

    var deactivateSelectInteractions = function (both) {
      selectDoubleClick.setActive(false);
      if (both) {
        selectSingleClick.setActive(false);
      }
    };

    /**
     * This will activate the following interactions from the map:
     * -selectDoubleClick
     * -selectSingleClick - only if demanded with the Both
     */

    var activateSelectInteractions = function (both) {
      selectDoubleClick.setActive(true);
      if (both) {
        selectSingleClick.setActive(true);
      }
    };

    var handleRoadsVisibility = function () {
      if (_.isObject(vectorLayer)) {
        vectorLayer.setVisible(map.getView().getZoom() >= minimumContentZoomLevel());
      }
    };

    var minimumContentZoomLevel = function () {
      if (!_.isUndefined(layerMinContentZoomLevels[applicationModel.getSelectedLayer()])) {
        return layerMinContentZoomLevels[applicationModel.getSelectedLayer()];
      }
      return zoomlevels.minZoomForRoadLinks;
    };

    var loadFeatures = function (features) {
      vectorSource.addFeatures(features);
    };

    var show = function() {
      vectorLayer.setVisible(true);
    };

    var hideLayer = function () {
      me.stop();
      me.hide();
    };

    var clearProjectLinkLayer = function () {
      vectorLayer.getSource().clear();
    };

    var removeCutterMarkers = function () {
      var featuresToRemove = [];
      _.each(selectSingleClick.getFeatures().getArray(), function(feature){
        if(feature.getProperties().type === 'cutter')
          featuresToRemove.push(feature);
      });
      _.each(featuresToRemove, function (ft) {
        selectSingleClick.getFeatures().remove(ft);
      });
    };

    var SuravageCutter = function (suravageLayer, collection, eventListener) {
      var scissorFeatures = [];
      var CUT_THRESHOLD = 20;
      var self = this;

      var moveTo = function (x, y) {
        scissorFeatures = [new ol.Feature({
          geometry: new ol.geom.Point([x, y]),
          type: 'cutter-crosshair'
        })];
        scissorFeatures[0].setStyle(
            new ol.style.Style({
              image: new ol.style.Icon({
                src: 'images/cursor-crosshair.svg'
              })
            })
        );
        removeFeaturesByType('cutter-crosshair');
        addFeaturesToSelection(scissorFeatures);
      };

      var removeFeaturesByType = function (match) {
        _.each(selectSingleClick.getFeatures().getArray(), function(feature){
          if(feature && feature.getProperties().type === match) {
            selectSingleClick.getFeatures().remove(feature);
          }
        });
      };

      this.addCutLine = function (cutGeom) {
        var points = _.map(cutGeom.geometry, function (point) {
          return [point.x, point.y];
        });
        var cutFeature = new ol.Feature({
          geometry: new ol.geom.LineString(points),
          type: 'cut-line'
        });
        var style = new ol.style.Style({
          stroke: new ol.style.Stroke({color: [20, 20, 255, 1], width: 9}),
          zIndex: 11
        });
        cutFeature.setStyle(style);
        removeFeaturesByType('cut-line');
        addFeaturesToSelection([cutFeature]);
      };

      this.addTerminatedFeature = function (terminatedLink) {
        var points = _.map(terminatedLink.geometry, function (point) {
          return [point.x, point.y];
        });
        var terminatedFeature = new ol.Feature({
          linkData: terminatedLink,
          geometry: new ol.geom.LineString(points),
          type: 'pre-split'
        });
        var style = new ol.style.Style({
          stroke: new ol.style.Stroke({color: '#c6c00f', width: 13, lineCap: 'round'}),
          zIndex: 11
        });
        terminatedFeature.setStyle(style);
        removeFeaturesByType('pre-split');
        addFeaturesToSelection([terminatedFeature]);
      };

      var clickHandler = function (evt) {
        if (applicationModel.getSelectedTool() === 'Cut') {
          $('.wrapper').remove();
          removeCutterMarkers();
          self.cut(evt);
        }
      };

      this.deactivate = function () {
        eventListener.stopListening(eventbus, 'map:clicked', clickHandler);
        selectedProjectLinkProperty.setDirty(false);
      };

      this.activate = function () {
        eventListener.listenTo(eventbus, 'map:clicked map:dblclicked', clickHandler);
      };

      var isWithinCutThreshold = function (suravageLink) {
        return suravageLink !== undefined && suravageLink < CUT_THRESHOLD;
      };

      var findNearestSuravageLink = function (point) {

        var possibleSplit = _.filter(vectorSource.getFeatures().concat(suravageRoadProjectLayer.getSource().getFeatures()), function(feature){
          return !_.isUndefined(feature.linkData) && (feature.linkData.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value);
        });
        return _.chain(possibleSplit)
            .map(function (feature) {
              var closestP = feature.getGeometry().getClosestPoint(point);
              var distanceBetweenPoints = GeometryUtils.distanceOfPoints(point, closestP);
              return {
                feature: feature,
                point: closestP,
                distance: distanceBetweenPoints
              };
            })
            .sortBy(function (nearest) {
              return nearest.distance;
            })
            .head()
            .value();
      };

      this.updateByPosition = function (mousePoint) {
        var closestSuravageLink = findNearestSuravageLink(mousePoint);
        if (!closestSuravageLink) {
          return;
        }
        if (isWithinCutThreshold(closestSuravageLink.distance)) {
          moveTo(closestSuravageLink.point[0], closestSuravageLink.point[1]);
        } else {
          removeFeaturesByType('cutter-crosshair');
        }
      };

      this.cut = function (mousePoint) {
        var pointsToLineString = function (points) {
          var coordPoints = _.map(points, function (point) {
            return [point.x, point.y];
          });
          return new ol.geom.LineString(coordPoints);
        };

        var nearest = findNearestSuravageLink([mousePoint.x, mousePoint.y]);

        if (!nearest || !isWithinCutThreshold(nearest.distance)) {
          showChangesAndSendButton();
          selectSingleClick.getFeatures().clear();
          return;
        }
        var nearestSuravage = nearest.feature.linkData;
        nearestSuravage.points = _.isUndefined(nearestSuravage.originalGeometry) ? nearestSuravage.points : nearestSuravage.originalGeometry;
        if (!_.isUndefined(nearestSuravage.connectedLinkId)) {
          nearest.feature.geometry = pointsToLineString(nearestSuravage.originalGeometry);
        }
        selectedProjectLinkProperty.setNearestPoint({x: nearest.point[0], y: nearest.point[1]});
        selectedProjectLinkProperty.preSplitSuravageLink(nearestSuravage);
        projectCollection.setTmpDirty([nearest.feature.linkData]);
      };
    };

    var projectLinkStatusIn = function (projectLink, possibleStatus) {
      if (!_.isUndefined(possibleStatus) && !_.isUndefined(projectLink))
        return _.contains(possibleStatus, projectLink.status);
      else return false;
    };

    var suravageCutter = new SuravageCutter(suravageRoadProjectLayer, projectCollection, me.eventListener);

    var changeTool = function (tool) {
      if (tool === 'Cut') {
        suravageCutter.activate();
        selectSingleClick.setActive(false);
      } else if (tool === 'Select') {
        suravageCutter.deactivate();
        selectSingleClick.setActive(true);
      }
    };

    eventbus.on('split:projectLinks', function (split) {
      _.defer(function () {
        drawIndicators(_.filter(split, function (link) {
          return !_.isUndefined(link.marker);
        }));
      });
      eventbus.trigger('projectLink:split', split);
    });

    eventbus.on('projectLink:projectLinksCreateSuccess', function () {
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), currentZoom + 1, undefined, projectCollection.getPublishableStatus());
    });

    eventbus.on('changeProjectDirection:clicked', function () {
      vectorLayer.getSource().clear();
      directionMarkerLayer.getSource().clear();
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), currentZoom + 1, undefined, projectCollection.getPublishableStatus());
      eventbus.once('roadAddressProject:fetched', function () {
        if (selectedProjectLinkProperty.isSplited()) {
          selectedProjectLinkProperty.openSplit(selectedProjectLinkProperty.get()[0].linkId, true);
        } else if (selectedProjectLinkProperty.isMultiLink())
          selectedProjectLinkProperty.open(getSelectedId(selectedProjectLinkProperty.get()[0]), true);
        else
          selectedProjectLinkProperty.open(getSelectedId(selectedProjectLinkProperty.get()[0]), false);
      });
    });

    eventbus.on('split:splitedCutLine', function (cutGeom) {
      addCutLine(cutGeom);
      applicationModel.removeSpinner();
    });

    eventbus.on('projectLink:revertedChanges', function () {
      isNotEditingData = true;
      selectedProjectLinkProperty.setDirty(false);
      eventbus.trigger('roadAddress:projectLinksUpdated');
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), currentZoom + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.redraw = function () {
      var ids = {};
      _.each(selectedProjectLinkProperty.get(), function (sel) {
        ids[sel.linkId] = true;
      });

      var editedLinks = _.map(projectCollection.getDirty(), function (editedLink) {
        return editedLink;
      });

      var separated = _.partition(projectCollection.getAll(), function (projectRoad) {
        return projectRoad.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value;
      });
      calibrationPointLayer.getSource().clear();

      var toBeTerminated = _.partition(editedLinks, function (link) {
        return link.status === LinkStatus.Terminated.value;
      });

      var toBeTerminatedLinkIds = _.pluck(toBeTerminated[0], 'id');
      var suravageProjectRoads = separated[0].filter(function (val) {
        return _.find(separated[1], function (link) {
          return link.linkId === val.linkId;
        }) !== 0;
      });
      var suravageFeatures = [];
      suravageProjectDirectionMarkerLayer.getSource().clear();
      suravageRoadProjectLayer.getSource().clear();

      _.map(suravageProjectRoads, function (projectLink) {
        var points = _.map(projectLink.points, function (point) {
          return [point.x, point.y];
        });
        var feature = new ol.Feature({
          geometry: new ol.geom.LineString(points)
        });
        feature.linkData = projectLink;
        suravageFeatures.push(feature);
      });

      cachedMarker = new ProjectLinkMarker(selectedProjectLinkProperty);
      var suravageDirectionRoadMarker = _.filter(suravageProjectRoads, function (projectLink) {
        return projectLink.roadLinkType !== RoadLinkType.FloatingRoadLinkType.value && projectLink.anomaly !== Anomaly.NoAddressGiven.value && projectLink.anomaly !== Anomaly.GeometryChanged.value && (projectLink.sideCode === SideCode.AgainstDigitizing.value || projectLink.sideCode === SideCode.TowardsDigitizing.value);
      });

      _.each(suravageDirectionRoadMarker, function (directionLink) {
        var marker = cachedMarker.createProjectMarker(directionLink);
        if (map.getView().getZoom() > zoomlevels.minZoomForDirectionalMarkers) {
          suravageProjectDirectionMarkerLayer.getSource().addFeature(marker);
          selectSingleClick.getFeatures().push(marker);
        }
      });

      if (map.getView().getZoom() >= zoomlevels.minZoomLevelForCalibrationPoints) {
        var actualCalibrationPoints = me.drawCalibrationMarkers(calibrationPointLayer.source, suravageProjectRoads);
        _.each(actualCalibrationPoints, function (actualPoint) {
          var calMarker = new CalibrationPoint(actualPoint);
          calibrationPointLayer.getSource().addFeature(calMarker.getMarker(true));
        });
      }

      suravageRoadProjectLayer.getSource().addFeatures(suravageFeatures);

      var projectLinks = separated[1];
      var features = [];
      _.map(projectLinks, function (projectLink) {
        var points = _.map(projectLink.points, function (point) {
          return [point.x, point.y];
        });
        var feature = new ol.Feature({
          geometry: new ol.geom.LineString(points)
        });
        feature.linkData = projectLink;
        feature.linkId = projectLink.linkId;
        features.push(feature);
      });

      cachedMarker = new ProjectLinkMarker(selectedProjectLinkProperty);
      var directionRoadMarker = _.filter(projectLinks, function (projectLink) {
        return projectLink.roadLinkType !== RoadLinkType.FloatingRoadLinkType.value && projectLink.anomaly !== Anomaly.NoAddressGiven.value && projectLink.anomaly !== Anomaly.GeometryChanged.value && (projectLink.sideCode === SideCode.AgainstDigitizing.value || projectLink.sideCode === SideCode.TowardsDigitizing.value);
      });

      var featuresToRemove = [];
      directionMarkerLayer.getSource().clear();
      _.each(selectSingleClick.getFeatures().getArray(), function (feature) {
        if (feature.getProperties().type && feature.getProperties().type === "marker")
          featuresToRemove.push(feature);
      });
      _.each(featuresToRemove, function (feature) {
        selectSingleClick.getFeatures().remove(feature);
      });
      featuresToRemove = [];
      _.each(selectDoubleClick.getFeatures().getArray(), function (feature) {
          if (feature.getProperties().type && feature.getProperties().type === "marker")
              featuresToRemove.push(feature);
      });
      _.each(featuresToRemove, function (feature) {
          selectDoubleClick.getFeatures().remove(feature);
      });
      _.each(directionRoadMarker, function (directionLink) {
        var marker = cachedMarker.createProjectMarker(directionLink);
        if (map.getView().getZoom() > zoomlevels.minZoomForDirectionalMarkers) {
          directionMarkerLayer.getSource().addFeature(marker);
          selectSingleClick.getFeatures().push(marker);
        }
      });

      if (map.getView().getZoom() >= zoomlevels.minZoomLevelForCalibrationPoints) {
        var actualPoints = me.drawCalibrationMarkers(calibrationPointLayer.source, projectLinks);
        _.each(actualPoints, function (actualPoint) {
          var calMarker = new CalibrationPoint(actualPoint);
          calibrationPointLayer.getSource().addFeature(calMarker.getMarker(true));
        });
      }

      var partitioned = _.partition(features, function (feature) {
        return (!_.isUndefined(feature.linkData.linkId) && _.contains(_.pluck(editedLinks, 'id'), feature.linkData.linkId));
      });
      features = [];
      _.each(partitioned[0], function (feature) {
        var editedLink = (!_.isUndefined(feature.linkData.linkId) && _.contains(_.pluck(editedLinks, 'id'), feature.linkData.linkId));
        if (editedLink) {
          if (_.contains(toBeTerminatedLinkIds, feature.linkData.linkId)) {
            feature.linkData.status = LinkStatus.Terminated.value;
            var termination = projectLinkStyler.getProjectLinkStyle().getStyle( feature.linkData, {zoomLevel: currentZoom});
            feature.setStyle(termination);
            features.push(feature);
          }
        }
      });

      if (features.length !== 0)
        addFeaturesToSelection(features);
      features = features.concat(partitioned[1]);
      vectorLayer.getSource().clear(true);
      vectorLayer.getSource().addFeatures(features);
      vectorLayer.changed();
    };

    eventbus.on('tool:changed', changeTool);

    eventbus.on('roadAddressProject:openProject', function (projectSelected) {
      this.project = projectSelected;
      eventbus.trigger('layer:enableButtons', false);
      eventbus.trigger('editMode:setReadOnly', false);
      eventbus.trigger('roadAddressProject:selected', projectSelected.id, layerName, applicationModel.getSelectedLayer());
    });

    eventbus.on('roadAddressProject:selected', function (projId) {
      eventbus.once('roadAddressProject:projectFetched', function (projectInfo) {
        projectCollection.fetch(map.getView().calculateExtent(map.getSize()), map.getView().getZoom(), projectInfo.id, projectInfo.publishable);
      });
      projectCollection.getNextProjectsWithLinksById(projId);
    });

    eventbus.on('roadAddressProject:fetched', function () {
      applicationModel.removeSpinner();
      me.redraw();
      _.defer(function () {
        highlightFeatures();
        if (selectedProjectLinkProperty.isSplit()) {
          drawIndicators(selectedProjectLinkProperty.get());
        }
      });
    });

    eventbus.on('roadAddress:projectLinksEdited', function () {
      me.redraw();
    });

    eventbus.on('roadAddressProject:projectLinkSaved', function (projectId, isPublishable) {
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()), map.getView().getZoom() + 1, projectId, isPublishable);
    });

    eventbus.on('map:moved', mapMovedHandler, this);

    eventbus.on('layer:selected', function (layer, previouslySelectedLayer) {
      if (layer !== 'roadAddressProject') {
        deactivateSelectInteractions(true);
        removeSelectInteractions();
      }
      else {
        activateSelectInteractions(true);
        addSelectInteractions();
      }
      if (previouslySelectedLayer === 'roadAddressProject') {
        clearProjectLinkLayer();
        clearLayers();
        hideLayer();
        removeSelectInteractions();
      }
    });

    eventbus.on('roadAddressProject:deselectFeaturesSelected', function () {
      clearHighlights();
    });

    eventbus.on('roadAddressProject:clearAndDisableInteractions', function () {
      clearHighlights();
      removeSelectInteractions();
    });

    eventbus.on('roadAddressProject:enableInteractions', function () {
      addSelectInteractions();
    });

    eventbus.on('roadAddressProject:clearOnClose', function () {
      clearHighlights();
      clearLayers();
      clearProjectLinkLayer();
    });

    eventbus.on('map:clearLayers', clearLayers);

    eventbus.on('suravageProjectRoads:toggleVisibility', function (visibility) {
      suravageRoadProjectLayer.setVisible(visibility);
      suravageProjectDirectionMarkerLayer.setVisible(visibility);
    });

    eventbus.on('allProjectRoads:toggleVisibility', function (visibility) {
      toggleProjectLayersVisibility(visibility, true);
    });

    eventbus.on('roadAddressProject:toggleEditingRoad', function (notEditingData) {
      isNotEditingData = notEditingData;
    });

    eventbus.on('roadAddressProject:deactivateAllSelections', function () {
      deactivateSelectInteractions(true);
    });

    eventbus.on('roadAddressProject:startAllInteractions', function () {
      activateSelectInteractions(true);
    });

    eventbus.on('split:cutPointFeature', function (cutGeom, terminatedLink) {
      suravageCutter.addCutLine(cutGeom);
      suravageCutter.addTerminatedFeature(terminatedLink);
    });

    eventbus.on('roadAddressProject:roadCreationFailed', function (errorMessage) {
      clearHighlights();
    });
    var toggleProjectLayersVisibility = function (visibility, withRoadLayer) {
      vectorLayer.setVisible(visibility);
      suravageRoadProjectLayer.setVisible(visibility);
      calibrationPointLayer.setVisible(visibility);
      directionMarkerLayer.setVisible(visibility);
      suravageProjectDirectionMarkerLayer.setVisible(visibility);
      if (withRoadLayer) {
        roadLayer.layer.setVisible(visibility);
      }
    };
    toggleProjectLayersVisibility(true);
    map.addLayer(vectorLayer);
    map.addLayer(suravageRoadProjectLayer);
    map.addLayer(calibrationPointLayer);
    map.addLayer(directionMarkerLayer);
    map.addLayer(suravageProjectDirectionMarkerLayer);
    return {
      show: show,
      hide: hideLayer,
      clearHighlights: clearHighlights
    };
  };

})(this);