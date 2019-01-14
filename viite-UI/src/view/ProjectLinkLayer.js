(function (root) {
  root.ProjectLinkLayer = function (map, projectCollection, selectedProjectLinkProperty, roadLayer) {
    var layerName = 'roadAddressProject';
    Layer.call(this, map);
    var me = this;

    var calibrationPointVector = new ol.source.Vector({});
    var directionMarkerVector = new ol.source.Vector({});
    var suravageProjectDirectionMarkerVector = new ol.source.Vector({});
    var suravageRoadVector = new ol.source.Vector({});

    var Anomaly = LinkValues.Anomaly;
    var LinkGeomSource = LinkValues.LinkGeomSource;
    var SideCode = LinkValues.SideCode;
    var RoadZIndex = LinkValues.RoadZIndex;
    var LinkStatus = LinkValues.LinkStatus;
    var RoadClass = LinkValues.RoadClass;
    var SelectionType = LinkValues.SelectionType;
    var RoadLinkType = LinkValues.RoadLinkType;
    var isNotEditingData = true;
    var isActiveLayer = false;

    var projectLinkStyler = new ProjectLinkStyler();

    var projectLinkVector = new ol.source.Vector({
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
          return projectLinkStyler.getStyler(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)});
      },
      zIndex: RoadZIndex.SuravageLayer.value
    });

    var suravageProjectDirectionMarkerLayer = new ol.layer.Vector({
      source: suravageProjectDirectionMarkerVector,
      name: 'suravageProjectDirectionMarkerLayer',
      zIndex: RoadZIndex.DirectionMarkerLayer.value
    });

    var projectLinkLayer = new ol.layer.Vector({
      source: projectLinkVector,
      name: layerName,
      style: function(feature) {
          return projectLinkStyler.getStyler(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)});
      },
      zIndex: RoadZIndex.VectorLayer.value
    });

    var layers = [projectLinkLayer, calibrationPointLayer, directionMarkerLayer, suravageRoadProjectLayer, suravageProjectDirectionMarkerLayer];

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
          if (!_.isUndefined(selection)) {
            if (clickType === 'single')
              showSingleClickChanges(ctrlPressed, selection);
            else
              showDoubleClickChanges(ctrlPressed, selection);
          }
        },
        closeCallback: function () {
          isNotEditingData = false;
        }
      });
    };

    var possibleStatusForSelection = [LinkStatus.NotHandled.value, LinkStatus.New.value, LinkStatus.Terminated.value, LinkStatus.Transfer.value, LinkStatus.Unchanged.value, LinkStatus.Numbering.value];

    var selectSingleClick = new ol.interaction.Select({
      layer: [projectLinkLayer, suravageRoadProjectLayer],
      condition: ol.events.condition.singleClick,
      style: function (feature) {
        if (!_.isUndefined(feature.linkData))
          if (projectLinkStatusIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value || feature.linkData.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value) {
              return projectLinkStyler.getSelectionLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)});
          }
      }
    });

    selectSingleClick.set('name', 'selectSingleClickInteractionPLL');

    selectSingleClick.on('select', function (event) {
      var ctrlPressed = !_.isUndefined(event.mapBrowserEvent) ? event.mapBrowserEvent.originalEvent.ctrlKey : false;
      removeCutterMarkers();
      var rawSelection = !_.isUndefined(event.mapBrowserEvent) ? map.forEachFeatureAtPixel(event.mapBrowserEvent.pixel, function(feature) {
        return feature;
      }) : event.selected;
      var selection = _.find(ctrlPressed ? [rawSelection] : [rawSelection].concat(selectSingleClick.getFeatures().getArray()), function (selectionTarget) {
        if (!_.isUndefined(selectionTarget))
          return (applicationModel.getSelectedTool() !== 'Cut' && !_.isUndefined(selectionTarget.linkData) && (
                  projectLinkStatusIn(selectionTarget.linkData, possibleStatusForSelection) ||
                  (selectionTarget.linkData.anomaly === Anomaly.NoAddressGiven.value && selectionTarget.linkData.floating !== SelectionType.Floating.value) ||
                  selectionTarget.linkData.roadClass === RoadClass.NoClass.value || selectionTarget.linkData.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value || (selectionTarget.getProperties().type && selectionTarget.getProperties().type === "marker"))
          );
        else return false;
      });
      if (ctrlPressed) {
        showDoubleClickChanges(ctrlPressed, selection);
      } else if (isNotEditingData) {
        showSingleClickChanges(ctrlPressed, selection);
      } else {
        var selectedFeatures = event.deselected.concat(selectDoubleClick.getFeatures().getArray());
        clearHighlights();
        addFeaturesToSelection(selectedFeatures);
        fireDeselectionConfirmation(ctrlPressed, selection, 'single');
      }
        highlightFeatures();
    });

    var showSingleClickChanges = function (ctrlPressed, selection) {
      if (applicationModel.getSelectedTool() === 'Cut')
        return;
      if (ctrlPressed && !_.isUndefined(selection) && !_.isUndefined(selectedProjectLinkProperty.get())) {
        if (canBeAddedToSelection(selection.linkData)) {
          var clickedIds = projectCollection.getMultiProjectLinks(getSelectedId(selection.linkData));
          var selectedLinkIds = _.map(selectedProjectLinkProperty.get(), function (selected) {
            return getSelectedId(selected);
          });
          if (_.contains(selectedLinkIds, getSelectedId(selection.linkData))) {
            selectedLinkIds = _.without(selectedLinkIds, clickedIds);
          } else {
            selectedLinkIds = _.union(selectedLinkIds, clickedIds);
          }
          selectedProjectLinkProperty.openCtrl(selectedLinkIds);
        }
        highlightFeatures();
      } else if (!_.isUndefined(selection) && !selectedProjectLinkProperty.isDirty()) {
        selectedProjectLinkProperty.clean();
        projectCollection.setTmpDirty([]);
        projectCollection.setDirty([]);
        if(!_.isUndefined(selection.linkData.connectedLinkId)){
          selectedProjectLinkProperty.openSplit(selection.linkData.linkId, true);
        } else {
          selectedProjectLinkProperty.open(getSelectedId(selection.linkData), true);
        }
      } else {
        eventbus.trigger('roadAddressProject:discardChanges'); // Background map was clicked so discard changes
      }
    };

    var selectDoubleClick = new ol.interaction.Select({
      layer: [projectLinkLayer, suravageRoadProjectLayer],
      condition: ol.events.condition.doubleClick,
      style: function(feature) {
          if (projectLinkStatusIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value) {
              return projectLinkStyler.getSelectionLinkStyle().getStyle(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)});
        }
      }
    });

    selectDoubleClick.set('name', 'selectDoubleClickInteractionPLL');

    selectDoubleClick.on('select', function (event) {
      var ctrlPressed = event.mapBrowserEvent.originalEvent.ctrlKey;
      var selection = _.find(event.selected, function (selectionTarget) {
          return (applicationModel.getSelectedTool() !== 'Cut' && !_.isUndefined(selectionTarget.linkData) && (
                  projectLinkStatusIn(selectionTarget.linkData, possibleStatusForSelection) ||
                  (selectionTarget.linkData.anomaly === Anomaly.NoAddressGiven.value && selectionTarget.linkData.floating !== SelectionType.Floating.value) ||
                  selectionTarget.linkData.roadClass === RoadClass.NoClass.value || (selectionTarget.getProperties().type && selectionTarget.getProperties().type === "marker"))
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
      highlightFeatures();
    });

    var showDoubleClickChanges = function (ctrlPressed, selection) {
      if (ctrlPressed && !_.isUndefined(selectedProjectLinkProperty.get())) {
        if (!_.isUndefined(selection) && canBeAddedToSelection(selection.linkData)) {
          var selectedLinkIds = _.map(selectedProjectLinkProperty.get(), function (selected) {
            return getSelectedId(selected);
          });
          if (_.contains(selectedLinkIds, getSelectedId(selection.linkData))) {
            selectedLinkIds = _.without(selectedLinkIds, getSelectedId(selection.linkData));
          } else {
            selectedLinkIds = selectedLinkIds.concat(getSelectedId(selection.linkData));
          }
          selectedProjectLinkProperty.openCtrl(selectedLinkIds);
        }
        highlightFeatures();
      } else if (!_.isUndefined(selection) && !selectedProjectLinkProperty.isDirty()) {
        selectedProjectLinkProperty.clean();
        projectCollection.setTmpDirty([]);
        projectCollection.setDirty([]);
        if (!_.isUndefined(selection.linkData.connectedLinkId)) {
          selectedProjectLinkProperty.openSplit(selection.linkData.linkId, true);
        } else {
          selectedProjectLinkProperty.open(getSelectedId(selection.linkData));
        }
      }
    };

    //Add defined interactions to the map.
    map.addInteraction(selectSingleClick);
    map.addInteraction(selectDoubleClick);

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

    var canBeAddedToSelection = function(selectionData) {
      if (selectedProjectLinkProperty.get().length === 0) {
        return true;
      }
      var currentlySelectedSample = _.first(selectedProjectLinkProperty.get());
      return selectionData.roadNumber === currentlySelectedSample.roadNumber &&
        selectionData.roadPartNumber === currentlySelectedSample.roadPartNumber &&
        selectionData.trackCode === currentlySelectedSample.trackCode &&
        selectionData.roadType === currentlySelectedSample.roadType;
    };

    var highlightFeatures = function () {
      clearHighlights();
      var featuresToHighlight = [];
      _.each(projectLinkVector.getFeatures(), function (feature) {
          var canIHighlight = ((!_.isUndefined(feature.linkData.linkId) && _.isUndefined(feature.linkData.connectedLinkId)) ||
          (!_.isUndefined(feature.linkData.connectedLinkId) && feature.linkData.status === LinkStatus.Terminated.value) ?
              selectedProjectLinkProperty.isSelected(getSelectedId(feature.linkData)) : false);
        if (canIHighlight) {
          featuresToHighlight.push(feature);
        }
      });
      addFeaturesToSelection(featuresToHighlight);

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

    var removeFeaturesByType = function (match) {
      _.each(selectSingleClick.getFeatures().getArray().concat(selectDoubleClick.getFeatures().getArray()), function (feature) {
        if (feature && feature.getProperties().type === match) {
          selectSingleClick.getFeatures().remove(feature);
        }
      });
    };

    me.eventListener.listenTo(eventbus, 'projectLink:clicked projectLink:split projectLink:errorClicked', function () {
      highlightFeatures();
    });

    var zoomDoubleClickListener = function (event) {
      if (isActiveLayer) {
        _.defer(function () {
          if (applicationModel.getSelectedTool() !== 'Cut' && !event.originalEvent.ctrlKey &&
            selectedProjectLinkProperty.get().length === 0 && zoomlevels.getViewZoom(map) <= 13) {
              map.getView().setZoom(zoomlevels.getViewZoom(map) + 1);
          }
        });
      }
    };
    //This will control the double click zoom when there is no selection that activates
    map.on('dblclick', zoomDoubleClickListener);
    if (window.getSelection) {window.getSelection().removeAllRanges();} //removes selection from forms
    else if (document.selection) {document.selection.empty();}
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

    //Listen pointerMove and get pixel for displaying roadAddress feature info
    me.eventListener.listenTo(eventbus, 'map:mouseMoved', function (event, pixel) {
      if (event.dragging) {
        return;
      }
      if (applicationModel.getSelectedTool() === 'Cut' && suravageCutter) {
        suravageCutter.updateByPosition(event.coordinate);
      } else {
        eventbus.trigger('overlay:update', event, pixel);
      }
    });

    var loadFeatures = function (features) {
      projectLinkVector.clear(true);
      projectLinkVector.addFeatures(features);
    };

    var showLayer = function() {
      me.start();
    };

    var hideLayer = function () {
      projectLinkLayer.getSource().clear();
      calibrationPointLayer.getSource().clear();
      suravageProjectDirectionMarkerLayer.getSource().clear();
      suravageRoadProjectLayer.getSource().clear();
      directionMarkerLayer.getSource().clear();
      me.clearLayers(layers);
    };

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

    var removeCutterMarkers = function () {
      var featuresToRemove = [];
      _.each(selectSingleClick.getFeatures().getArray(), function(feature){
        if (feature.getProperties().type === 'cutter')
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
          if (feature && feature.getProperties().type === match) {
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

        if (applicationModel.getSelectedTool() === 'Cut' && suravageLayer.getVisible()) {
          $('.wrapper').remove();
          removeCutterMarkers();
          self.cut(evt);
        }
        eventbus.trigger('projectLink:clickHandled');
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

        var possibleSplit = _.filter(projectLinkVector.getFeatures().concat(suravageRoadProjectLayer.getSource().getFeatures()), function(feature){
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
        if (suravageRoadProjectLayer.getVisible() && isWithinCutThreshold(closestSuravageLink.distance)) {
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

    me.eventListener.listenTo(eventbus, 'split:projectLinks', function (split) {
      _.defer(function () {
        drawIndicators(_.filter(split, function (link) {
          return !_.isUndefined(link.marker);
        }));
      });
      eventbus.trigger('projectLink:split', split);
    });

    me.eventListener.listenTo(eventbus, 'projectLink:projectLinksCreateSuccess', function () {
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.eventListener.listenTo(eventbus, 'changeProjectDirection:clicked', function () {
     projectLinkVector.clear();
      directionMarkerLayer.getSource().clear();
      me.eventListener.listenToOnce(eventbus, 'roadAddressProject:fetched', function () {
        if (selectedProjectLinkProperty.isSplit())
          selectedProjectLinkProperty.openSplit(selectedProjectLinkProperty.get()[0].linkId, true);
        else
          selectedProjectLinkProperty.open(getSelectedId(selectedProjectLinkProperty.get()[0]), selectedProjectLinkProperty.isMultiLink());
      });
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.eventListener.listenTo(eventbus, 'split:splitCutLine', function (cutGeom) {
      addCutLine(cutGeom);
      applicationModel.removeSpinner();
    });

    me.eventListener.listenTo(eventbus, 'projectLink:revertedChanges', function () {
      isNotEditingData = true;
      selectedProjectLinkProperty.setDirty(false);
      eventbus.trigger('roadAddress:projectLinksUpdated');
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.redraw = function () {
      var checkedBoxLayers = _.filter(layers, function(layer) {
          if ((layer.get('name') === 'suravageRoadProjectLayer' || layer.get('name') === 'suravageProjectDirectionMarkerLayer') &&
              (!suravageRoadProjectLayer.getVisible() || !suravageProjectDirectionMarkerLayer.getVisible())){
            return false;
          } else
            return true;
      });
      me.toggleLayersVisibility(checkedBoxLayers, applicationModel.getRoadVisibility(), true);
      var marker;
      var cachedMarker = new ProjectLinkMarker(selectedProjectLinkProperty);

      calibrationPointLayer.getSource().clear();
      suravageProjectDirectionMarkerLayer.getSource().clear();
      suravageRoadProjectLayer.getSource().clear();
      directionMarkerLayer.getSource().clear();

      var editedLinks = _.map(projectCollection.getDirty(), function (editedLink) {
        return editedLink;
      });

      var separated = _.partition(projectCollection.getAll(), function (projectRoad) {
        return projectRoad.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value;
      });

      var toBeTerminated = _.filter(editedLinks, function (link) {
        return link.status === LinkStatus.Terminated.value;
      });

      var suravageProjectRoads = separated[0].filter(function (val) {
        return _.find(separated[1], function (link) {
          return link.linkId === val.linkId;
        }) !== 0;
      });

      _.map(suravageProjectRoads, function (projectLink) {
        var points = _.map(projectLink.points, function (point) {
          return [point.x, point.y];
        });
        var feature = new ol.Feature({
          geometry: new ol.geom.LineString(points)
        });
        feature.linkData = projectLink;
        suravageRoadProjectLayer.getSource().addFeatures([feature]);
      });

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

      var removeSelectFeatures = function(select) {
        var selectFeatures = select.getFeatures();
        _.each(selectFeatures.getArray(), function (feature) {
          if (!_.isUndefined(feature))
            if (feature.getProperties().type && feature.getProperties().type === "marker")
              selectFeatures.remove(feature);
        });
      };
      removeSelectFeatures(selectSingleClick);
      removeSelectFeatures(selectDoubleClick);

      if (zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers) {
        var addMarkersToLayer = function(links, layer) {
          var directionMarkers = _.filter(links, function (projectLink) {
              var acceptedLinks = projectLink.id !== 0 || (projectLink.id === 0 && (projectLink.anomaly === Anomaly.NoAddressGiven.value || projectLink.roadLinkType === RoadLinkType.FloatingRoadLinkType.value));
              return acceptedLinks && projectLink.sideCode !== SideCode.Unknown.value && projectLink.endAddressM !== 0;
          });
          _.each(directionMarkers, function (directionLink) {
            marker = cachedMarker.createProjectMarker(directionLink);
            layer.getSource().addFeature(marker);
          });
        };
        addMarkersToLayer(suravageProjectRoads, suravageProjectDirectionMarkerLayer);
        addMarkersToLayer(projectLinks, directionMarkerLayer);
      }

      if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomLevelForCalibrationPoints) {
        var actualCalibrationPoints = me.drawCalibrationMarkers(calibrationPointLayer.source, projectLinks.concat(suravageProjectRoads));
        _.each(actualCalibrationPoints, function (actualPoint) {
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
          if (_.contains( _.pluck(toBeTerminated, 'id'), feature.linkData.linkId)) {
            feature.linkData.status = LinkStatus.Terminated.value;
            var termination = projectLinkStyler.getStyler(feature.linkData, {zoomLevel:zoomlevels.getViewZoom(map)});
            feature.setStyle(termination);
            features.push(feature);
          }
        }
      });

      if (features.length !== 0)
        addFeaturesToSelection(features);
      features = features.concat(partitioned[1]);
      _.each(features, function(feature) {
        return feature;
      });
      projectLinkVector.clear(true);
      projectLinkVector.addFeatures(features);
      projectLinkLayer.changed();
    };

    me.eventListener.listenTo(eventbus, 'tool:changed', changeTool);

    me.eventListener.listenTo(eventbus, 'roadAddressProject:openProject', function (projectSelected) {
      this.project = projectSelected;
      eventbus.trigger('layer:enableButtons', false);
      eventbus.trigger('editMode:setReadOnly', false);
      eventbus.trigger('roadAddressProject:selected', projectSelected.id, layerName, applicationModel.getSelectedLayer());
      applicationModel.selectLayer(layerName);
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:selected', function (projId) {
      me.eventListener.listenToOnce(eventbus, 'roadAddressProject:projectFetched', function (projectInfo) {
        projectCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map), projectInfo.id, projectInfo.publishable);
      });
      projectCollection.getProjectsWithLinksById(projId);
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:fetch', function() {
      var projectId = _.isUndefined(projectCollection.getCurrentProject()) ? undefined : projectCollection.getCurrentProject().project.id;
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, projectId, projectCollection.getPublishableStatus());
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:fetched', function () {
      me.redraw();
      _.defer(function () {
        highlightFeatures();
        if (selectedProjectLinkProperty.isSplit())
          drawIndicators(selectedProjectLinkProperty.get());
      });
    });

    me.eventListener.listenTo(eventbus, 'roadAddress:projectLinksEdited', function () {
      me.redraw();
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:projectLinkSaved', function (projectId, isPublishable) {
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map) + 1, projectId, isPublishable);
    });

    me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
      isActiveLayer = layer === 'roadAddressProject';
      toggleSelectInteractions(isActiveLayer, true);
      if (isActiveLayer) {
        addSelectInteractions();
      } else {
        clearHighlights();
        removeSelectInteractions();
      }
      if (previouslySelectedLayer === 'roadAddressProject') {
        hideLayer();
        removeSelectInteractions();
      }
      projectLinkLayer.setVisible(isActiveLayer && applicationModel.getRoadVisibility());
      calibrationPointLayer.setVisible(isActiveLayer && applicationModel.getRoadVisibility());
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:deselectFeaturesSelected', function () {
      clearHighlights();
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:clearAndDisableInteractions', function () {
      clearHighlights();
      removeSelectInteractions();
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:enableInteractions', function () {
      addSelectInteractions();
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:clearOnClose', function () {
      clearHighlights();
      me.clearLayers(layers);
    });

    me.eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers(layers));

    me.eventListener.listenTo(eventbus, 'suravageProjectRoads:toggleVisibility', function (visibility) {
      suravageRoadProjectLayer.setVisible(visibility);
      suravageProjectDirectionMarkerLayer.setVisible(visibility);
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:visibilityChanged', function () {
      me.toggleLayersVisibility(layers, applicationModel.getRoadVisibility());
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:toggleEditingRoad', function (notEditingData) {
      isNotEditingData = notEditingData;
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:deactivateAllSelections', function () {
      toggleSelectInteractions(false, true);
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:startAllInteractions', function () {
      toggleSelectInteractions(true, true);
    });

    me.eventListener.listenTo(eventbus, 'split:cutPointFeature', function (cutGeom, terminatedLink) {
      suravageCutter.addCutLine(cutGeom);
      suravageCutter.addTerminatedFeature(terminatedLink);
    });

    me.toggleLayersVisibility(true);
    me.addLayers(layers);

    return {
      show: showLayer,
      hide: hideLayer,
      clearHighlights: clearHighlights
    };
  };

})(this);