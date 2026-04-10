/**
 * LinkPropertyLayer component
 * Manages the vector layer for displaying link properties in view mode with selection and styling.
 * Handles link selection, reserved roads highlighting, and calibration points.
 * @param {Object} map - OpenLayers map instance
 * @param {Object} roadLayer - Road layer reference
 * @param {Object} selectedLinkProperty - Selected link property manager
 * @param {Object} roadCollection - Road collection manager
 * @param {Object} applicationModel - Application model
 * @returns {Object} Layer with methods for refresh, dirty check, and view management
 */
import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { RoadLinkStyler } from '@view/map/RoadLinkStyler.js';
import { Layer } from './Layer.js';
import { LinkPropertyMarker } from '../markers/LinkPropertyMarker.js';
import { CalibrationPoint } from '../markers/CalibrationPointMarker.js';

export function LinkPropertyLayer(map, roadLayer, selectedLinkProperty, roadCollection, applicationModel) {
    Layer.call(this, map);
    const me = this;

    const directionMarkerVector = new ol.source.Vector({});
    const selectedDirectionMarkerVector = new ol.source.Vector({});
    const calibrationPointVector = new ol.source.Vector({});
    const underConstructionRoadLayerVector = new ol.source.Vector({});
    const unAddressedRoadLayerVector = new ol.source.Vector({});
    const reservedRoadVector = new ol.source.Vector({});
    const selectedRoadVector = new ol.source.Vector({});
    const SelectionType = ViiteEnumerations.SelectionType;
    let isActiveLayer = false;
    let cachedMarker = null;

    const roadLinkStyler = new RoadLinkStyler();

    const directionMarkerLayer = new ol.layer.Vector({
      source: directionMarkerVector,
      name: 'directionMarkerLayer',
      zIndex: ViiteEnumerations.ViewModeZIndex.DirectionMarker.value
    });
    directionMarkerLayer.set('name', 'directionMarkerLayer');

    const calibrationPointLayer = new ol.layer.Vector({
      source: calibrationPointVector,
      name: 'calibrationPointLayer',
      zIndex: ViiteEnumerations.ViewModeZIndex.CalibrationPoint.value
    });
    calibrationPointLayer.set('name', 'calibrationPointLayer');


    const reservedRoadLayer = new ol.layer.Vector({
      source: reservedRoadVector,
      name: 'reservedRoadLayer',
      zIndex: ViiteEnumerations.ViewModeZIndex.ReservedRoad.value
    });
    reservedRoadLayer.set('name', 'reservedRoadLayer');

    const underConstructionRoadLayer = new ol.layer.Vector({
      source: underConstructionRoadLayerVector,
      name: 'underConstructionRoadLayer',
      style: function (feature) {
        return roadLinkStyler.getUnderConstructionStyles(feature.linkData, map);
      }
    });
    underConstructionRoadLayer.set('name', 'underConstructionRoadLayer');

    const unAddressedRoadLayer = new ol.layer.Vector({
      source: unAddressedRoadLayerVector,
      name: 'unAddressedRoadLayer',
      style: function (feature) {
        return roadLinkStyler.getUnAddressedStyles(feature.linkData, map);
      }
    });
    unAddressedRoadLayer.set('name', 'unAddressedRoadLayer');

    /**
     * When a road link is selected it will be added to this layer.
     * This layer will be drawn on top of all the other road link layers and the other layers will have dimmed opacity.
     * This will "highlight" the selected road link.
     * */
    const selectedRoadLayer = new ol.layer.Vector({
      source: selectedRoadVector,
      name: 'selectedRoadLayer',
      style: function (feature) {
        return getStyleForSelection(feature);
      }
    });

    /**
     * A selected road link has its own "selected" direction marker
     * (the other direction markers have dimmed opacity if they aren't selected)
     * */
    const selectedDirectionMarkerLayer = new ol.layer.Vector({
      source: selectedDirectionMarkerVector,
      name: 'selectedDirectionMarkerLayer',
      zIndex: ViiteEnumerations.ViewModeZIndex.DirectionMarker.value
    });
    selectedDirectionMarkerLayer.set('name', 'selectedDirectionMarkerLayer');

    /**
     * The order of these layers in this array affects the order these layers are presented on the map.
     * i.e. the first one is the bottom most layer drawn and the last one is the top most layer drawn
     * */
    const layers = [unAddressedRoadLayer, underConstructionRoadLayer, roadLayer.layer, reservedRoadLayer, selectedRoadLayer, directionMarkerLayer, selectedDirectionMarkerLayer, calibrationPointLayer];

    me.eventListener.listenTo(eventbus,'layers:removeViewModeFeaturesFromTheLayers', function() {
      me.removeFeaturesFromLayers(layers);
    });

    const setGeneralOpacity = function (opacity) {
      roadLayer.layer.setOpacity(opacity);
      directionMarkerLayer.setOpacity(opacity);
      underConstructionRoadLayer.setOpacity(opacity);
      unAddressedRoadLayer.setOpacity(opacity);
    };

    const getStyleForSelection = function (feature) {
      // for normal road links
      if (feature.linkData.roadClass !== ViiteEnumerations.RoadClass.NoClass.value) {
        return roadLinkStyler.getRoadLinkStyles(feature.linkData, map);
      }
      // for unaddressed road links
      else if (feature.linkData.roadClass === ViiteEnumerations.RoadClass.NoClass.value && feature.linkData.lifecycleStatus !== ViiteEnumerations.lifecycleStatus.UnderConstruction.value) {
        return roadLinkStyler.getUnAddressedStyles(feature.linkData, map);
      }
      // for under construction road links
      else {
        return roadLinkStyler.getUnderConstructionStyles(feature.linkData, map);
      }
    };

    /**
     * We declare the type of interaction we want the map to be able to respond.
     * A selected feature is moved to a new/temporary layer out of the default roadLayer.
     * This interaction is restricted to a double click.
     * @type {ol.interaction.Select}
     *
     *
     */
    const selectDoubleClick = new ol.interaction.Select({
      //Multi is the one en charge of defining if we select just the feature we clicked or all the overlapping
      //multi: true,
      //This will limit the interaction to the specific layer, in this case the layer where the roadAddressLinks are drawn
      layer: [roadLayer.layer, underConstructionRoadLayer, unAddressedRoadLayer],
      //Limit this interaction to the doubleClick
      condition: ol.events.condition.doubleClick,
      //The new/temporary layer needs to have a style function as well, we define it here.
      style: function (feature) {
        return roadLinkStyler.getRoadLinkStyles(feature.linkData, map);
      }
    });

    const getSelectedF = (ctrlPressed, event) => {
      // if ctrl is pressed, we return the raw selection so that we get the linkData we can add to the selection
      if (ctrlPressed) {
        return map.forEachFeatureAtPixel(event.mapBrowserEvent.pixel, function (feature) {
          return feature;
        });
      } else {
        // if not, then we want the selection to be undefined if we click a link that was already clicked
        // OR  if the link that is not already selected was clicked, we get linkData
        return _.find(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.linkData);
        });
      }
    };

    /**
     * We now declare what kind of custom actions we want when the interaction happens.
     * Note that 'select' is triggered when a feature is either selected or deselected.
     * The event holds the selected features in the events.selected and the deselected in event.deselected.
     */
    selectDoubleClick.on('select', function (event) {
      const visibleFeatures = getVisibleFeatures(true, true, true, true, true, true, true);
      selectSingleClick.getFeatures().clear();
      const ctrlPressed = (event.mapBrowserEvent) ? event.mapBrowserEvent.originalEvent.ctrlKey : false;

      selectDoubleClick.getFeatures().clear();

      // Since the selected features are moved to a new/temporary layer we just need to reduce the roadlayer's opacity levels.
      if (event.selected.length !== 0) {
        const selectedF = getSelectedF(ctrlPressed, event);
        if (roadLayer.layer.getOpacity() === 1) {
          setGeneralOpacity(0.2);
        }
        if (!_.isUndefined(selectedF)) {
          const selection = selectedF.linkData;
          if (ctrlPressed) { // if ctrl button was pressed while double clicking the link then we want to add the selected link to the selection
            modifyPreviousSelection(ctrlPressed, selection);
          } else { // otherwise we want to select just the double clicked link
            selectedLinkProperty.open(selection, false, visibleFeatures);
          }
        }
      }
      redraw();
    });
    selectDoubleClick.set('name', 'selectDoubleClickInteractionLPL');

    const zoomDoubleClickListener = function (_event) {
      if (isActiveLayer)
        _.defer(function () {
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
    const selectSingleClick = new ol.interaction.Select({
      //Multi is the one en charge of defining if we select just the feature we clicked or all the overlapping
      multi: true,
      //This will limit the interaction to the specific layer, in this case the layer where the roadAddressLinks are drawn
      layers: [roadLayer.layer, underConstructionRoadLayer, unAddressedRoadLayer],
      //Limit this interaction to the singleClick
      condition: ol.events.condition.singleClick,
      style: function (feature) {
        // for normal road links
        if (feature.linkData.roadClass !== ViiteEnumerations.RoadClass.NoClass.value) {
          return roadLinkStyler.getRoadLinkStyles(feature.linkData, map);
        }
        // for unaddressed road links
        else if (feature.linkData.roadClass === ViiteEnumerations.RoadClass.NoClass.value && feature.linkData.lifecycleStatus !== ViiteEnumerations.lifecycleStatus.UnderConstruction.value) {
          return roadLinkStyler.getUnAddressedStyles(feature.linkData, map);
        }
        // for under construction road links
        else {
          return roadLinkStyler.getUnderConstructionStyles(feature.linkData, map);
        }
      }
    });
    selectSingleClick.set('name', 'selectSingleClickInteractionLPL');


    /**
     * We now declare what kind of custom actions we want when the interaction happens.
     * Note that 'select' is triggered when a feature is either selected or deselected.
     * The event holds the selected features in the events.selected and the deselected in event.deselected.
     *
     * In this particular case we are fetching every roadLinkAddress in view and
     * sending them to the selectedLinkProperty.open for further processing,
     * or adding them to the selection if user pressed ctrl button while clicking.
     */
    selectSingleClick.on('select', function (event) {
      const ctrlPressed = (event.mapBrowserEvent) ? event.mapBrowserEvent.originalEvent.ctrlKey : false;
      const visibleFeatures = getVisibleFeatures(true, true, true, true, true, true, true);
      selectDoubleClick.getFeatures().clear();

      const selectedF = getSelectedF(ctrlPressed, event);

      if (selectedF) {
        const selection = selectedF.linkData;
        if (roadLayer.layer.getOpacity() === 1) {
          setGeneralOpacity(0.2);
        }
        if (ctrlPressed) {  // if ctrl button was pressed while single clicking then we want to add the clicked link to the previous selection
          modifyPreviousSelection(ctrlPressed, selection);
        } else { // otherwise we want to select the whole road part
          selectedLinkProperty.close();
          setGeneralOpacity(0.2);
          if (selection.roadNumber !== 0) {
            // set the clicked linear location id so we know what road link group to update after fetching road links in backend
            roadCollection.setClickedLinearLocationId(selection.linearLocationId);
            // gets all the road links from backend and starts a cycle that updates road link group in RoadCollection.js
            roadCollection.fetchWholeRoadPart(selection.roadNumber, selection.roadPartNumber, selection);
          }
          // opens only the visible parts of the roads (bounding box view)
          selectedLinkProperty.open(selection, true, visibleFeatures);
        }
      } else { // if selectedF was undefined we want to deselect all selected links
        selectedLinkProperty.close();
      }
    });

    // listens to the event when the road link group is updated (with whole road part) and then continues the process normally with the updated road link groups
    eventbus.listenTo(eventbus,'roadCollection:wholeRoadPartFetched', function (selection) {
      const features = getAllFeatures();
      selectedLinkProperty.open(selection, true, features);
    });

    map.on('click', function (event) {
      //The addition of the check for features on point and the selection mode
      // seem to fix the problem with the clicking on the empty map after being in the defloating process would allow a deselection and enabling of the menus
      if (window.getSelection) {
        window.getSelection().removeAllRanges();
      } //removes selection from forms
      else if (document.selection) {
        document.selection.empty();
      }
      const hasFeatureOnPoint = _.isUndefined(map.forEachFeatureAtPixel(event.pixel, function (feature) {
        return feature;
      }));
      const nonSpecialSelectionType = !_.includes(applicationModel.specialSelectionTypes, applicationModel.getSelectionType().value);
      if (isActiveLayer) {
        if (hasFeatureOnPoint && nonSpecialSelectionType) {
          selectedLinkProperty.close();
        }
      }
    });

    /**
     * Simple method that will add various open layers 3 features to a selection.
     * @param features
     */
    const addFeaturesToSelection = function (features) {
      const olUids = _.map(selectSingleClick.getFeatures().getArray(), function (feature) {
        return feature.ol_uid;
      });
      _.each(features, function (feature) {
        if (!_.includes(olUids, feature.ol_uid)) {
          selectSingleClick.getFeatures().push(feature);
          olUids.push(feature.ol_uid); // prevent adding duplicate entries
        }
      });
    };

    /**
     * Add/remove ctrl clicked link's:
     * linearLocationId to/from a list of previously selected linearLocationIds, if the clicked link has an address
     * OR
     * linkId to/from a list of previously selected linkIds, if the clicked link is unaddressed
     *
     * There are 2 reasons why selections are divided into linkIds and linearLocationIds:
     * 1) a link with an address might have a "shared" linkId with another link
     *    and clicking one of those links would select/deselect both of those links.
     *
     * 2) unaddressed links all have linearLocationId set to 0 (zero).
     *    So only using linearLocationId would select/deselect all of them
     *
     * So to counter those points:
     * - Links with an address are kept track of with a list of linearLocationIds,
     * - Unaddressed links are kept track of with a list of linkIds.
     *
     * These two modified lists are then passed on to a function called openCtrl
     * @param ctrlPressed - boolean
     * @param selection - link data of the clicked link
     *
     * */
    function modifyPreviousSelection(ctrlPressed, selection) {
      const modifiedList = function (listOfIds, id) {
        if (_.includes(listOfIds, id)) {
          return _.without(listOfIds, id);
        } else {
          return listOfIds.concat(id);
        }
      };
      if (ctrlPressed && !_.isUndefined(selectedLinkProperty.get()) && !_.isUndefined(selection)) {

        const [selectedWithAddress, selectedUnaddressed] = _.partition(selectedLinkProperty.get(), function (selected) {
          return selected.linearLocationId !== 0;
        });

        let selectedLinearLocationIds = _.map(selectedWithAddress, function (selected) {
          return selected.linearLocationId;
        });

        let selectedLinkIds = _.map(selectedUnaddressed, function (selected) {
          return selected.linkId;
        });

        if (selection.linearLocationId === 0) {
          selectedLinkIds = modifiedList(selectedLinkIds, selection.linkId);
        } else {
          selectedLinearLocationIds = modifiedList(selectedLinearLocationIds, selection.linearLocationId);
        }

        if (selectedLinearLocationIds.length === 0 && selectedLinkIds.length === 0) {
          // if both lists are empty then the last selected link was "deselected" and we want the UI to behave like no links are currently selected
          selectedLinkProperty.close();
        } else {
          const features = getAllFeatures();
          // pass the lists to further processing
          selectedLinkProperty.openCtrl(selectedLinearLocationIds, selectedLinkIds, true, features);
        }
      }
    }

    /**
     * Event triggered by the selectedLinkProperty.open() returning all the open layers 3 features
     * that need to be included in the selection.
     */
    me.eventListener.listenTo(eventbus, 'linkProperties:olSelected', function (features) {
      clearHighlights();
      addFeaturesToSelection(features);
    });

    function getVisibleFeatures(withRoads, withDirectionalMarkers, withUnderConstructionRoads, withVisibleUnAddressedRoads) {
      const extent = map.getView().calculateExtent(map.getSize());
      const visibleRoads = withRoads ? roadLayer.layer.getSource().getFeaturesInExtent(extent) : [];
      const visibleDirectionalMarkers = withDirectionalMarkers ? directionMarkerLayer.getSource().getFeaturesInExtent(extent) : [];
      const visibleUnderConstructionRoads = withUnderConstructionRoads ? underConstructionRoadLayer.getSource().getFeaturesInExtent(extent) : [];
      const visibleUnAddressedRoads = withVisibleUnAddressedRoads ? unAddressedRoadLayer.getSource().getFeaturesInExtent(extent) : [];
      return visibleRoads.concat(visibleDirectionalMarkers).concat(visibleUnderConstructionRoads).concat(visibleUnAddressedRoads);
    }

    function getAllFeatures() {
      const roads = roadLayer.layer.getSource().getFeatures();
      const directionalMarkers = directionMarkerLayer.getSource().getFeatures();
      const underConstructionRoads = underConstructionRoadLayer.getSource().getFeatures();
      const unAddressedRoads = unAddressedRoadLayer.getSource().getFeatures();
      return roads.concat(directionalMarkers).concat(underConstructionRoads).concat(unAddressedRoads);
    }

    /**
     * This will add all the following interactions to the map:
     * -selectDoubleClick
     * -selectSingleClick
     */
    const addSelectInteractions = function () {
      removeSelectInteractions();
      map.addInteraction(selectDoubleClick);
      map.addInteraction(selectSingleClick);
    };

    /**
     * This will remove all the following interactions from the map:
     * -selectDoubleClick
     * -selectSingleClick
     */
    function removeSelectInteractions() {
      map.removeInteraction(selectDoubleClick);
      map.removeInteraction(selectSingleClick);
    }

    //We add the defined interactions to the map.
    addSelectInteractions();

    function redraw() {
      const addLinkFeaturesToLayer = function (links, destinationLayer) {
        _.map(links, function (link) {
          const points = _.map(link.points, function (point) {
            return [point.x, point.y];
          });
          const feature = new ol.Feature({
            geometry: new ol.geom.LineString(points)
          });
          feature.linkData = link;
          destinationLayer.getSource().addFeatures([feature]);
        });
      };

      cachedMarker = new LinkPropertyMarker(selectedLinkProperty);
      removeSelectInteractions();
      me.clearLayers([roadLayer.layer, underConstructionRoadLayer, unAddressedRoadLayer, directionMarkerLayer, selectedDirectionMarkerLayer, calibrationPointLayer, selectedRoadLayer]);

      const allRoadLinks = roadCollection.getAll();
      const [roadLinksWithoutRoadNumber, roadLinksWithRoadNumber] = _.partition(allRoadLinks, function (roadLink) {
        return roadLink.roadNumber === 0;
      });
      const [underConstruction, unAddressed] = _.partition(roadLinksWithoutRoadNumber, function (roadLink) {
        return roadLink.lifecycleStatus === ViiteEnumerations.lifecycleStatus.UnderConstruction.value;
      });

      const selectedLinks = selectedLinkProperty.get();

      // add road links to correct layer
      addLinkFeaturesToLayer(roadLinksWithRoadNumber, roadLayer.layer);

      // add under construction links to correct layer
      addLinkFeaturesToLayer(underConstruction, underConstructionRoadLayer);

      // add unAddressed links to correct layer
      addLinkFeaturesToLayer(unAddressed, unAddressedRoadLayer);

      // add selected links to correct layer
      addLinkFeaturesToLayer(selectedLinks, selectedRoadLayer);

      const roadLinks = _.reject(allRoadLinks, function (rl) {
        return _.includes(_.map(underConstruction, function (sl) {
          return sl.linkId;
        }), rl.linkId);
      });

      if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForRoadNetwork) {
        if (zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers) {
          _.each(roadLinksWithRoadNumber, function (directionLink) {
            cachedMarker.createMarker(directionLink, function (marker) {
              directionMarkerLayer.getSource().addFeature(marker);
            });
          });

          // add direction markers for selected links
          _.each(selectedLinks, function (directionLink) {
            cachedMarker.createMarker(directionLink, function (marker) {
              selectedDirectionMarkerLayer.getSource().addFeature(marker);
            });
          });
        }
        // Draw calibration points in view mode only
        if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomLevelForCalibrationPoints && applicationModel.getSelectedLayer() === 'linkProperty') {
          const actualPoints = me.drawCalibrationMarkers(calibrationPointLayer.source, roadLinks);
          _.each(actualPoints, function (actualPoint) {
            const calMarker = new CalibrationPoint(actualPoint);
            calibrationPointLayer.getSource().addFeature(calMarker.getMarker(true));
          });
        }
      }
      addSelectInteractions();
    }

    this.refreshView = function () {
      //Generalize the zoom levels as the resolutions and zoom levels differ between map tile sources
      roadCollection.reset();
      roadCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
      underConstructionRoadLayer.changed();
      unAddressedRoadLayer.changed();
      roadLayer.layer.changed();
    };

    this.isDirty = function () {
      return selectedLinkProperty.isDirty();
    };

    const handleLinkPropertyChanged = function (eventListener) {
      removeSelectInteractions();
      eventListener.stopListening(eventbus, 'map:clicked', me.displayConfirmMessage);
      eventListener.listenTo(eventbus, 'map:clicked', me.displayConfirmMessage);
    };

    const concludeLinkPropertyEdit = function (eventListener) {
      addSelectInteractions();
      eventListener.stopListening(eventbus, 'map:clicked', me.displayConfirmMessage);
      setGeneralOpacity(1);
      if (selectDoubleClick.getFeatures().getLength() !== 0) {
        selectDoubleClick.getFeatures().clear();
      }
    };

    eventbus.listenTo(eventbus, 'linkProperties:selected linkProperties:unselected roadLinks:fetched', function() {
      if (applicationModel.getSelectedLayer() === 'linkProperty' || applicationModel.getSelectedLayer() === 'node') {
        redraw();
      }
    });

    this.layerStarted = function (eventListener) {
      const linkPropertyChangeHandler = _.partial(handleLinkPropertyChanged, eventListener);
      const linkPropertyEditConclusion = _.partial(concludeLinkPropertyEdit, eventListener);
      eventListener.listenTo(eventbus, 'linkProperties:changed', linkPropertyChangeHandler);
      eventListener.listenTo(eventbus, 'linkProperties:cancelled linkProperties:saved', linkPropertyEditConclusion);

      eventListener.listenTo(eventbus, 'linkProperties:selected linkProperties:multiSelected', function (link) {
        let selectedLink = link;
        if (link) {
          selectedLink = (_.isArray(link)) ? link : [link];
        }
        const roads = roadLayer.layer.getSource().getFeatures();
        const features = [];
        _.each(selectedLink, function (featureLink) {
          if (selectedLinkProperty.canOpenByLinearLocationId(featureLink.linearLocationId)) {
            _.each(roads, function (feature) {
              if (_.includes(featureLink.selectedLinks, feature.linkData.linearLocationId))
                return features.push(feature);
              return features;
            });
          } else if (featureLink.linkId !== 0) {
            _.each(roads, function (feature) {
              if (_.includes(featureLink.selectedLinks, feature.linkData.linkId))
                return features.push(feature);
              return features;
            });
          }
        });
        if (features) {
          addFeaturesToSelection(features);
        }
      });
      eventListener.listenTo(eventbus, 'unAddressedRoads:toggleVisibility', function (visibility) {
        unAddressedRoadLayer.setVisible(visibility);
      });
      eventListener.listenTo(eventbus, 'underConstructionRoads:toggleVisibility', function (visibility) {
        underConstructionRoadLayer.setVisible(visibility);
      });
      eventListener.listenTo(eventbus, 'linkProperty:visibilityChanged', function () {
        //Exclude underConstruction layers from toggle
        me.toggleLayersVisibility([roadLayer.layer, directionMarkerLayer, calibrationPointLayer, reservedRoadLayer], applicationModel.getRoadVisibility());
      });

      eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
        me.refreshView();
      });

      eventListener.listenTo(eventListener, 'map:clearLayers', me.clearLayers);
    };


    me.eventListener.listenTo(eventbus, 'linkProperties:highlightSelectedProject', function (featureLinkId) {
      setGeneralOpacity(0.2);
      const boundingBox = map.getView().calculateExtent(map.getSize());
      const zoomLevel = zoomlevels.getViewZoom(map);
      roadCollection.findReservedProjectLinks(boundingBox, zoomLevel, featureLinkId);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:highlightReservedRoads', function (reservedOLFeatures) {
      const styledFeatures = _.map(reservedOLFeatures, function (feature) {
        feature.setStyle(roadLinkStyler.getRoadLinkStyles(feature.linkData, map));
        return feature;
      });
      if (applicationModel.getSelectedLayer() === "linkProperty") { //check if user is still in reservation form
        reservedRoadLayer.getSource().addFeatures(styledFeatures);
      }
    });

    me.eventListener.listenTo(eventbus, 'linkProperty:fetch', function () {
      map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
      roadCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:activateInteractions', function () {
      toggleSelectInteractions(true, true);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:deactivateInteractions', function () {
      toggleSelectInteractions(false, true);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:unselected', function () {
      clearHighlights();
      setGeneralOpacity(1);
      if (applicationModel.selectionTypeIs(SelectionType.Unknown)) {
        setGeneralOpacity(0.2);
      }
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:deactivateDoubleClick', function () {
      toggleSelectInteractions(false, false);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:deactivateAllSelections roadAddressProject:deactivateAllSelections', function () {
      toggleSelectInteractions(false, true);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:activateDoubleClick', function () {
      toggleSelectInteractions(true, false);
    });

    me.eventListener.listenTo(eventbus, 'linkProperties:activateAllSelections roadAddressProject:startAllInteractions', function () {
      toggleSelectInteractions(true, true);
    });

    me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
      isActiveLayer = layer === 'linkProperty';
      toggleSelectInteractions(isActiveLayer, true);
      if (isActiveLayer) {
        addSelectInteractions();
      } else {
        removeSelectInteractions();
      }
      me.clearLayers(layers);
      clearHighlights();
      if (previouslySelectedLayer === 'linkProperty') {
        hideLayer();
        removeSelectInteractions();
      } else {
        setGeneralOpacity(1);
        showLayer();
        eventbus.trigger('linkProperty:fetch');
      }
      // Exclude unAddressedRoadLayer from general visibility toggle since it has its own checkbox control
      const nonAdressedOrConstructionLayers = layers.filter(function(layerItem) {
        return layerItem !== unAddressedRoadLayer && layerItem !== underConstructionRoadLayer;
      });
      me.toggleLayersVisibility(nonAdressedOrConstructionLayers, applicationModel.getRoadVisibility());
    });

    function clearHighlights() {
      selectSingleClick.getFeatures().clear();
      selectDoubleClick.getFeatures().clear();
      map.updateSize();
    }

    function toggleSelectInteractions(activate, both) {
      selectDoubleClick.setActive(activate);
      if (both) {
        selectSingleClick.setActive(activate);
      }
    }

    me.eventListener.listenTo(eventbus, 'roadAddressProject:clearOnClose', function () {
      setGeneralOpacity(1);
      reservedRoadLayer.getSource().clear();
    });

    function showLayer() {
      me.start();
      me.layerStarted(me.eventListener);
    }

    function hideLayer() {
      me.clearLayers(layers);
    }

    me.toggleLayersVisibility(layers, true);
    me.addLayers(layers);
    me.layerStarted(me.eventListener);

    return {
      show: showLayer,
      hide: hideLayer,
      minZoomForContent: me.minZoomForContent
    };
}
