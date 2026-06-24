/**
 * LinkPropertyLayer component
 * Manages the vector layer for displaying link properties in view mode with selection and styling.
 * Handles link selection, reserved roads highlighting, and calibration points.
 * @param {Object} map - OpenLayers map instance
 * @param {Object} roadLayer - Road layer reference
 * @param {Object} selectedLinkProperty - Selected link property manager
 * @param {Object} roadCollection - Road collection manager
 * @returns {Object} Layer with methods for refresh, dirty check, and view management
 */
import { eventbus } from '@utils/Eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { RoadLinkStyler } from '@view/map/RoadLinkStyler.js';
import { Layer } from './Layer.js';
import { LinkPropertyMarker } from '../markers/LinkPropertyMarker.js';
import { CalibrationPoint } from '../markers/CalibrationPointMarker.js';
import { getSelectedLayer, getRoadVisibility } from '@model/ApplicationModel.js';

let _instance = null;

export function fetchLinkPropertiesForCurrentMap() { return _instance.fetchLinkProperties(); }
export function redrawLinkPropertyLayer() { return _instance.redraw(); }
export function highlightProject(featureLinkId) { return _instance.highlightProject(featureLinkId); }
export function highlightReservedRoads(reservedOLFeatures) { return _instance.highlightReservedRoads(reservedOLFeatures); }
export function clearLinkPropertyLayer() { return _instance.clearOnProjectClose(); }

