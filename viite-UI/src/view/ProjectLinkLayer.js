(function (root) {
  root.ProjectLinkLayer = function (map, projectCollection, selectedProjectLinkProperty) {
    var layerName = 'roadAddressProject';
    Layer.call(this, map);
    var me = this;

    var SideCode = ViiteEnumerations.SideCode;
    var LinkStatus = ViiteEnumerations.LinkStatus;
    var RoadClass = ViiteEnumerations.RoadClass;
    var lifecycleStatus = ViiteEnumerations.lifecycleStatus;
    var isNotEditingData = true;
    var isActiveLayer = false;

    var projectLinkStyler = new ProjectLinkStyler();

    var calibrationPointVector = new ol.source.Vector({});
    var underConstructionRoadVector = new ol.source.Vector({});
    var directionMarkerVector = new ol.source.Vector({});
    var unAddressedRoadsRoadVector = new ol.source.Vector({});
    var projectLinkVector = new ol.source.Vector({});
    var notReservedInProjectVector = new ol.source.Vector({});
    var notHandledProjectLinkVector = new ol.source.Vector({});
    var terminatedProjectLinkVector = new ol.source.Vector({});

    var calibrationPointLayer = new ol.layer.Vector({
      source: calibrationPointVector,
      name: 'calibrationPointLayer',
      zIndex: ViiteEnumerations.ProjectModeZIndex.CalibrationPoint.value
    });

    var terminatedProjectLinkLayer = new ol.layer.Vector({
      source: terminatedProjectLinkVector,
      name: 'terminatedProjectLinkLayer',
      style: function (feature) {
        return projectLinkStyler.getTerminatedProjectLinksStyle(feature.linkData, map);
      }
    });

    var notHandledProjectLinksLayer = new ol.layer.Vector({
      source: notHandledProjectLinkVector,
      name: 'notHandledProjectLinksLayer',
      style: function (feature) {
        return projectLinkStyler.getNotHandledProjectLinksStyle(feature.linkData, map);
      }
    });

    var notReservedInProjectLayer = new ol.layer.Vector({
      source: notReservedInProjectVector,
      name: 'notReservedInProjectLayer',
      opacity: 0.4,
      style: function (feature) {
        return projectLinkStyler.getNotInProjectStyles(feature.linkData, map);
      }
    });

    var underConstructionRoadProjectLayer = new ol.layer.Vector({
      source: underConstructionRoadVector,
      name: 'underConstructionRoadProjectLayer',
      style: function (feature) {
       return projectLinkStyler.getUnderConstructionStyles(feature.linkData, map);
      }
    });
    var unAddressedRoadsProjectLayer = new ol.layer.Vector({
      source: unAddressedRoadsRoadVector,
      name: 'unAddressedRoadsProjectLayer',
      style: function (feature) {
        return projectLinkStyler.getUnAddressedStyles(feature.linkData, map);
      }
    });

    var projectLinkLayer = new ol.layer.Vector({
      source: projectLinkVector,
      name: layerName,
      style: function (feature) {
      return projectLinkStyler.getProjectLinkStyles(feature.linkData, map);
      }
    });

    var directionMarkerLayer = new ol.layer.Vector({
      source: directionMarkerVector,
      name: 'directionMarkerLayer',
      zIndex: ViiteEnumerations.ProjectModeZIndex.DirectionMarker.value
    });

    var layers = [notReservedInProjectLayer, terminatedProjectLinkLayer, unAddressedRoadsProjectLayer, underConstructionRoadProjectLayer, projectLinkLayer, notHandledProjectLinksLayer, calibrationPointLayer, directionMarkerLayer];

    me.eventListener.listenTo(eventbus,'layers:removeProjectModeFeaturesFromTheLayers', function() {
      me.removeFeaturesFromLayers(layers);
    });

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
      layer: [projectLinkLayer, underConstructionRoadProjectLayer, unAddressedRoadsProjectLayer, notHandledProjectLinksLayer, terminatedProjectLinkLayer],
      condition: ol.events.condition.singleClick,
      style: function (feature) {
        if (feature.linkData) {
          if (projectLinkStatusIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value ||
            feature.linkData.lifecycleStatus === lifecycleStatus.UnderConstruction.value) {
            return projectLinkStyler.getSelectionLinkStyle(feature.linkData, map);
          }
        }
        return null;
      }
    });

    selectSingleClick.set('name', 'selectSingleClickInteractionPLL');

    selectSingleClick.on('select', function (event) {
      var ctrlPressed = (event.mapBrowserEvent) ? event.mapBrowserEvent.originalEvent.ctrlKey : false;
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
      layer: [projectLinkLayer, underConstructionRoadProjectLayer, unAddressedRoadsProjectLayer, terminatedProjectLinkLayer, notHandledProjectLinksLayer],
      condition: ol.events.condition.doubleClick,
      style: function (feature) {
        if (projectLinkStatusIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value ||
          feature.linkData.lifecycleStatus === lifecycleStatus.UnderConstruction.value) {
          return projectLinkStyler.getSelectionLinkStyle(feature.linkData, map);
        }
        return null;
      }
    });

    selectDoubleClick.set('name', 'selectDoubleClickInteractionPLL');

    selectDoubleClick.on('select', function (event) {
      var ctrlPressed = event.mapBrowserEvent.originalEvent.ctrlKey;
      var selection = _.find(event.selected, function (selectionTarget) {
        return (!_.isUndefined(selectionTarget.linkData) && (
            projectLinkStatusIn(selectionTarget.linkData, possibleStatusForSelection) ||
            selectionTarget.linkData.roadClass === RoadClass.NoClass.value ||
            selectionTarget.linkData.lifecycleStatus === lifecycleStatus.UnderConstruction.value)
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
        (selectionData.administrativeClassId === currentlySelectedSample.administrativeClassId || selectionData.roadNumber === 0) && // unaddressed road can be added to selection even if their administrative class don't match
        selectionData.elyCode === currentlySelectedSample.elyCode;
    };

    var highlightFeatures = function () {
      clearHighlights();
      var featuresToHighlight = [];
      _.each(projectLinkLayer.getSource().getFeatures()
          .concat(underConstructionRoadProjectLayer.getSource().getFeatures())
          .concat(unAddressedRoadsProjectLayer.getSource().getFeatures())
          .concat(notHandledProjectLinksLayer.getSource().getFeatures())
          .concat(terminatedProjectLinkLayer.getSource().getFeatures()), function (feature) {
        var canIHighlight = (!_.isUndefined(feature.linkData.linkId) || feature.linkData.status === LinkStatus.Terminated.value
          ? selectedProjectLinkProperty.isSelected(getSelectedId(feature.linkData)) : false);
        if (canIHighlight) {
          featuresToHighlight.push(feature);
        }
      });
      addFeaturesToSelection(featuresToHighlight);
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

    me.eventListener.listenTo(eventbus, 'projectLink:clicked projectLink:errorClicked', function () {
      highlightFeatures();
    });

    var zoomDoubleClickListener = function (event) {
      if (isActiveLayer) {
        _.defer(function () {
          if (!event.originalEvent.ctrlKey &&
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
      removeSelectInteractions();
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


    var projectLinkStatusIn = function (projectLink, possibleStatus) {
      if (!_.isUndefined(possibleStatus) && !_.isUndefined(projectLink))
        return _.includes(possibleStatus, projectLink.status);
      else return false;
    };

    var changeTool = function (tool) {
      if (tool === 'Select') {
        selectSingleClick.setActive(true);
      }
    };

    me.eventListener.listenTo(eventbus, 'projectLink:projectLinksCreateSuccess', function () {
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.eventListener.listenTo(eventbus, 'changeProjectDirection:clicked', function () {
      projectLinkLayer.getSource().clear();
      directionMarkerLayer.getSource().clear();
      me.eventListener.listenToOnce(eventbus, 'roadAddressProject:fetched', function () {
        selectedProjectLinkProperty.open(getSelectedId(selectedProjectLinkProperty.get()[0]), selectedProjectLinkProperty.isMultiLink());
      });
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    me.eventListener.listenTo(eventbus, 'projectLink:revertedChanges', function (response) {
      isNotEditingData = true;
      selectedProjectLinkProperty.setDirty(false);
      eventbus.trigger('roadAddress:projectLinksUpdated', response);
      projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, undefined, projectCollection.getPublishableStatus());
    });

    /**
     * This function is responsible for adding features to the correct layers that they belong to.
     * */
    me.redraw = function () {
      var addLinkFeaturesToLayer = function (links, destinationLayer) {
        _.map(links, function (link) {
          var points = _.map(link.points, function (point) {
            return [point.x, point.y];
          });
          var feature = new ol.Feature({
            geometry: new ol.geom.LineString(points)
          });
          feature.linkData = link;
          destinationLayer.getSource().addFeatures([feature]);
        });
      };

      me.clearLayers(layers);
      removeSelectInteractions();
      var cachedMarker = new ProjectLinkMarker(selectedProjectLinkProperty);

      var [linksWithNoRoadNumber, linksWithRoadNumber] = _.partition(projectCollection.getAll(), function (projectRoad) {
        return projectRoad.roadNumber === 0;
      });

      var [underConstruction, unAddressed] = _.partition(linksWithNoRoadNumber, function (projectRoad) {
        return projectRoad.lifecycleStatus === lifecycleStatus.UnderConstruction.value;
      });

      // get the links that are not in the project
      var [outsideOfProjectLinks, inProjectWithRoadNumberLinks] = _.partition(linksWithRoadNumber, function (link) {
        return link.status === LinkStatus.Undefined.value;
      });

      var [notHandledLinks, othersInProject] = _.partition(inProjectWithRoadNumberLinks, function (link){
        return link.status === LinkStatus.NotHandled.value;
      });

      var [terminatedLinks, restOfProjectLinks] = _.partition(othersInProject, function (link) {
        return link.status === LinkStatus.Terminated.value;
      });

      // add under construction roads to correct layer
      addLinkFeaturesToLayer(underConstruction, underConstructionRoadProjectLayer);

      // add unaddressed roads to correct layer
      addLinkFeaturesToLayer(unAddressed, unAddressedRoadsProjectLayer);

      // add links that are not in the project to correct layer
      addLinkFeaturesToLayer(outsideOfProjectLinks, notReservedInProjectLayer);

      // add not handled project links to correct layer
      addLinkFeaturesToLayer(notHandledLinks, notHandledProjectLinksLayer);

      // add terminated project links to correct layer
      addLinkFeaturesToLayer(terminatedLinks, terminatedProjectLinkLayer);

      // add rest of the project links (transfer, new, numbering, unchanged) to correct layer
      addLinkFeaturesToLayer(restOfProjectLinks, projectLinkLayer);

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
            var acceptedLinks = projectLink.id !== 0;
            return acceptedLinks && projectLink.sideCode !== SideCode.Unknown.value && projectLink.endAddressM !== 0;
          });
          _.each(directionMarkers, function (directionLink) {
            cachedMarker.createProjectMarker(directionLink, function (marker) {
              layer.getSource().addFeature(marker);
            });
          });
        };
        addMarkersToLayer(linksWithRoadNumber, directionMarkerLayer);
      }

      if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomLevelForCalibrationPoints) {
        var actualCalibrationPoints = me.drawProjectCalibrationMarkers(calibrationPointLayer.source, linksWithRoadNumber.concat(underConstruction));
        _.each(actualCalibrationPoints, function (actualPoint) {
          var calMarker = new CalibrationPoint(actualPoint);
          calibrationPointLayer.getSource().addFeature(calMarker.getMarker(true));
        });
      }

      unAddressedRoadsProjectLayer.changed();
      terminatedProjectLinkLayer.changed();
      underConstructionRoadProjectLayer.changed();
      notReservedInProjectLayer.changed();
      notHandledProjectLinksLayer.changed();
      projectLinkLayer.changed();

      addSelectInteractions();
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
      eventbus.trigger('layers:removeViewModeFeaturesFromTheLayers'); // view mode features should not be shown to user in project mode
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
    });
    me.eventListener.listenTo(eventbus, 'unAddressedProjectRoads:toggleVisibility', function (visibility) {
      unAddressedRoadsProjectLayer.setVisible(visibility);
    });

    me.eventListener.listenTo(eventbus, 'roadAddressProject:visibilityChanged', function () {
      me.toggleLayersVisibility([projectLinkLayer, calibrationPointLayer, directionMarkerLayer, notHandledProjectLinksLayer, terminatedProjectLinkLayer, notReservedInProjectLayer], applicationModel.getRoadVisibility());
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
