(function (root) {
  root.ProjectLinkLayer = function (map, projectCollection, selectedProjectLinkProperty) {
    var layerName = 'roadAddressProject';
    Layer.call(this, map);
    var me = this;

    var Anomaly = LinkValues.Anomaly;
    var SideCode = LinkValues.SideCode;
    var RoadZIndex = LinkValues.RoadZIndex;
    var LinkStatus = LinkValues.LinkStatus;
    var RoadClass = LinkValues.RoadClass;
    var RoadLinkType = LinkValues.RoadLinkType;
    var ConstructionType = LinkValues.ConstructionType;
    var isNotEditingData = true;
    var isActiveLayer = false;

    var projectLinkStyler = new ProjectLinkStyler();

    var calibrationPointVector = new ol.source.Vector({});
    var underConstructionRoadVector = new ol.source.Vector({});
    var directionMarkerVector = new ol.source.Vector({});
    var underConstructionProjectDirectionMarkerVector = new ol.source.Vector({});
    var unAddressedRoadsRoadVector = new ol.source.Vector({});
    var projectLinkVector = new ol.source.Vector({});

    var calibrationPointLayer = new ol.layer.Vector({
      source: calibrationPointVector,
      name: 'calibrationPointLayer',
      zIndex: RoadZIndex.CalibrationPointLayer.value
    });

    function vectorLayerStyle(feature) {
      return [projectLinkStyler.getProjectBorderStyler(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)}), projectLinkStyler.getUnderConstructionStyler(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        projectLinkStyler.getProjectLinkStyler(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)})
      ];
    }

    function unAddressedVectorLayerStyle(feature) {
      return [projectLinkStyler.getUnderConstructionStyler(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        projectLinkStyler.getProjectLinkStyler(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)})
      ];
    }

    var underConstructionRoadProjectLayer = new ol.layer.Vector({
      source: underConstructionRoadVector,
      name: 'underConstructionRoadProjectLayer',
      style: vectorLayerStyle,
      zIndex: RoadZIndex.UnderConstructionLayer.value
    });
    var unAddressedRoadsProjectLayer = new ol.layer.Vector({
      source: unAddressedRoadsRoadVector,
      name: 'unAddressedRoadsProjectLayer',
      style: unAddressedVectorLayerStyle,
      zIndex: RoadZIndex.unAddressedRoadsLayer.value
    });

    var projectLinkLayer = new ol.layer.Vector({
      source: projectLinkVector,
      name: layerName,
      style: vectorLayerStyle,
      zIndex: RoadZIndex.VectorLayer.value
    });

    var directionMarkerLayer = new ol.layer.Vector({
      source: directionMarkerVector,
      name: 'directionMarkerLayer',
      zIndex: RoadZIndex.DirectionMarkerLayer.value
    });

    var underConstructionProjectDirectionMarkerLayer = new ol.layer.Vector({
      source: underConstructionProjectDirectionMarkerVector,
      name: 'underConstructionProjectDirectionMarkerLayer',
      zIndex: RoadZIndex.DirectionMarkerLayer.value
    });

    var layers = [projectLinkLayer, calibrationPointLayer, directionMarkerLayer, underConstructionRoadProjectLayer, underConstructionProjectDirectionMarkerLayer, unAddressedRoadsProjectLayer];

    var getSelectedId = function (selected) {
      if (!_.isUndefined(selected.id) && selected.id > 0) {
        return selected.id;
      } else {
        return selected.linkId;
      }
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
      layer: [projectLinkLayer, underConstructionRoadProjectLayer, unAddressedRoadsProjectLayer],
      condition: ol.events.condition.singleClick,
      style: function (feature) {
        if (feature.linkData) {
          if (projectLinkStatusIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value ||
            feature.linkData.constructionType === ConstructionType.UnderConstruction.value) {
            return projectLinkStyler.getSelectionLinkStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)});
          }
        }
        return null;
      }
    });

    selectSingleClick.set('name', 'selectSingleClickInteractionPLL');

    selectSingleClick.on('select', function (event) {
      var ctrlPressed = (event.mapBrowserEvent) ? event.mapBrowserEvent.originalEvent.ctrlKey : false;
      removeCutterMarkers();
      var rawSelection = (event.mapBrowserEvent) ? map.forEachFeatureAtPixel(event.mapBrowserEvent.pixel, function (feature) {
        return feature;
      }) : event.selected;
      var selection = _.find(ctrlPressed ? [rawSelection] : [rawSelection].concat(selectSingleClick.getFeatures().getArray()), function (selectionTarget) {
        if (selectionTarget)
          return !_.isUndefined(selectionTarget.linkData) && (
            projectLinkStatusIn(selectionTarget.linkData, possibleStatusForSelection) || selectionTarget.linkData.roadClass === RoadClass.NoClass.value);
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
          if (_.includes(selectedLinkIds, getSelectedId(selection.linkData))) {
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
        selectedProjectLinkProperty.open(getSelectedId(selection.linkData), true);
      } else {
        eventbus.trigger('roadAddressProject:discardChanges'); // Background map was clicked so discard changes
      }
    };

    var selectDoubleClick = new ol.interaction.Select({
      layer: [projectLinkLayer, underConstructionRoadProjectLayer, unAddressedRoadsProjectLayer],
      condition: ol.events.condition.doubleClick,
      style: function (feature) {
        if (projectLinkStatusIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value ||
          feature.linkData.constructionType === ConstructionType.UnderConstruction.value) {
          return projectLinkStyler.getSelectionLinkStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)});
        }
        return null;
      }
    });

    selectDoubleClick.set('name', 'selectDoubleClickInteractionPLL');

    selectDoubleClick.on('select', function (event) {
      var ctrlPressed = event.mapBrowserEvent.originalEvent.ctrlKey;
      var selection = _.find(event.selected, function (selectionTarget) {
        return (applicationModel.getSelectedTool() !== 'Cut' && !_.isUndefined(selectionTarget.linkData) && (
            projectLinkStatusIn(selectionTarget.linkData, possibleStatusForSelection) ||
            selectionTarget.linkData.anomaly === Anomaly.NoAddressGiven.value ||
            selectionTarget.linkData.roadClass === RoadClass.NoClass.value ||
            (selectionTarget.linkData.constructionType === ConstructionType.UnderConstruction.value && selectionTarget.linkData.anomaly === Anomaly.NoAddressGiven.value))
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
          if (_.includes(selectedLinkIds, getSelectedId(selection.linkData))) {
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
        selectedProjectLinkProperty.open(getSelectedId(selection.linkData));
      }
    };

    //Add defined interactions to the map.
    map.addInteraction(selectSingleClick);
    map.addInteraction(selectDoubleClick);

    var canBeAddedToSelection = function (selectionData) {
      if (selectedProjectLinkProperty.get().length === 0) {
        return true;
      }
      var currentlySelectedSample = _.head(selectedProjectLinkProperty.get());
      return selectionData.roadNumber === currentlySelectedSample.roadNumber &&
        selectionData.roadPartNumber === currentlySelectedSample.roadPartNumber &&
        selectionData.trackCode === currentlySelectedSample.trackCode &&
        selectionData.administrativeClassId === currentlySelectedSample.administrativeClassId &&
        selectionData.elyCode === currentlySelectedSample.elyCode;
    };

    var highlightFeatures = function () {
      clearHighlights();
      var featuresToHighlight = [];
      _.each(projectLinkVector.getFeatures().concat(underConstructionRoadProjectLayer.getSource().getFeatures()).concat(unAddressedRoadsProjectLayer.getSource().getFeatures()), function (feature) {
        var canIHighlight = (!_.isUndefined(feature.linkData.linkId) || feature.linkData.status === LinkStatus.Terminated.value
          ? selectedProjectLinkProperty.isSelected(getSelectedId(feature.linkData)) : false);
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
     * @param features
     */
    var addFeaturesToSelection = function (features) {
      var olUids = _.map(selectSingleClick.getFeatures().getArray(), function (feature) {
        return feature.ol_uid;
      });
      _.each(features, function (feature) {
        if (!_.includes(olUids, feature.ol_uid)) {
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
    if (window.getSelection) {
      window.getSelection().removeAllRanges();
    } //removes selection from forms
    else if (document.selection) {
      document.selection.empty();
    }
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
      eventbus.trigger('overlay:update', event, pixel);
    });

    var showLayer = function () {
      me.start();
    };

    var hideLayer = function () {
      me.clearLayers(layers);
    };

    var clearHighlights = function () {
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
      _.each(selectSingleClick.getFeatures().getArray(), function (feature) {
        if (feature.getProperties().type === 'cutter')
          featuresToRemove.push(feature);
      });
      _.each(featuresToRemove, function (ft) {
        selectSingleClick.getFeatures().remove(ft);
      });
    };

    var projectLinkStatusIn = function (projectLink, possibleStatus) {
      if (!_.isUndefined(possibleStatus) && !_.isUndefined(projectLink))
        return _.includes(possibleStatus, projectLink.status);
      else return false;
    };

    var changeTool = function (tool) {
      if (tool === 'Cut') {
        selectSingleClick.setActive(false);
      } else if (tool === 'Select') {
        selectSingleClick.setActive(true);
      }
    };

    me.eventListener.listenTo(eventbus, 'projectLink:projectLinksCreateSuccess', function () {
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.eventListener.listenTo(eventbus, 'changeProjectDirection:clicked', function () {
      projectLinkVector.clear();
      directionMarkerLayer.getSource().clear();
      me.eventListener.listenToOnce(eventbus, 'roadAddressProject:fetched', function () {
        selectedProjectLinkProperty.open(getSelectedId(selectedProjectLinkProperty.get()[0]), selectedProjectLinkProperty.isMultiLink());
      });
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.eventListener.listenTo(eventbus, 'split:splitCutLine', function (cutGeom) {
      addCutLine(cutGeom);
      applicationModel.removeSpinner();
    });

    me.eventListener.listenTo(eventbus, 'projectLink:revertedChanges', function (data) {
      isNotEditingData = true;
      selectedProjectLinkProperty.setDirty(false);
      eventbus.trigger('roadAddress:projectLinksUpdated', data);
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.redraw = function () {
      me.clearLayers(layers);
      var cachedMarker = new ProjectLinkMarker(selectedProjectLinkProperty);

      var editedLinks = _.map(projectCollection.getDirty(), function (editedLink) {
        return editedLink;
      });

      var unaddressedProjectRoads = _.partition(projectCollection.getAll(), function (projectRoad) {
        return projectRoad.roadNumber === 0;
      });
      var underConstructionAndUnknownProjectRoads = _.partition(unaddressedProjectRoads[0], function (projectRoad) {
        return projectRoad.constructionType === ConstructionType.UnderConstruction.value;
      });

      var unknownProjectRoads = underConstructionAndUnknownProjectRoads[1];
      var underConstructionProjectRoads = underConstructionAndUnknownProjectRoads[0];

      _.map(underConstructionProjectRoads, function (projectLink) {
        var points = _.map(projectLink.points, function (point) {
          return [point.x, point.y];
        });
        var feature = new ol.Feature({
          geometry: new ol.geom.LineString(points)
        });
        feature.linkData = projectLink;
        underConstructionRoadProjectLayer.getSource().addFeatures([feature]);
      });

      _.map(unknownProjectRoads, function (projectLink) {
        var points = _.map(projectLink.points, function (point) {
          return [point.x, point.y];
        });
        var feature = new ol.Feature({
          geometry: new ol.geom.LineString(points)
        });
        feature.linkData = projectLink;
        unAddressedRoadsProjectLayer.getSource().addFeatures([feature]);
      });

      var projectLinks = unaddressedProjectRoads[1];
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

      var removeSelectFeatures = function (select) {
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
        var addMarkersToLayer = function (links, layer) {
          var directionMarkers = _.filter(links, function (projectLink) {
            var acceptedLinks = projectLink.id !== 0 || (projectLink.id === 0 && (projectLink.anomaly === Anomaly.NoAddressGiven.value || projectLink.roadLinkType === RoadLinkType.FloatingRoadLinkType.value));
            return acceptedLinks && projectLink.sideCode !== SideCode.Unknown.value && projectLink.endAddressM !== 0;
          });
          _.each(directionMarkers, function (directionLink) {
            cachedMarker.createProjectMarker(directionLink, function (marker) {
              layer.getSource().addFeature(marker);
            });
          });
        };
        addMarkersToLayer(underConstructionProjectRoads, underConstructionProjectDirectionMarkerLayer);
        addMarkersToLayer(projectLinks, directionMarkerLayer);
      }

      if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomLevelForCalibrationPoints) {
        var actualCalibrationPoints = me.drawProjectCalibrationMarkers(calibrationPointLayer.source, projectLinks.concat(underConstructionProjectRoads));
        _.each(actualCalibrationPoints, function (actualPoint) {
          var calMarker = new CalibrationPoint(actualPoint);
          calibrationPointLayer.getSource().addFeature(calMarker.getMarker(true));
        });
      }

      var partitioned = _.partition(features, function (feature) {
        return (!_.isUndefined(feature.linkData.linkId) && _.includes(_.map(editedLinks, 'id'), feature.linkData.linkId));
      });

      var toBeTerminated = _.filter(editedLinks, function (link) {
        return link.status === LinkStatus.Terminated.value;
      });
      features = [];
      _.each(partitioned[0], function (feature) {
        if (_.includes(_.map(toBeTerminated, 'id'), feature.linkData.linkId)) {
          feature.linkData.status = LinkStatus.Terminated.value;
          var termination = projectLinkStyler.getProjectLinkStyler(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)});
          feature.setStyle(termination);
          features.push(feature);
        }
      });

      if (features.length !== 0)
        addFeaturesToSelection(features);
      features = features.concat(partitioned[1]);
      projectLinkVector.clear(true);
      projectLinkVector.addFeatures(features);
      projectLinkLayer.changed();
    };

    me.eventListener.listenTo(eventbus, 'tool:changed', changeTool);

    me.eventListener.listenTo(eventbus, 'roadAddressProject:openProject', function (projectSelected) {
      this.project = projectSelected;
      eventbus.trigger('layer:enableButtons', false);
      eventbus.trigger('roadAddressProject:selected', projectSelected.id, layerName, applicationModel.getSelectedLayer());
      applicationModel.selectLayer(layerName);
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:selected', function (projId) {
      me.eventListener.listenToOnce(eventbus, 'roadAddressProject:projectFetched', function (projectInfo) {
        projectCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map), projectInfo.id, projectInfo.publishable);
      });
      projectCollection.getProjectsWithLinksById(projId);
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:fetch', function () {
      var projectId = _.isUndefined(projectCollection.getCurrentProject()) ? undefined : projectCollection.getCurrentProject().project.id;
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, projectId, projectCollection.getPublishableStatus());
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:fetched', function () {
      me.redraw();
      _.defer(function () {
        highlightFeatures();
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

    me.eventListener.listenTo(eventbus, 'underConstructionProjectRoads:toggleVisibility', function (visibility) {
      underConstructionRoadProjectLayer.setVisible(visibility);
      underConstructionProjectDirectionMarkerLayer.setVisible(visibility);
    });
    me.eventListener.listenTo(eventbus, 'unAddressedProjectRoads:toggleVisibility', function (visibility) {
      unAddressedRoadsProjectLayer.setVisible(visibility);
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:visibilityChanged', function () {
      me.toggleLayersVisibility([projectLinkLayer, calibrationPointLayer, directionMarkerLayer], applicationModel.getRoadVisibility());
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

    me.toggleLayersVisibility(true);
    me.addLayers(layers);

    return {
      show: showLayer,
      hide: hideLayer,
      clearHighlights: clearHighlights
    };
  };

}(this));