export function initLinkPropertyLayer(map, roadLayer, selectedLinkProperty, roadCollection) {
    const me = {};
    Layer.call(me, map);

    const directionMarkerVector = new ol.source.Vector({});
    const selectedDirectionMarkerVector = new ol.source.Vector({});
    const calibrationPointVector = new ol.source.Vector({});
    const underConstructionRoadLayerVector = new ol.source.Vector({});
    const unAddressedRoadLayerVector = new ol.source.Vector({});
    const reservedRoadVector = new ol.source.Vector({});
    const selectedRoadVector = new ol.source.Vector({});
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

    const selectedRoadLayer = new ol.layer.Vector({
        source: selectedRoadVector,
        name: 'selectedRoadLayer',
        style: function (feature) {
            return getStyleForSelection(feature);
        }
    });

    const selectedDirectionMarkerLayer = new ol.layer.Vector({
        source: selectedDirectionMarkerVector,
        name: 'selectedDirectionMarkerLayer',
        zIndex: ViiteEnumerations.ViewModeZIndex.DirectionMarker.value
    });
    selectedDirectionMarkerLayer.set('name', 'selectedDirectionMarkerLayer');

    const layers = [unAddressedRoadLayer, underConstructionRoadLayer, roadLayer.layer, reservedRoadLayer, selectedRoadLayer, directionMarkerLayer, selectedDirectionMarkerLayer, calibrationPointLayer];

    const roadVisibilityLayers = [roadLayer.layer, directionMarkerLayer, calibrationPointLayer, reservedRoadLayer];

    const setGeneralOpacity = function (opacity) {
        roadLayer.layer.setOpacity(opacity);
        directionMarkerLayer.setOpacity(opacity);
        underConstructionRoadLayer.setOpacity(opacity);
        unAddressedRoadLayer.setOpacity(opacity);
    };

    const setVisible = function (layerName, visible) {
        const layersByName = {
            roadLayer: roadLayer.layer,
            underConstructionRoadLayer: underConstructionRoadLayer,
            unAddressedRoadLayer: unAddressedRoadLayer,
            reservedRoadLayer: reservedRoadLayer,
            selectedRoadLayer: selectedRoadLayer,
            directionMarkerLayer: directionMarkerLayer,
            selectedDirectionMarkerLayer: selectedDirectionMarkerLayer,
            calibrationPointLayer: calibrationPointLayer
        };
        const targetLayer = layersByName[layerName];
        if (targetLayer) {
            targetLayer.setVisible(visible);
        }
    };

    const getStyleForSelection = function (feature) {
        if (feature.linkData.roadClass !== ViiteEnumerations.RoadClass.NoClass.value) {
            return roadLinkStyler.getRoadLinkStyles(feature.linkData, map);
        } else if (feature.linkData.roadClass === ViiteEnumerations.RoadClass.NoClass.value && feature.linkData.lifecycleStatus !== ViiteEnumerations.lifecycleStatus.UnderConstruction.value) {
            return roadLinkStyler.getUnAddressedStyles(feature.linkData, map);
        } else {
            return roadLinkStyler.getUnderConstructionStyles(feature.linkData, map);
        }
    };

    const selectDoubleClick = new ol.interaction.Select({
        layer: [roadLayer.layer, underConstructionRoadLayer, unAddressedRoadLayer],
        condition: ol.events.condition.doubleClick,
        style: function (feature) {
            return roadLinkStyler.getRoadLinkStyles(feature.linkData, map);
        }
    });

    const getSelectedF = (ctrlPressed, event) => {
        if (ctrlPressed) {
            return map.forEachFeatureAtPixel(event.mapBrowserEvent.pixel, function (feature) {
                return feature;
            });
        } else {
            return _.find(event.selected, function (selectionTarget) {
                return !_.isUndefined(selectionTarget.linkData);
            });
        }
    };

    selectDoubleClick.on('select', function (event) {
        const visibleFeatures = getVisibleFeatures(true, true, true, true, true, true, true);
        selectSingleClick.getFeatures().clear();
        const ctrlPressed = (event.mapBrowserEvent) ? event.mapBrowserEvent.originalEvent.ctrlKey : false;
        selectDoubleClick.getFeatures().clear();

        if (event.selected.length !== 0) {
            const selectedF = getSelectedF(ctrlPressed, event);
            if (roadLayer.layer.getOpacity() === 1) {
                setGeneralOpacity(0.2);
            }
            if (!_.isUndefined(selectedF)) {
                const selection = selectedF.linkData;
                if (ctrlPressed) {
                    modifyPreviousSelection(ctrlPressed, selection);
                } else {
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
    map.on('dblclick', zoomDoubleClickListener);

    const selectSingleClick = new ol.interaction.Select({
        multi: true,
        layers: [roadLayer.layer, underConstructionRoadLayer, unAddressedRoadLayer],
        condition: ol.events.condition.singleClick,
        style: function (feature) {
            if (feature.linkData.roadClass !== ViiteEnumerations.RoadClass.NoClass.value) {
                return roadLinkStyler.getRoadLinkStyles(feature.linkData, map);
            } else if (feature.linkData.roadClass === ViiteEnumerations.RoadClass.NoClass.value && feature.linkData.lifecycleStatus !== ViiteEnumerations.lifecycleStatus.UnderConstruction.value) {
                return roadLinkStyler.getUnAddressedStyles(feature.linkData, map);
            } else {
                return roadLinkStyler.getUnderConstructionStyles(feature.linkData, map);
            }
        }
    });
    selectSingleClick.set('name', 'selectSingleClickInteractionLPL');

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
            if (ctrlPressed) {
                modifyPreviousSelection(ctrlPressed, selection);
            } else {
                selectedLinkProperty.close();
                setGeneralOpacity(0.2);
                if (selection.roadNumber !== 0) {
                    roadCollection.setClickedLinearLocationId(selection.linearLocationId);
                    roadCollection.fetchWholeRoadPart(selection.roadNumber, selection.roadPartNumber, selection);
                }
                selectedLinkProperty.open(selection, true, visibleFeatures);
            }
        } else {
            selectedLinkProperty.close();
        }
    });

    map.on('click', function (event) {
        if (window.getSelection) {
            window.getSelection().removeAllRanges();
        } else if (document.selection) {
            document.selection.empty();
        }

        const hasFeatureOnPoint = _.isUndefined(map.forEachFeatureAtPixel(event.pixel, function (feature) {
            return feature;
        }));

        if (isActiveLayer && hasFeatureOnPoint) {
            selectedLinkProperty.close();
        }
    });

    const addFeaturesToSelection = function (features) {
        const olUids = _.map(selectSingleClick.getFeatures().getArray(), function (feature) {
            return feature.ol_uid;
        });
        _.each(features, function (feature) {
            if (!_.includes(olUids, feature.ol_uid)) {
                selectSingleClick.getFeatures().push(feature);
                olUids.push(feature.ol_uid);
            }
        });
    };

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
                selectedLinkProperty.close();
            } else {
                const features = getAllFeatures();
                selectedLinkProperty.openCtrl(selectedLinearLocationIds, selectedLinkIds, true, features);
            }
        }
    }

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

    const addSelectInteractions = function () {
        removeSelectInteractions();
        map.addInteraction(selectDoubleClick);
        map.addInteraction(selectSingleClick);
    };

    function removeSelectInteractions() {
        map.removeInteraction(selectDoubleClick);
        map.removeInteraction(selectSingleClick);
    }

    addSelectInteractions();

    const redraw = function () {
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

        addLinkFeaturesToLayer(roadLinksWithRoadNumber, roadLayer.layer);
        addLinkFeaturesToLayer(underConstruction, underConstructionRoadLayer);
        addLinkFeaturesToLayer(unAddressed, unAddressedRoadLayer);
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
            if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomLevelForCalibrationPoints && getSelectedLayer() === 'linkProperty') {
                const actualPoints = me.drawCalibrationMarkers(calibrationPointLayer.source, roadLinks);
                _.each(actualPoints, function (actualPoint) {
                    const calMarker = new CalibrationPoint(actualPoint);
                    calibrationPointLayer.getSource().addFeature(calMarker.getMarker(true));
                });
            }
        }
        addSelectInteractions();
    };

    const refreshView = function () {
        roadCollection.reset();
        roadCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
        underConstructionRoadLayer.changed();
        unAddressedRoadLayer.changed();
        roadLayer.layer.changed();
    };

    const isDirty = function () {
        return selectedLinkProperty.isDirty();
    };

    const addSelectedLinkFeaturesToSelection = function (link) {
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
    };

    const onLinkPropertySelected = function (data) {
        if (getSelectedLayer() === 'linkProperty' || getSelectedLayer() === 'node') {
            redraw();
        }
        addSelectedLinkFeaturesToSelection(data);
    };

    const onLinkPropertyUnselected = function () {
        if (getSelectedLayer() === 'linkProperty' || getSelectedLayer() === 'node') {
            redraw();
        }
        clearHighlights();
        setGeneralOpacity(1);
    };

    const fetchLinkProperties = function () {
        map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
        roadCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1);
    };

    const highlightProjectInternal  = function (featureLinkId) {
        setGeneralOpacity(0.2);
        const boundingBox = map.getView().calculateExtent(map.getSize());
        const zoomLevel = zoomlevels.getViewZoom(map);
        roadCollection.findReservedProjectLinks(boundingBox, zoomLevel, featureLinkId);
    };

    const highlightReservedRoadsInternal  = function (reservedOLFeatures) {
        const styledFeatures = _.map(reservedOLFeatures, function (feature) {
            feature.setStyle(roadLinkStyler.getRoadLinkStyles(feature.linkData, map));
            return feature;
        });
        if (getSelectedLayer() === 'linkProperty') {
            reservedRoadLayer.getSource().addFeatures(styledFeatures);
        }
    };

    const clearOnProjectClose = function () {
        setGeneralOpacity(1);
        reservedRoadLayer.getSource().clear();
    };

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

    const showLayer = function () {
        me.start();
        me.eventListener.listenTo(me.eventListener, 'map:clearLayers', me.clearLayers);
    };

    const hideLayer = function () {
        me.clearLayers(layers);
    };

    const updateRoadVisibility = function () {
        me.toggleLayersVisibility(roadVisibilityLayers, getRoadVisibility());
    };

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
            fetchLinkProperties();
        }
        const nonAddressedOrConstructionLayers = layers.filter(function (layerItem) {
            return layerItem !== unAddressedRoadLayer && layerItem !== underConstructionRoadLayer;
        });
        me.toggleLayersVisibility(nonAddressedOrConstructionLayers, getRoadVisibility());
    });

    me.toggleLayersVisibility(layers, true);
    me.addLayers(layers);
    showLayer();

    _instance = {
        fetchLinkProperties,
        redraw: function () {
            if (getSelectedLayer() === 'linkProperty' || getSelectedLayer() === 'node') {
                redraw();
            }
        },
        highlightProject: highlightProjectInternal,
        highlightReservedRoads: highlightReservedRoadsInternal,
        clearOnProjectClose,
        show: showLayer,
        hide: hideLayer,
        isDirty,
        refreshView,
        updateRoadVisibility,
        setVisible,
        onLinkPropertySelected,
        onLinkPropertyUnselected
    };

    return _instance;
}