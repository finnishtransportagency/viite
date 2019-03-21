(function(root) {
  root.LinkPropertyLayer = function(map, roadLayer, selectedLinkProperty, roadCollection, linkPropertiesModel, applicationModel) {
    Layer.call(this, map);
    var me = this;

    var indicatorVector = new ol.source.Vector({});
    var floatingMarkerVector = new ol.source.Vector({});
    var anomalousMarkerVector = new ol.source.Vector({});
    var suravageMarkerVector = new ol.source.Vector({});
    var directionMarkerVector = new ol.source.Vector({});
    var calibrationPointVector = new ol.source.Vector({});
    var greenRoadLayerVector = new ol.source.Vector({});
    var pickRoadsLayerVector = new ol.source.Vector({});
    var simulationVector = new ol.source.Vector({});
    var geometryChangedVector = new ol.source.Vector({});
    var suravageRoadLayerVector = new ol.source.Vector({});
    var reservedRoadVector = new ol.source.Vector({});
    var historicRoadsVector = new ol.source.Vector({});

    var SelectionType = LinkValues.SelectionType;
    var Anomaly = LinkValues.Anomaly;
    var LinkGeomSource = LinkValues.LinkGeomSource;
    var SideCode = LinkValues.SideCode;
    var RoadZIndex = LinkValues.RoadZIndex;

    var unknownCalibrationPointValue = -1;
    var isActiveLayer = false;
    var cachedMarker = null;

    var roadLinkStyler = new RoadLinkStyler();

    var indicatorLayer = new ol.layer.Vector({
      source: indicatorVector,
      name: 'indicatorLayer',
      zIndex: RoadZIndex.IndicatorLayer.value
    });
    indicatorLayer.set('name', 'indicatorLayer');

    var floatingMarkerLayer = new ol.layer.Vector({
      source: floatingMarkerVector,
      name: 'floatingMarkerLayer'
    });
    floatingMarkerLayer.set('name', 'floatingMarkerLayer');

    var anomalousMarkerLayer = new ol.layer.Vector({
      source: anomalousMarkerVector,
      name: 'anomalousMarkerLayer',
      zIndex: RoadZIndex.IndicatorLayer.value
    });
    anomalousMarkerLayer.set('name', 'anomalousMarkerLayer');

    var suravageMarkerLayer = new ol.layer.Vector({
      source: suravageMarkerVector,
      name: 'suravageMarkerLayer',
      zIndex: RoadZIndex.DirectionMarkerLayer.value
    });
    suravageMarkerLayer.set('name', 'suravageMarkerLayer');

    var directionMarkerLayer = new ol.layer.Vector({
      source: directionMarkerVector,
      name: 'directionMarkerLayer',
      zIndex: RoadZIndex.DirectionMarkerLayer.value
    });
    directionMarkerLayer.set('name', 'directionMarkerLayer');

    var geometryChangedLayer = new ol.layer.Vector({
      source: geometryChangedVector,
      name: 'geometryChangedLayer',
      style: function(feature) {
          return [roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
              roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)})];
      },
      zIndex: RoadZIndex.GeometryChangedLayer.value
    });
    geometryChangedLayer.set('name', 'geometryChangedLayer');

    var calibrationPointLayer = new ol.layer.Vector({
      source: calibrationPointVector,
      name: 'calibrationPointLayer',
      zIndex: RoadZIndex.CalibrationPointLayer.value
    });
    calibrationPointLayer.set('name', 'calibrationPointLayer');

    var greenRoadLayer = new ol.layer.Vector({
      source: greenRoadLayerVector,
      name: 'greenRoadLayer',
      zIndex: RoadZIndex.GreenLayer.value
    });
    greenRoadLayer.set('name', 'greenRoadLayer');

    var reservedRoadLayer = new ol.layer.Vector({
      source: reservedRoadVector,
      name: 'reservedRoadLayer',
      zIndex: RoadZIndex.ReservedRoadLayer.value
    });
    reservedRoadLayer.set('name', 'reservedRoadLayer');

    /*var greenRoads = function(Ol3Features, addToGreenLayer) {
      var features = [];

      var style = new ol.style.Style({
        fill: new ol.style.Fill({
          color: 'rgba(0, 255, 0, 0.75)'
        }),
        stroke: new ol.style.Stroke({
          color: 'rgba(0, 255, 0, 0.95)',
          width: 8
        })
      });

      var greenRoadStyle = function(feature) {
        feature.setStyle(style);
        features.push(feature);
      };

      var greenStyle = function() {
        _.each(Ol3Features, function(feature) {
          greenRoadStyle(feature);
        });
      };
      greenStyle();

      if (!addToGreenLayer) {
        greenRoadLayer.getSource().addFeatures(features);
        selectSingleClick.getFeatures().clear();
      }
    };*/

    var pickRoadsLayer = new ol.layer.Vector({
      source: pickRoadsLayerVector,
      name: 'pickRoadsLayer',
      style: function(feature) {
          return [roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
              roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)})];
      }
    });
    pickRoadsLayer.set('name', 'pickRoadsLayer');

    var simulatedRoadsLayer = new ol.layer.Vector({
      source: simulationVector,
      name: 'simulatedRoadsLayer',
      style: function(feature) {
          return [roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
              roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)})];
      }
    });
    simulatedRoadsLayer.set('name', 'simulatedRoadsLayer');


    var suravageRoadLayer = new ol.layer.Vector({
      source: suravageRoadLayerVector,
      name: 'suravageRoadLayer',
      style: function(feature) {
          return [roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
              roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)})];
      }
    });
    suravageRoadLayer.set('name', 'suravageRoadLayer');


    var historicRoadsLayer = new ol.layer.Vector({
      source: historicRoadsVector,
      name: 'historicRoadsLayer',
      style: function(feature) {
          return [roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
              roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)})];
      },
      zIndex: RoadZIndex.HistoricRoadLayer.value
    });
    historicRoadsLayer.set('name', 'historicRoadsLayer');


    var layers = [roadLayer.layer, floatingMarkerLayer, anomalousMarkerLayer, suravageMarkerLayer, directionMarkerLayer, geometryChangedLayer, calibrationPointLayer,
      indicatorLayer, greenRoadLayer, pickRoadsLayer, simulatedRoadsLayer, suravageRoadLayer, reservedRoadLayer, historicRoadsLayer];

    var setGeneralOpacity = function (opacity){
      roadLayer.layer.setOpacity(opacity);
      floatingMarkerLayer.setOpacity(opacity);
      anomalousMarkerLayer.setOpacity(opacity);
      suravageMarkerLayer.setOpacity(opacity);
      directionMarkerLayer.setOpacity(opacity);
      suravageRoadLayer.setOpacity(opacity);
      historicRoadsLayer.setOpacity(opacity);
      geometryChangedLayer.setOpacity(opacity);
    };

    /**
     * We declare the type of interaction we want the map to be able to respond.
     * A selected feature is moved to a new/temporary layer out of the default roadLayer.
     * This interaction is restricted to a double click.
     * @type {ol.interaction.Select}
     *
     * 
     */
    var selectDoubleClick = new ol.interaction.Select({
      //Multi is the one en charge of defining if we select just the feature we clicked or all the overlapping
      //multi: true,
      //This will limit the interaction to the specific layer, in this case the layer where the roadAddressLinks are drawn
      layer: [roadLayer.layer, geometryChangedLayer, suravageRoadLayer, historicRoadsLayer],
      //Limit this interaction to the doubleClick
      condition: ol.events.condition.doubleClick,
      //The new/temporary layer needs to have a style function as well, we define it here.
      style: function(feature) {
          return [roadLinkStyler.getBorderStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
            roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
              roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)})];
      }
    });


    /**
     * We now declare what kind of custom actions we want when the interaction happens.
     * Note that 'select' is triggered when a feature is either selected or deselected.
     * The event holds the selected features in the events.selected and the deselected in event.deselected.
     */
    selectDoubleClick.on('select',function(event) {
      var visibleFeatures = getVisibleFeatures(true, true, true, false, false, true, true, true);
      selectSingleClick.getFeatures().clear();

      if(applicationModel.isReadOnly()){
        selectDoubleClick.getFeatures().clear();
      }
      //Since the selected features are moved to a new/temporary layer we just need to reduce the roadlayer's opacity levels.
      if (event.selected.length !== 0) {
        if (roadLayer.layer.getOpacity() === 1) {
          setGeneralOpacity(0.2);
        }
        var selectedF =  _.find(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.linkData);
        });
        if (!_.isUndefined(selectedF)) {
          var selection = selectedF.linkData;
          if (selection.floating === SelectionType.Floating.value) {
              selectedLinkProperty.openFloating(selection, true, visibleFeatures);
              floatingMarkerLayer.setOpacity(1);
          } else {
            selectedLinkProperty.open(selection, false, visibleFeatures);
          }
        }
      }
    });
    selectDoubleClick.set('name','selectDoubleClickInteractionLPL');


    var zoomDoubleClickListener = function(event) {
      if (isActiveLayer)
        _.defer(function(){
          if (selectedLinkProperty.get().length === 0 && zoomlevels.getViewZoom(map) <= 13) {
            map.getView().setZoom(Math.trunc(map.getView().getZoom() + 1));
          }
        });
    };
    //This will control the double click zoom when there is no selection that activates
    map.on('dblclick', zoomDoubleClickListener);

    /**
     * We declare the type of interaction we want the map to be able to respond.
     * A selected feature is moved to a new/temporary layer out of the default roadLayer.
     * This interaction is restricted to a single click (there is a 250 ms enforced
     * delay between single clicks in order to differentiate from double click).
     * @type {ol.interaction.Select}
     */
    var selectSingleClick = new ol.interaction.Select({
      //Multi is the one en charge of defining if we select just the feature we clicked or all the overlapping
      multi: true,
      //This will limit the interaction to the specific layer, in this case the layer where the roadAddressLinks are drawn
      layers: [roadLayer.layer, floatingMarkerLayer, anomalousMarkerLayer, greenRoadLayer, pickRoadsLayer, geometryChangedLayer, suravageRoadLayer, historicRoadsLayer],
      //Limit this interaction to the singleClick
      condition: ol.events.condition.singleClick,
      filter: function(feature) {
        var currentSelectionType = applicationModel.getSelectionType().value;
        if(currentSelectionType === SelectionType.Floating.value){
          return feature.linkData.anomaly !== Anomaly.None.value && feature.linkData.floating === SelectionType.Floating.value;
        }
        else if (currentSelectionType === SelectionType.Unknown.value) {
          return feature.linkData.anomaly !== Anomaly.None.value && feature.linkData.roadLinkType === RoadLinkType.UnknownRoadLinkType.value;
        } else {
          return currentSelectionType === SelectionType.All.value;
        }
      },
      //The new/temporary layer needs to have a style function as well, we define it here.
      style: function(feature) {
          return [roadLinkStyler.getBorderStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
            roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)}),
              roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)})];
      }
    });
    selectSingleClick.set('name','selectSingleClickInteractionLPL');


    /**
     * We now declare what kind of custom actions we want when the interaction happens.
     * Note that 'select' is triggered when a feature is either selected or deselected.
     * The event holds the selected features in the events.selected and the deselected in event.deselected.
     *
     * In this particular case we are fetching every roadLinkAddress and anomaly marker in view and
     * sending them to the selectedLinkProperty.open for further processing.
     */
    selectSingleClick.on('select', function(event) {
      var visibleFeatures = getVisibleFeatures(true, true, true, true, true, true, true, true);
      selectDoubleClick.getFeatures().clear();
      var selectedF =  _.find(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.linkData);
      });

      if (!_.isUndefined(selectedF)) {
        var selection = selectedF.linkData;
        if (roadLayer.layer.getOpacity() === 1) {
          setGeneralOpacity(0.2);
        }
        if (selection.floating === SelectionType.Floating.value && !applicationModel.isReadOnly()) {
            selectedLinkProperty.close();
            selectedLinkProperty.openFloating(selection, true, visibleFeatures);
            floatingMarkerLayer.setOpacity(1);
            anomalousMarkerLayer.setOpacity(1);
        } else if (selection.floating !== SelectionType.Floating.value && applicationModel.selectionTypeIs(SelectionType.Floating) && !applicationModel.isReadOnly() && event.deselected.length !== 0) {
          var floatings = event.deselected;
          var nonFloatings = event.selected;
          removeFeaturesFromSelection(nonFloatings);
          addFeaturesToSelection(floatings);
        } else if (applicationModel.selectionTypeIs(SelectionType.Unknown) && !applicationModel.isReadOnly()) {
          if ((selection.anomaly === Anomaly.NoAddressGiven.value || selection.anomaly === Anomaly.GeometryChanged.value) && selection.roadLinkType !== SelectionType.Floating.value) {
            selectedLinkProperty.openUnknown(selection, visibleFeatures);
          } else {
            removeFeaturesFromSelection(event.selected);
            addFeaturesToSelection(event.deselected);
          }
        } else {
          selectedLinkProperty.close();
          setGeneralOpacity(0.2);
          selectedLinkProperty.open(selection, true, visibleFeatures);
        }
        if (applicationModel.selectionTypeIs(SelectionType.Unknown) && selection.floating !== SelectionType.Floating.value && (selection.anomaly === Anomaly.NoAddressGiven.value || selection.anomaly === Anomaly.GeometryChanged.value)) {
          greenRoadLayer.setOpacity(1);
          var anomalousFeatures = _.uniq(_.filter(selectedLinkProperty.getFeaturesToKeep(), function (ft) {
              return ft.anomaly === Anomaly.NoAddressGiven.value;
          }));
          anomalousFeatures.forEach(function (fmf) {
            editFeatureDataForGreen(fmf);
          });
        }
      } else {
        selectedLinkProperty.close();
      }
    });

    map.on('click', function(event) {
      //The addition of the check for features on point and the selection mode
      // seem to fix the problem with the clicking on the empty map after being in the defloating process would allow a deselection and enabling of the menus
      if (window.getSelection) {window.getSelection().removeAllRanges();} //removes selection from forms
      else if (document.selection) {document.selection.empty();}
      var hasFeatureOnPoint = _.isUndefined(map.forEachFeatureAtPixel(event.pixel, function(feature) {return feature;}));
      var nonSpecialSelectionType = !_.contains(applicationModel.specialSelectionTypes, applicationModel.getSelectionType().value);
      if (isActiveLayer){
        if (hasFeatureOnPoint && nonSpecialSelectionType) {
          selectedLinkProperty.close();
        }
      }
    });

    /**
     * Simple method that will add various open layers 3 features to a selection.
     * @param ol3Features
     */
    var addFeaturesToSelection = function (ol3Features) {
      var olUids = _.map(selectSingleClick.getFeatures().getArray(), function(feature){
        return feature.ol_uid;
      });
      _.each(ol3Features, function(feature){
        if (!_.contains(olUids, feature.ol_uid)) {
          selectSingleClick.getFeatures().push(feature);
          olUids.push(feature.ol_uid); // prevent adding duplicate entries
        }
      });
    };

    /**
     * Simple method that will remove various open layers 3 features from a selection.
     * @param ol3Features
     * @param select
     */
    var removeFeaturesFromSelection = function (ol3Features) {
      var olUids = _.map(selectSingleClick.getFeatures().getArray(), function(feature){
        return feature.ol_uid;
      });
      _.each(ol3Features, function(feature){
        if(_.contains(olUids,feature.ol_uid)){
          selectSingleClick.getFeatures().remove(feature);
          olUids.push(feature.ol_uid);
        }
      });

    };

    /**
     * Event triggered by the selectedLinkProperty.open() returning all the open layers 3 features
     * that need to be included in the selection.
     */
    me.eventListener.listenTo(eventbus, 'linkProperties:ol3Selected', function(ol3Features){
      clearHighlights();
      addFeaturesToSelection(ol3Features);
    });

    var getVisibleFeatures = function(withRoads, withAnomalyMarkers, withFloatingMarkers, withGreenRoads, withPickRoads, withDirectionalMarkers, withSuravageRoads, withGeometryChanged){
      var extent = map.getView().calculateExtent(map.getSize());
      var visibleRoads = withRoads ? roadLayer.layer.getSource().getFeaturesInExtent(extent) : [];
      var visibleAnomalyMarkers =  withAnomalyMarkers ? anomalousMarkerLayer.getSource().getFeaturesInExtent(extent) : [];
      var visibleFloatingMarkers =  withFloatingMarkers ? floatingMarkerLayer.getSource().getFeaturesInExtent(extent) : [];
      var visibleGreenRoadLayer = withGreenRoads ? greenRoadLayer.getSource().getFeaturesInExtent(extent) : [];
      var visibleDirectionalMarkers = withDirectionalMarkers ? directionMarkerLayer.getSource().getFeaturesInExtent(extent) : [];
      var visibleSuravageMarkers = withDirectionalMarkers ? suravageMarkerLayer.getSource().getFeaturesInExtent(extent) : [];
      var visibleSuravageRoads = withSuravageRoads ? suravageRoadLayer.getSource().getFeaturesInExtent(extent) : [];
      var visibleGeometryChanged = withGeometryChanged ? geometryChangedLayer.getSource().getFeaturesInExtent(extent) : [];
      return visibleRoads.concat(visibleAnomalyMarkers).concat(visibleFloatingMarkers).concat(visibleGreenRoadLayer).concat(visibleDirectionalMarkers).concat(visibleSuravageRoads).concat(visibleSuravageMarkers).concat(visibleGeometryChanged);
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
    var removeSelectInteractions = function() {
      map.removeInteraction(selectDoubleClick);
      map.removeInteraction(selectSingleClick);
    };

    //We add the defined interactions to the map.
    addSelectInteractions();

    var unselectRoadLink = function() {
      indicatorLayer.getSource().clear();
      greenRoadLayer.getSource().clear();
      _.map(roadLayer.layer.getSource().getFeatures(),function (feature){
        if (feature.linkData.gapTransfering) {
          feature.linkData.gapTransfering = false;
          feature.linkData.anomaly = feature.linkData.prevAnomaly;
          var unknownRoadStyle = roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)});
          feature.setStyle(unknownRoadStyle);
        }
      });
    };

    var highlightFeatures = function() {
      clearHighlights();
      var featuresToHighlight = [];
      _.each(roadLayer.layer.features, function(feature) {
        var gapTransfering = feature.linkData.gapTransfering;
        var canIHighlight = !_.isUndefined(feature.attributes.linearLocationId) ? selectedLinkProperty.isSelectedByLinkId(feature.attributes.linkId) : selectedLinkProperty.isSelectedById(feature.attributes.id);
        if(gapTransfering || canIHighlight){
          featuresToHighlight.push(feature);
        }
      });
      if(featuresToHighlight.length !== 0)
        addFeaturesToSelection(featuresToHighlight);
    };

    var drawIndicators = function(links) {
      var features = [];

      var markerContainer = function(link, position) {
        var style = new ol.style.Style({
          image : new ol.style.Icon({
            src: 'images/center-marker2.svg'
          }),
          text : new ol.style.Text({
            text : link.marker,
            fill: new ol.style.Fill({
              color: '#ffffff'
            }),
            font : '12px sans-serif'
          })
        });
        var marker = new ol.Feature({
          geometry : new ol.geom.Point([position.x, position.y])
        });
        marker.setStyle(style);
        features.push(marker);
      };

      var indicators = function() {
        return me.mapOverLinkMiddlePoints(links, function(link, middlePoint) {
          markerContainer(link, middlePoint);
        });
      };
      indicators();
      indicatorLayer.getSource().addFeatures(features);
    };

    var redraw = function() {
      var marker;
      var cachedLinkPropertyMarker = new LinkPropertyMarker(selectedLinkProperty);
      cachedMarker = new LinkPropertyMarker(selectedLinkProperty);
      removeSelectInteractions();
      var suravageLinks = roadCollection.getSuravageLinks();
      var roadLinks = _.reject(roadCollection.getAll(), function (rl) {
        return _.contains(_.map(suravageLinks, function(sl){ return sl.linkId;}), rl.linkId);
      });
      var linkIdsToRemove = applicationModel.getCurrentAction() !== applicationModel.actionCalculated ? [] : selectedLinkProperty.linkIdsToExclude();
      me.clearLayers([floatingMarkerLayer, anomalousMarkerLayer, geometryChangedLayer, suravageMarkerLayer, directionMarkerLayer, calibrationPointLayer]);

      if(zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForAssets) {

        var directionRoadMarker = _.filter(roadLinks, function(roadlink) {
          return roadlink.floating !== SelectionType.Floating.value && roadlink.anomaly !== Anomaly.NoAddressGiven.value && roadlink.anomaly !== Anomaly.GeometryChanged.value && (roadlink.sideCode === SideCode.AgainstDigitizing.value || roadlink.sideCode === SideCode.TowardsDigitizing.value);
        });
        _.each(directionRoadMarker, function(directionlink) {
          var marker = cachedMarker.createMarker(directionlink);
          if(zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers)
            directionMarkerLayer.getSource().addFeature(marker);
        });

        var floatingRoadMarkers = _.filter(roadLinks, function(roadlink) {
          return roadlink.floating === SelectionType.Floating.value;
        });

        var anomalousRoadMarkers = _.filter(roadLinks, function(roadlink) {
          return roadlink.anomaly !== Anomaly.None.value;
        });
        _.each(anomalousRoadMarkers, function(anomalouslink) {
          var marker = cachedMarker.createMarker(anomalouslink);
          anomalousMarkerLayer.getSource().addFeature(marker);
        });

        var suravageRoadMarkers = _.filter(suravageLinks, function(roadlink) {
          return roadlink.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value;
        });
        _.each(suravageRoadMarkers, function(directionLink) {
          var marker = cachedMarker.createMarker(directionLink);
          if(zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers)
            suravageMarkerLayer.getSource().addFeature(marker);
        });


        var floatingGroups = _.sortBy(_.groupBy(floatingRoadMarkers, function(value){
          return value.linkId;
        }), 'startAddressM');
        _.each(floatingGroups, function(floatGroup) {
            _.each(floatGroup, function(floating){
                marker = cachedLinkPropertyMarker.createMarker(floating);
                if (applicationModel.getCurrentAction() !== applicationModel.actionCalculated && !_.contains(linkIdsToRemove, marker.linkData.linkId))
                    floatingMarkerLayer.getSource().addFeature(marker);
            });
        });

        var geometryChangedRoadMarkers = _.filter(roadLinks, function(roadlink){
          return roadlink.anomaly === Anomaly.GeometryChanged.value;
        });

        _.each(geometryChangedRoadMarkers, function(geometryChangedLink) {

            var newLinkData = Object.assign({}, geometryChangedLink);
            newLinkData.roadClass = 99;
            newLinkData.roadLinkSource = 99;
            newLinkData.sideCode = 99;
            newLinkData.linkType = 99;
            newLinkData.constructionType = 0;
            newLinkData.roadLinkType = 0;
            newLinkData.id = 0;
            newLinkData.startAddressM = "";
            newLinkData.endAddressM = "";
            newLinkData.anomaly = Anomaly.NoAddressGiven.value;
            newLinkData.points = newLinkData.newGeometry;

            var marker = cachedMarker.createMarker(newLinkData);
          geometryChangedLayer.getSource().addFeature(marker);

            var points = _.map(newLinkData.newGeometry, function (point) {
            return [point.x, point.y];
          });
          var feature = new ol.Feature({ geometry: new ol.geom.LineString(points)});
            feature.linkData = newLinkData;
            roadCollection.addTmpRoadLinkGroups(newLinkData);
          geometryChangedLayer.getSource().addFeature(feature);
        });

        //Removed the need to check if the buttons are active in order to draw calibration points.
        if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomLevelForCalibrationPoints) {
          var actualPoints = me.drawCalibrationMarkers(calibrationPointLayer.source, roadLinks);
          _.each(actualPoints, function (actualPoint) {
            var calMarker = new CalibrationPoint(actualPoint);
            calibrationPointLayer.getSource().addFeature(calMarker.getMarker(true));
          });
        }
      }
      addSelectInteractions();
      if(applicationModel.getCurrentAction() === -1){
        applicationModel.removeSpinner();
      }
    };

    this.refreshView = function() {
      //Generalize the zoom levels as the resolutions and zoom levels differ between map tile sources
      roadCollection.reset();
      roadCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
      suravageRoadLayer.changed();
      roadLayer.layer.changed();
    };

    this.isDirty = function() {
      return selectedLinkProperty.isDirty();
    };

    var reselectRoadLink = function(targetFeature, adjacents) {
      var visibleFeatures = getVisibleFeatures(true, true, true, true, true, true, true);
      var indicators = adjacents;
      indicatorLayer.getSource().clear();
      if(indicators.length !== 0){
        drawIndicators(indicators);
      }

      if (applicationModel.selectionTypeIs(SelectionType.Unknown) && targetFeature.linkData.floating !== SelectionType.Floating.value && targetFeature.linkData.anomaly === Anomaly.NoAddressGiven.value) {
        if (applicationModel.isReadOnly()) {
          greenRoadLayer.setOpacity(1);
          var anomalousFeatures = _.uniq(_.filter(selectedLinkProperty.getFeaturesToKeep(), function (ft) {
            return ft.anomaly === Anomaly.NoAddressGiven.value;
          }));
          anomalousFeatures.forEach(function (fmf) {
            editFeatureDataForGreen(targetFeature.linkData);
          });
        } else {
          selectedLinkProperty.openUnknown(targetFeature.linkData, visibleFeatures);
        }
      }
    };

    var handleLinkPropertyChanged = function(eventListener) {
      removeSelectInteractions();
      eventListener.stopListening(eventbus, 'map:clicked', me.displayConfirmMessage);
      eventListener.listenTo(eventbus, 'map:clicked', me.displayConfirmMessage);
    };

    var concludeLinkPropertyEdit = function(eventListener) {
      addSelectInteractions();
      eventListener.stopListening(eventbus, 'map:clicked', me.displayConfirmMessage);
      geometryChangedLayer.setVisible(false);
      setGeneralOpacity(1);
      if(selectDoubleClick.getFeatures().getLength() !== 0){
        selectDoubleClick.getFeatures().clear();
      }
    };

    this.layerStarted = function(eventListener) {
      var linkPropertyChangeHandler = _.partial(handleLinkPropertyChanged, eventListener);
      var linkPropertyEditConclusion = _.partial(concludeLinkPropertyEdit, eventListener);
      eventListener.listenTo(eventbus, 'linkProperties:changed', linkPropertyChangeHandler);
      eventListener.listenTo(eventbus, 'linkProperties:cancelled linkProperties:saved', linkPropertyEditConclusion);
      eventListener.listenTo(eventbus, 'linkProperties:closed', refreshViewAfterClosingFloating);

      eventListener.listenTo(eventbus, 'linkProperties:selected linkProperties:multiSelected', function (link) {
        var selectedLink = (_.isUndefined(link) ? link : (_.isArray(link) ? link : [link]));
        var isUnknown = _.every(selectedLink, function(sl) {
          return sl.anomaly !== Anomaly.None.value && sl.floating !== SelectionType.Floating.value && sl.roadLinkSource !== LinkGeomSource.SuravageLinkInterface.descriptionFI;
        });
        var roads = isUnknown ? geometryChangedLayer.getSource().getFeatures() : roadLayer.layer.getSource().getFeatures();
        var features = [];
        _.each(selectedLink, function (featureLink) {
          if (selectedLinkProperty.canOpenByLinearLocationId(featureLink.linearLocationId)) {
            _.each(roads, function (feature) {
              if (_.contains(featureLink.selectedLinks, feature.linkData.linearLocationId))
                return features.push(feature);
            });
          } else if (featureLink.linkId !== 0) {
            _.each(roads, function (feature) {
              if (_.contains(featureLink.selectedLinks, feature.linkData.linkId))
                return features.push(feature);
            });
          }
        });
        if (features) {
          addFeaturesToSelection(features);
        }
        clearIndicators();
      });

      eventListener.listenTo(eventbus, 'linkProperties:reselect', reselectRoadLink);

      eventListener.listenTo(eventbus, 'roadLinks:fetched', function (eventData, reselection, selectedIds) {
        redraw();
        if (reselection && !applicationModel.selectionTypeIs(SelectionType.Unknown)) {
          _.defer(function(){
            var currentGreenFeatures = greenRoadLayer.getSource().getFeatures();
            var floatingsIds = _.chain(selectedLinkProperty.getFeaturesToKeepFloatings()).map(function (feature) {
              return feature.id;
            }).uniq().value();
            var floatingsLinkIds = _.chain(selectedLinkProperty.getFeaturesToKeepFloatings()).map(function(feature){
              return feature.linkId;
            }).uniq().value();
            var visibleFeatures = getVisibleFeatures(true,false,true);
            var featuresToReSelect = function() {
              if (floatingsIds.length !== 0) {
                return _.filter(visibleFeatures, function (feature) {
                  return _.contains(floatingsIds, feature.linkData.id);
                });
              } else {
                return _.filter(visibleFeatures, function (feature) {
                  return _.contains(floatingsLinkIds, feature.linkData.linkId);
                });
              }
            };
            var filteredFeaturesToReselect = _.reject(featuresToReSelect(), function (feat) {
              return _.some(currentGreenFeatures, function (green) {
                return green.linkData.id === feat.linkData.id;
              });
            });
            if (filteredFeaturesToReselect.length !== 0){
              addFeaturesToSelection(filteredFeaturesToReselect);
            }

            var fetchedDataInSelection = _.map(_.filter(roadLayer.layer.getSource().getFeatures(), function(feature){
              return _.contains(_.unique(selectedIds), feature.linkData.linkId);
            }), function (feat) {
              return feat.linkData;
            });

            var groups = _.flatten(eventData);
            var fetchedLinksInSelection = _.filter(groups, function (group) {
              return _.contains(_.pluck(fetchedDataInSelection, 'linkId'), group.getData().linkId);
            });
            if (fetchedLinksInSelection.length > 0) {
              eventbus.trigger('linkProperties:deselectFeaturesSelected');
              selectedLinkProperty.setCurrent(fetchedLinksInSelection);
              if (applicationModel.getCurrentAction() !== applicationModel.actionCalculating)
                eventbus.trigger('linkProperties:selected', selectedLinkProperty.extractDataForDisplay(fetchedDataInSelection));
            }
          }, reselection);
        }
      });
      eventListener.listenTo(eventbus, 'suravageRoadLinks:fetched', function(suravageRoads){
        var ol3SuravageRoads = _.map(_.flatten(suravageRoads), function(road) {
          var roadData = road.getData();
          var points = _.map(roadData.points, function (point) {
            return [point.x, point.y];
          });
          var feature = new ol.Feature({
            geometry: new ol.geom.LineString(points)
          });
            feature.linkData = roadData;
          return feature;
        });
        suravageRoadLayer.getSource().addFeatures(ol3SuravageRoads);
      });
      eventListener.listenTo(eventbus, 'suravageRoads:toggleVisibility', function(visibility){
        suravageRoadLayer.setVisible(visibility);
        suravageMarkerLayer.setVisible(visibility);
      });
      eventListener.listenTo(eventbus, 'linkProperty:visibilityChanged', function () {
        //Exclude suravage layers from toggle
        me.toggleLayersVisibility([roadLayer.layer, floatingMarkerLayer, anomalousMarkerLayer, directionMarkerLayer, geometryChangedLayer, calibrationPointLayer,
          indicatorLayer, greenRoadLayer, pickRoadsLayer, simulatedRoadsLayer, reservedRoadLayer, historicRoadsLayer], applicationModel.getRoadVisibility());
      });
      eventListener.listenTo(eventbus, 'linkProperties:dataset:changed', redraw);
      eventListener.listenTo(eventbus, 'linkProperties:updateFailed', cancelSelection);
      eventListener.listenTo(eventbus, 'adjacents:nextSelected', function(sources, adjacents, targets) {
        applicationModel.addSpinner();
        if (applicationModel.getCurrentAction() !== applicationModel.actionCalculated) {
          drawIndicators(adjacents);
          selectedLinkProperty.addTargets(targets, adjacents);
        }
        redrawNextSelectedTarget(targets, adjacents);
      });
      eventListener.listenTo(eventbus, 'adjacents:added', function(sources, targets) {
        clearIndicators();
        drawIndicators(targets);
        _.map(_.filter(selectedLinkProperty.getFeaturesToKeep(), function(link) {
          return link.roadNumber === 0;
        }), function (roads) {
          editFeatureDataForGreen(roads);
        });
      });

      eventListener.listenTo(eventbus, 'adjacents:floatingAdded', function(sources, targets) {
          clearIndicators();
          drawIndicators(targets);
      });

      eventListener.listenTo(eventbus, 'adjacents:floatingAdded', function(floatings) {
        var visibleFeatures = getVisibleFeatures(true, true, true, true, true, true,true);
        selectedLinkProperty.processOL3Features(visibleFeatures);
        drawIndicators(floatings);
      });
      eventListener.listenTo(eventbus, 'adjacents:roadTransfer', function(newRoads,changedIds) {

        var floatingsLinkIds = _.map(selectedLinkProperty.getFeaturesToKeepFloatings(), function (f) {
          return f.linkId;
        });
        var roadLinks = _.reject(roadCollection.getAll(),function (rl) {
          return _.contains(floatingsLinkIds, rl.linkId);
        });
        var afterTransferLinks=  _.filter(roadLinks, function(roadlink) {
          return !_.contains(changedIds, roadlink.linkId.toString());
        });
        var simulatedOL3Features = [];
        _.map(newRoads, function(road){
          var points = _.map(road.points, function(point) {
            return [point.x, point.y];
          });
          var feature =  new ol.Feature({ geometry: new ol.geom.LineString(points)
          });
          feature.linkData = road;
          simulatedOL3Features.push(feature);
          afterTransferLinks.push(road);
          var marker = cachedMarker.createMarker(feature.linkData);
          if (zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers) {
            simulatedRoadsLayer.getSource().addFeature(marker);
          }
        });

        var actualPoints =  me.drawCalibrationMarkers(calibrationPointLayer.source, newRoads);
        _.each(actualPoints, function(actualPoint) {
          var calMarker = new CalibrationPoint(actualPoint);
          simulatedRoadsLayer.getSource().addFeature(calMarker.getMarker(true));
        });

        indicatorLayer.getSource().clear();
        roadCollection.setTmpRoadAddresses(afterTransferLinks);
        roadCollection.setChangedIds(changedIds);
        applicationModel.setCurrentAction(applicationModel.actionCalculated);
        selectedLinkProperty.cancelAfterDefloat(applicationModel.actionCalculated, changedIds);

        clearHighlights();
        greenRoadLayer.getSource().clear();
        setGeneralOpacity(0.2);

        _.each(simulatedOL3Features, function(elem) {
          roadLayer.layer.getSource().getFeatures().filter( function(item){
              if (item.linkData.linkId === elem.linkData.linkId)
                roadLayer.layer.getSource().removeFeature(item);
          });
        });
        simulatedRoadsLayer.getSource().addFeatures(simulatedOL3Features);
        roadLayer.layer.getSource().addFeatures(simulatedRoadsLayer);
      });

      eventListener.listenTo(eventbus, 'roadLink:editModeAdjacents', function() {
        if (applicationModel.isReadOnly() && !applicationModel.isActiveButtons()) {
          indicatorLayer.getSource().clear();
          var floatingsLinkIds = _.map(_.filter(selectedLinkProperty.getFeaturesToKeep(), function (feature) {
            return feature.floating === SelectionType.Floating.value;
          }), function (floating) {
            return floating.linkId;
          });
          _.defer(function () {
            _.map(roadLayer.layer.features, function (feature) {
              if (_.contains(floatingsLinkIds, feature.attributes.linkId)) {
                selectControl.select(feature);
              }
            });
          });
        } else {
          var selectedFloatings = _.filter(selectedLinkProperty.get(), function (features) {
            return features.floating === SelectionType.Floating.value;
          });
          _.each(selectedFloatings, function(sf){
            selectedLinkProperty.addToFeaturesToKeep(sf);
          });
        }
      });

      eventListener.listenTo(eventbus, 'roadLinks:unSelectIndicators', function (originalFeature) {
        var visibleFeatures = getVisibleFeatures(true,true,true,true);
        clearIndicators();
        greenRoadLayerVector.clear();
        clearHighlights();
        if (!applicationModel.selectionTypeIs(SelectionType.Floating)) {
          var features = [];
          var extractedLinkIds = _.map(originalFeature,function(of){
            return of.linkId;
          });
          _.each(roadLayer.layer.getSource().getFeatures(), function (feature) {
              if (!_.contains(extractedLinkIds, feature.linkData.linkId) && feature.linkData.floating === SelectionType.Floating.value) {
              features.push(feature);
            }
          });

          if (!_.isEmpty(features)) {
            //currentRenderIntent = 'select';
            var featureToSelect = _.first(features);
              selectedLinkProperty.openFloating(featureToSelect.linkData, false, visibleFeatures);
            highlightFeatures();
          }
        }
      });

      eventListener.listenTo(eventbus, 'linkProperties:clearIndicators', function(){
        clearIndicators();
      });

      eventListener.listenTo(eventbus, 'roadLinks:refreshView', function(){
        me.refreshView();
      });

      var clearIndicators = function () {
        indicatorLayer.getSource().clear();
      };

      eventListener.listenTo(eventListener, 'map:clearLayers', me.clearLayers);
    };

    var cancelSelection = function() {
      if(!applicationModel.isActiveButtons()) {
        selectedLinkProperty.cancel();
        selectedLinkProperty.close();
        unselectRoadLink();
      }
    };

    var refreshViewAfterClosingFloating = function() {
      selectedLinkProperty.setDirty(false);
      selectedLinkProperty.resetTargets();
      selectedLinkProperty.clearFeaturesToKeep();
      applicationModel.resetCurrentAction();
      applicationModel.setActiveButtons(false);
      applicationModel.setContinueButton(false);
      eventbus.trigger('layer:enableButtons', true);
      eventbus.trigger('form:showPropertyForm');
      me.clearLayers();
      me.refreshView();
      toggleSelectInteractions(true, true);
      applicationModel.setSelectionType(SelectionType.All);
      selectedLinkProperty.clearFeaturesToKeep();
      greenRoadLayer.getSource().clear();
      simulatedRoadsLayer.getSource().clear();
      me.eventListener.listenToOnce(eventbus, 'roadLinks:fetched', function(){
        applicationModel.removeSpinner();
        geometryChangedLayer.setVisible(true);
      });
    };

    var redrawNextSelectedTarget = function(targets, adjacents) {
      _.find(roadLayer.layer.getSource().getFeatures(), function(feature) {
          return targets !== 0 && feature.linkData.linkId === parseInt(targets);
      }).linkData.gapTransfering = true;
      var targetFeature = _.find(geometryChangedLayer.getSource().getFeatures(), function(feature) {
          return targets !== 0 && feature.linkData.linkId === parseInt(targets);
      });
      if(_.isUndefined(targetFeature))
      {
        targetFeature = _.find(roadLayer.layer.getSource().getFeatures(), function (feature) {
            return targets !== 0 && feature.linkData.linkId === parseInt(targets);
        });
      }
      reselectRoadLink(targetFeature, adjacents);
      redraw();
      reHighlightGreen();
    };

    var reHighlightGreen = function() {
      var greenFeaturesLinkId = _.map(greenRoadLayer.getSource().getFeatures(), function(gf){
          return gf.linkData.linkId;
      });

      if (greenFeaturesLinkId.length !== 0) {
        var features =[];
        _.each(roadLayer.layer.getSource().getFeatures(), function (feature) {
            if (_.contains(greenFeaturesLinkId, feature.linkData.linkId)) {

                feature.linkData.prevAnomaly = feature.linkData.anomaly;
                feature.linkData.gapTransfering = true;
                var greenRoadStyle = roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)});
            feature.setStyle(greenRoadStyle);
            features.push(feature);
          }
        });
        greenRoads(features,true);
        addFeaturesToSelection(features);
      }
    };

    var editFeatureDataForGreen = function (targets) {
      var features =[];
      if(targets !== 0){
        var targetFeature = _.find(greenRoadLayer.getSource().getFeatures(), function(greenFeature){
            return targets.linkId !== 0 && greenFeature.linkData.linkId === parseInt(targets.linkId);
        });
        if(!targetFeature){
          _.map(roadLayer.layer.getSource().getFeatures(), function(feature){
              if (feature.linkData.linkId === targets.linkId) {
              var pickAnomalousMarker;
                  if (feature.linkData.anomaly === Anomaly.GeometryChanged.value) {
                      var roadLink = feature.linkData;
                var points = _.map(roadLink.newGeometry, function (point) {
                  return [point.x, point.y];
                });
                feature = new ol.Feature({geometry: new ol.geom.LineString(points)});
                      feature.linkData = roadLink;
                pickAnomalousMarker = _.filter(geometryChangedLayer.getSource().getFeatures(), function(marker){
                    return marker.linkData.linkId === feature.linkData.linkId;
                });
                _.each(pickAnomalousMarker, function(pickRoads){
                  geometryChangedLayer.getSource().removeFeature(pickRoads);
                });
              }

                  feature.linkData.prevAnomaly = feature.linkData.anomaly;
                  feature.linkData.gapTransfering = true;
                  var greenRoadStyle = roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)});
              feature.setStyle(greenRoadStyle);
              features.push(feature);
              roadCollection.addPreMovedRoadAddresses(feature.data);
              pickAnomalousMarker = _.filter(pickRoadsLayer.getSource().getFeatures(), function(markersPick){
                  return markersPick.linkData.linkId === feature.linkData.linkId;
              });
              _.each(pickAnomalousMarker, function(pickRoads){
                pickRoadsLayer.getSource().removeFeature(pickRoads);
              });
              if(!applicationModel.selectionTypeIs(SelectionType.Unknown))
                geometryChangedLayer.setVisible(false);
            }
          });
          addFeaturesToSelection(features);
          greenRoads(features);
        }
      }
      if(features.length === 0)
        return undefined;
      else return _.first(features);
    };

    me.eventListener.listenTo(eventbus, 'linkProperties:highlightSelectedProject', function(featureLinkId) {
      setGeneralOpacity(0.2);
      var boundingBox = map.getView().calculateExtent(map.getSize());
      var zoomLevel = zoomlevels.getViewZoom(map);
      roadCollection.findReservedProjectLinks(boundingBox, zoomLevel, featureLinkId);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:highlightReservedRoads', function(reservedOL3Features){
      var styledFeatures = _.map(reservedOL3Features, function(feature) {
        feature.setStyle(roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)}));
        return feature;
      });
      if (applicationModel.getSelectedLayer() === "linkProperty") { //check if user is still in reservation form
        reservedRoadLayer.getSource().addFeatures(styledFeatures);
      }
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:deselectFeaturesSelected', function(){
      clearHighlights();
      geometryChangedLayer.setVisible(true);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:highlightAnomalousByFloating', function(){
      highlightAnomalousFeaturesByFloating();
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:highlightSelectedFloatingFeatures', function(){
      highlightSelectedFloatingFeatures();
      geometryChangedLayer.setOpacity(1);
    });

    var highlightAnomalousFeaturesByFloating = function() {
      var allFeatures = roadLayer.layer.getSource().getFeatures().concat(anomalousMarkerLayer.getSource().getFeatures()).concat(floatingMarkerLayer.getSource().getFeatures());
      _.each(allFeatures, function(feature){
          if (feature.linkData.anomaly === Anomaly.NoAddressGiven.value || feature.linkData.anomaly === Anomaly.GeometryChanged.value || feature.linkData.floating === SelectionType.Floating.value)
          pickRoadsLayer.getSource().addFeature(feature);
      });
      pickRoadsLayer.setOpacity(1);
      setGeneralOpacity(0.2);
    };

    var highlightSelectedFloatingFeatures = function() {
      var allFeatures = roadLayer.layer.getSource().getFeatures().concat(anomalousMarkerLayer.getSource().getFeatures()).concat(floatingMarkerLayer.getSource().getFeatures());
      var selectedFloatingIds = _.pluck(selectedLinkProperty.getFeaturesToKeepFloatings(), 'linkId');

      _.each(allFeatures, function(feature){
          if (feature.linkData.anomaly === Anomaly.NoAddressGiven.value || (_.contains(selectedFloatingIds, feature.linkData.linkId) && feature.linkData.floating === SelectionType.Floating.value))
          pickRoadsLayer.getSource().addFeature(feature);
      });
      pickRoadsLayer.setOpacity(1);
      setGeneralOpacity(0.2);
    };

    me.eventListener.listenTo(eventbus, 'linkProperties:cleanFloatingsAfterDefloat', function(){
      cleanFloatingsAfterDefloat();
      cleanUnknownsAfterDefloat();
    });

    var cleanFloatingsAfterDefloat = function() {
      var floatingRoadMarker = [];
      /*
       * Clean from pickLayer floatings selected
       */
      var FeaturesToKeepFloatings = _.reject(_.map(selectedLinkProperty.getFeaturesToKeepFloatings(), function (featureToKeep){
        if(featureToKeep.floating === SelectionType.Floating.value && featureToKeep.anomaly === Anomaly.None.value) {
          return featureToKeep.linkId;
        } else return undefined;
      }), function(featuresNotToKeep){
        return _.isUndefined(featuresNotToKeep);
      });

      var olUids = _.reject(_.map(pickRoadsLayer.getSource().getFeatures(), function(pickFeature){
          if (_.contains(FeaturesToKeepFloatings, pickFeature.linkData.linkId))
          return pickFeature.ol_uid;
        else return undefined;
      }), function(featuresNotToKeep){
        return _.isUndefined(featuresNotToKeep);
      });
      var PickFeaturesToRemove = _.filter(pickRoadsLayer.getSource().getFeatures(), function (pickFeatureToRemove){
        return !_.contains(olUids, pickFeatureToRemove.ol_uid);
      });

      pickRoadsLayer.getSource().clear();
      pickRoadsLayer.getSource().addFeatures(PickFeaturesToRemove );

      /*
       * Clean from calibrationPoints layer selected
       */

      _.map(selectedLinkProperty.getFeaturesToKeepFloatings(), function(featureToKeep){
        if(featureToKeep.calibrationPoints.length > 0) {
          _.each(featureToKeep.calibrationPoints, function (cPoint) {
            var newPoint = new CalibrationPoint({points:cPoint.point, calibrationCode:unknownCalibrationPointValue}).getMarker(true);
            _.each(calibrationPointLayer.getSource().getFeatures(), function (feature) {
              if (newPoint.values_.geometry.flatCoordinates[0] === feature.values_.geometry.flatCoordinates[0] &&
                newPoint.values_.geometry.flatCoordinates[1] === feature.values_.geometry.flatCoordinates[1]) {
                calibrationPointLayer.getSource().removeFeature(feature);
              }
            });
          });
        }
      });

      /*
       * Clean from roadLayer floatings selected
       */
      var olUidsRoadLayer = _.reject(_.map(roadLayer.layer.getSource().getFeatures(), function(featureRoadLayer){
          if (_.contains(FeaturesToKeepFloatings, featureRoadLayer.linkData.linkId))
          return featureRoadLayer.ol_uid;
        else return undefined;
      }), function(featuresNotToKeep){
        return _.isUndefined(featuresNotToKeep);
      });
      var featuresRoadLayerToKeep = _.filter(roadLayer.layer.getSource().getFeatures(), function (featureRoadLayer){
        return !_.contains(olUidsRoadLayer, featureRoadLayer.ol_uid);
      });
      var featuresRoadLayerToRemove = _.filter(roadLayer.layer.getSource().getFeatures(), function (featureRoadLayer){
        return _.contains(olUidsRoadLayer, featureRoadLayer.ol_uid);
      });

      roadLayer.layer.getSource().clear();
      roadLayer.layer.getSource().addFeatures(featuresRoadLayerToKeep);
      floatingRoadMarker = floatingRoadMarker.concat(featuresRoadLayerToRemove);

      /*
       * Clean from floatingMarkerLayer markers from selected floatings
       */
      var olUidsFloatingLayer = _.reject(_.map(floatingMarkerLayer.getSource().getFeatures(), function(feature){
          if (_.contains(FeaturesToKeepFloatings, feature.linkData.linkId))
          return feature.ol_uid;
        else return undefined;
      }), function(featuresNotToKeep){
        return _.isUndefined(featuresNotToKeep);
      });
      var featuresFloatingLayerToKeep = _.filter(floatingMarkerLayer.getSource().getFeatures(), function (featureFloatMarker){
        return !_.contains(olUidsFloatingLayer, featureFloatMarker.ol_uid);
      });

      var featuresFloatingLayerToRemove = _.filter(floatingMarkerLayer.getSource().getFeatures(), function (featureFloatMarker){
        return _.contains(olUidsFloatingLayer, featureFloatMarker.ol_uid);
      });

      floatingMarkerLayer.getSource().clear();
      floatingMarkerLayer.getSource().addFeatures(featuresFloatingLayerToKeep);
      floatingRoadMarker = floatingRoadMarker.concat(featuresFloatingLayerToRemove);
      /*
       * Add to FloatingRoadMarker to keep in the case of clicking cancel (peruuta).
       */
      selectedLinkProperty.setFloatingRoadMarker(floatingRoadMarker);
      geometryChangedLayer.setVisible(false);
    };

    var cleanUnknownsAfterDefloat = function(){
      var unknownRoadMarkers =[];

      var unknownFeaturesToKeep = _.reject(_.map(selectedLinkProperty.getFeaturesToKeepUnknown(), function(feature){
        if(feature.anomaly === Anomaly.NoAddressGiven.value){
          return feature.linkId;
        } else return undefined;
      }), function(featureNotToKeep){
        return _.isUndefined(featureNotToKeep);
      });

      //Clean from anomalousMarkerLayer

      var olUidsAnomalousMarkerLayer = _.reject(_.map(anomalousMarkerLayer.getSource().getFeatures(),function(anomalousMarkerLayerFeature){
          if (_.contains(unknownFeaturesToKeep, anomalousMarkerLayerFeature.linkData.linkId)) {
          return anomalousMarkerLayerFeature.ol_uid;
        } else return undefined;
      }), function(featuresNotToKeep){
        return _.isUndefined(featuresNotToKeep);
      });

      var anomalousMarkerLayerFeaturesToKeep = _.filter(anomalousMarkerLayer.getSource().getFeatures(), function(pf){
        return !_.contains(olUidsAnomalousMarkerLayer, pf.ol_uid);
      });

      var anomalousMarkerLayerFeaturesToRemove = _.filter(anomalousMarkerLayer.getSource().getFeatures(), function(pf){
        return _.contains(olUidsAnomalousMarkerLayer, pf.ol_uid);
      });
      anomalousMarkerLayer.getSource().clear();
      anomalousMarkerLayer.getSource().addFeatures(anomalousMarkerLayerFeaturesToKeep);
      unknownRoadMarkers = unknownRoadMarkers.concat(anomalousMarkerLayerFeaturesToRemove);
      selectedLinkProperty.setAnomalousMarkers(unknownRoadMarkers);
    };

    me.eventListener.listenTo(eventbus, 'linkProperties:floatingRoadMarkerPreviousSelected', function(){
      addSelectedFloatings();
      addSelectedUnknowns();
    });

    var addSelectedFloatings = function() {
      var floatingRoadMarker = selectedLinkProperty.getFloatingRoadMarker();
      var floatingRoad = [];
      var floatingMarker = [];
      _.each(floatingRoadMarker, function(floating){
        if (floating.getGeometry().getType() === 'LineString') {
          floatingRoad.push(floating);
        } else {
          floatingMarker.push(floating);
        }
      });

      _.each(floatingMarker, function(marker){
        floatingMarkerLayer.getSource().addFeature(marker);
      });

      _.each(floatingRoad, function(road){
        roadLayer.layer.getSource().addFeature(road);
      });
    };

    var addSelectedUnknowns = function(){
      var anomalousRoadMarker = selectedLinkProperty.getAnomalousMarkers();
      var anomalousMarker = [];
      _.each(anomalousRoadMarker, function(anomalous){
        if(anomalous.getGeometry().getType() !== 'LineString'){
          anomalousMarker.push(anomalous);
        }
      });

      _.each(anomalousMarker, function(marker){
        anomalousMarkerLayer.getSource().addFeature(marker);
      });
    };

    me.eventListener.listenTo(eventbus, 'linkProperty:fetch', function() {
      map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
      roadCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','),zoomlevels.getViewZoom(map) + 1);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:activateInteractions', function(){
      toggleSelectInteractions(true, true);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:deactivateInteractions', function(){
      toggleSelectInteractions(false, true);
    });

    me.eventListener.listenTo(eventbus, 'linkProperty:fetchedHistoryLinks', function(historyLinkData) {
      var points = _.map(historyLinkData.geometry, function(point) {
        return [point.x, point.y];
      });
      var historyFeatures = _.map(historyLinkData, function(link) {
        var feature = new ol.Feature({
          geometry: new ol.geom.LineString(points)
        });
          feature.linkData = link;
        return feature;
      });
      historicRoadsLayer.getSource().addFeatures(historyFeatures);
    });

    me.eventListener.listenTo(eventbus, 'linkProperty:fetchHistoryLinks', function(date){
      roadCollection.setDate(date);
      roadCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:unselected', function() {
      clearHighlights();
      setGeneralOpacity(1);
      if(greenRoadLayer.getSource().getFeatures().length !== 0) {
        unselectRoadLink();
      }
      if(indicatorLayer.getSource().getFeatures().length !== 0){
        indicatorLayer.getSource().clear();
      }
      if (applicationModel.selectionTypeIs(SelectionType.Floating)) {
        setGeneralOpacity(0.2);
        floatingMarkerLayer.setOpacity(1);
      } else if (applicationModel.selectionTypeIs(SelectionType.Unknown)) {
        setGeneralOpacity(0.2);
        anomalousMarkerLayer.setOpacity(1);
      }
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:clearHighlights', function(){
      unselectRoadLink();
      geometryChangedLayer.setVisible(false);
      if(pickRoadsLayer.getSource().getFeatures().length !== 0){
        pickRoadsLayer.getSource().clear();
      }
      clearHighlights();
      if(simulatedRoadsLayer.getSource().getFeatures().length !== 0){
        simulatedRoadsLayer.getSource().clear();
      }
      var featureToReOpen = _.cloneDeep(_.first(selectedLinkProperty.getFeaturesToKeepFloatings()));
      var visibleFeatures = getVisibleFeatures(true,true,true);
      selectedLinkProperty.openFloating(featureToReOpen, false, visibleFeatures);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:deactivateDoubleClick', function(){
      toggleSelectInteractions(false, false);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:deactivateAllSelections roadAddressProject:deactivateAllSelections', function(){
      toggleSelectInteractions(false, true);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:activateDoubleClick', function(){
      toggleSelectInteractions(true, false);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:activateAllSelections roadAddressProject:startAllInteractions', function(){
      toggleSelectInteractions(true, true);
    });

    me.eventListener.listenTo(eventbus, 'layer:selected', function(layer, previouslySelectedLayer){
      isActiveLayer = layer === 'linkProperty';
      toggleSelectInteractions(isActiveLayer, true);
      if (isActiveLayer) {
        addSelectInteractions();
      } else {
        removeSelectInteractions();
      }
      me.clearLayers();
      clearHighlights();
      if (previouslySelectedLayer === 'linkProperty') {
        hideLayer();
        removeSelectInteractions();
      } else if(previouslySelectedLayer === 'roadAddressProject') {
        setGeneralOpacity(1);
        showLayer();
        _.defer(function(){
          roadCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
        });
      }
      me.toggleLayersVisibility(layers, applicationModel.getRoadVisibility());
    });

    var clearHighlights = function(){
      selectSingleClick.getFeatures().clear();
      selectDoubleClick.getFeatures().clear();
      map.updateSize();
    };

    var toggleSelectInteractions = function (activate, both) {
      selectDoubleClick.setActive(activate);
      if (both) {
        selectSingleClick.setActive(activate);
      }
    };

    me.eventListener.listenTo(eventbus, 'roadAddressProject:clearOnClose', function(){
      setGeneralOpacity(1);
      reservedRoadLayer.getSource().clear();
      applicationModel.setReadOnly(true);
    });
    
    var showLayer = function(){
      me.start();
      me.layerStarted(me.eventListener);
    };

    var hideLayer = function() {
      unselectRoadLink();
      me.clearLayers(layers);
    };

    me.toggleLayersVisibility(layers, true);
    me.addLayers(layers);
    me.layerStarted(me.eventListener);

    return {
      show: showLayer,
      hide: hideLayer,
      minZoomForContent: me.minZoomForContent
    };
  };
})(this);
