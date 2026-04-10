import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

/**
 * ApplicationModel - Central application state management
 * 
 * Manages global application state including:
 * - Zoom levels and map center
 * - Selected tools and layers
 * - Read-only mode and UI state
 * - Project state and user session
 * - Selection types and special configurations
 */
export function ApplicationModel() {
    const zoom = {
      level: undefined
    };
    const specialSelectionTypes = [ViiteEnumerations.SelectionType.Unknown.value];

    let selectedLayer;
    let selectedTool = ViiteEnumerations.Tool.Unknown.value;
    let centerLonLat;
    let activeButtons = false;
    let openProject = false;
    let projectButton = false;
    let projectFeature;
    let selectionType = ViiteEnumerations.SelectionType.All;
    let sessionUsername = '';
    let sessionUserRoles = '';
    const appContext = {
      startupParameters: undefined,
      projectCollection: undefined,
      selectedProjectLinkProperty: undefined,
      projectLinkLayer: undefined,
      mainMenu: undefined
    };

    const getSelectionType = function () {
      return selectionType;
    };

    const setSelectionType = function (type) {
      selectionType = type;
    };

    const selectionTypeIs = function (type) {
      if (!_.isUndefined(selectionType.value) || !_.isUndefined(type.value))
        return selectionType.value === type.value;
      else
        return false;
    };

    const setActiveButtons = function (newState) {
      if (activeButtons !== newState) {
        activeButtons = newState;
        eventbus.trigger('application:activeButtons', newState);
      }
    };

    const setProjectFeature = function (featureLinkID) {
      projectFeature = featureLinkID;
    };

    const setProjectButton = function (newState) {
      if (projectButton !== newState) {
        projectButton = newState;
      }
    };

    const setOpenProject = function (newState) {
      if (openProject !== newState) {
        openProject = newState;
      }
    };

    const setZoomLevel = function (level) {
      zoom.level = Math.round(level);
    };

    const getZoomLevel = function () {
      return zoom.level;
    };

    let roadsVisibility = true;

    function toggleRoadVisibility() {
      roadsVisibility = !roadsVisibility;
    }

    function isSelectedTool(tool) {
      const alias = _.has(ViiteEnumerations.Tool[selectedTool], 'alias') ? ViiteEnumerations.Tool[selectedTool].alias : [];
      return tool === selectedTool || _.includes(alias, tool);
    }

    function setSelectedTool(tool) {
      if (isSelectedTool(tool)) {
        selectedTool = ViiteEnumerations.Tool.Unknown.value;
        eventbus.trigger('tool:clear');
      } else {
        selectedTool = tool;
      }
      eventbus.trigger('tool:changed', selectedTool);
    }

    const getUserGeoLocation = function () {
      return {
        x: centerLonLat[0],
        y: centerLonLat[1],
        zoom: zoom.level
      };
    };

    const isProjectOpen = function () {
      return openProject;
    };

    const isProjectButton = function () {
      return projectButton;
    };

    const isActiveButtons = function () {
      return activeButtons;
    };

    const getSelectedTool = function () {
      return selectedTool;
    };

    const getRoadVisibility = function () {
      return roadsVisibility;
    };

    const getSelectedLayer = function () {
      return selectedLayer;
    };

    const getProjectFeature = function () {
      return projectFeature;
    };

    const getCurrentLocation = function () {
      return centerLonLat;
    };

    const getSessionUsername = function () {
      return sessionUsername;
    };

    const getSessionUserRoles = function () {
      return sessionUserRoles;
    };

    const setStartupParameters = function (startupParameters) {
      appContext.startupParameters = startupParameters;
    };

    const getStartupParameters = function () {
      return appContext.startupParameters;
    };

    const setProjectCollection = function (projectCollection) {
      appContext.projectCollection = projectCollection;
    };

    const getProjectCollection = function () {
      return appContext.projectCollection;
    };

    const selectLayer = function (layer, toggleStart, noSave) {
      const tool = layer === 'node' ? ViiteEnumerations.Tool.Unknown.value : ViiteEnumerations.Tool.Default.value;
      setSelectedTool(tool);
      if (layer !== selectedLayer) {
        const previouslySelectedLayer = selectedLayer;
        selectedLayer = layer;
        eventbus.trigger('layer:selected', layer, previouslySelectedLayer, toggleStart);
      } else if (layer === 'linkProperty' && toggleStart) {
        eventbus.trigger('roadLayer:toggleProjectSelectionInForm', layer, noSave);
      }
    };

    eventbus.on("userData:fetched", function (userData) {
      sessionUsername = userData.userName;
      sessionUserRoles = userData.roles;
    });

    const refreshMap = function (zoomLevel, bbox, center) {
      const hasZoomLevelChanged = zoomLevel.level !== zoomLevel;
      setZoomLevel(zoomLevel);
      centerLonLat = center;
      eventbus.trigger('map:refresh', {
        selectedLayer: selectedLayer,
        zoom: getZoomLevel(),
        bbox: bbox,
        center: center,
        hasZoomLevelChanged: hasZoomLevelChanged
      });
    };

    return {
      refreshMap: refreshMap,
      getUserGeoLocation: getUserGeoLocation,
      setSelectedTool: setSelectedTool,
      getSelectedTool: getSelectedTool,
      isSelectedTool: isSelectedTool,
      zoom: zoom,
      setZoomLevel: setZoomLevel,
      getRoadVisibility: getRoadVisibility,
      toggleRoadVisibility: toggleRoadVisibility,
      selectLayer: selectLayer,
      getSelectedLayer: getSelectedLayer,
      setActiveButtons: setActiveButtons,
      setProjectButton: setProjectButton,
      setProjectFeature: setProjectFeature,
      setOpenProject: setOpenProject,
      getProjectFeature: getProjectFeature,
      isActiveButtons: isActiveButtons,
      isProjectButton: isProjectButton,
      isProjectOpen: isProjectOpen,
      getCurrentLocation: getCurrentLocation,
      setSelectionType: setSelectionType,
      getSelectionType: getSelectionType,
      selectionTypeIs: selectionTypeIs,
      getSessionUsername: getSessionUsername,
      getSessionUserRoles: getSessionUserRoles,
      setStartupParameters: setStartupParameters,
      getStartupParameters: getStartupParameters,
      setProjectCollection: setProjectCollection,
      getProjectCollection: getProjectCollection,
      specialSelectionTypes: specialSelectionTypes
    };
}
