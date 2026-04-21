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
import { ViiteEnumerations } from "@utils/ViiteEnumerations.js";
import { eventbus } from "@utils/eventbus.js";

const specialSelectionTypes = [
  ViiteEnumerations.SelectionType.Unknown.value
];

const state = {
  zoomLevel: undefined,
  selectedLayer: undefined,
  selectedTool: ViiteEnumerations.Tool.Unknown.value,
  centerLonLat: undefined,
  selectionType: ViiteEnumerations.SelectionType.All,
  sessionUsername: "",
  sessionUserRoles: "",
  roadsVisibility: true,

  appContext: {
    startupParameters: undefined,
    projectCollection: undefined,
    selectedProjectLinkProperty: undefined,
    projectLinkLayer: undefined,
    mainMenu: undefined
  }
};

function getState() {
  return state;
}

function getSelectionType() {
  return state.selectionType;
}

function setSelectionType(type) {
  state.selectionType = type;
  eventbus.trigger("selectionType:changed", type);
}

function selectionTypeIs(type) {
  if (
    _.isUndefined(state.selectionType?.value) ||
    _.isUndefined(type?.value)
  ) {
    return false;
  }

  return state.selectionType.value === type.value;
}

function setZoomLevel(level) {
  state.zoomLevel = Math.round(level);
  eventbus.trigger("zoom:changed", state.zoomLevel);
}

function getZoomLevel() {
  return state.zoomLevel;
}

function toggleRoadVisibility() {
  state.roadsVisibility = !state.roadsVisibility;
  eventbus.trigger("roadsVisibility:changed", state.roadsVisibility);
}

function getRoadVisibility() {
  return state.roadsVisibility;
}

function isSelectedTool(tool) {
  const aliases =
    ViiteEnumerations.Tool[state.selectedTool]?.alias || [];

  return (
    tool === state.selectedTool ||
    _.includes(aliases, tool)
  );
}

function setSelectedTool(tool) {
  if (isSelectedTool(tool)) {
    state.selectedTool =
      ViiteEnumerations.Tool.Unknown.value;

    eventbus.trigger("tool:clear");
  } else {
    state.selectedTool = tool;
  }

  eventbus.trigger("tool:changed", state.selectedTool);
}

function getSelectedTool() {
  return state.selectedTool;
}

function getUserGeoLocation() {
  if (!state.centerLonLat) return undefined;

  return {
    x: state.centerLonLat[0],
    y: state.centerLonLat[1],
    zoom: state.zoomLevel
  };
}

function getSelectedLayer() {
  return state.selectedLayer;
}

function getCurrentLocation() {
  return state.centerLonLat;
}

function getSessionUsername() {
  return state.sessionUsername;
}

function getSessionUserRoles() {
  return state.sessionUserRoles;
}

function setUserData(userData) {
  state.sessionUsername = userData.userName;
  state.sessionUserRoles = userData.roles;

  eventbus.trigger("userData:changed", userData);
}

function setStartupParameters(startupParameters) {
  state.appContext.startupParameters =
    startupParameters;

  eventbus.trigger(
    "startupParameters:changed",
    startupParameters
  );
}

function getStartupParameters() {
  return state.appContext.startupParameters;
}

function setProjectCollection(projectCollection) {
  state.appContext.projectCollection =
    projectCollection;

  eventbus.trigger(
    "projectCollection:changed",
    projectCollection
  );
}

function getProjectCollection() {
  return state.appContext.projectCollection;
}

function selectLayer(layer, toggleStart, noSave) {
  const tool =
    layer === "node"
      ? ViiteEnumerations.Tool.Unknown.value
      : ViiteEnumerations.Tool.Default.value;

  setSelectedTool(tool);

  if (layer !== state.selectedLayer) {
    const previous = state.selectedLayer;

    state.selectedLayer = layer;

    eventbus.trigger(
      "layer:selected",
      layer,
      previous,
      toggleStart
    );
  } else if (
    layer === "linkProperty" &&
    toggleStart
  ) {
    eventbus.trigger(
      "roadLayer:toggleProjectSelectionInForm",
      layer,
      noSave
    );
  }
}

function refreshMap(zoomLevel, bbox, center) {
  setZoomLevel(zoomLevel);
  state.centerLonLat = center;

  eventbus.trigger("map:refresh", {
    selectedLayer: state.selectedLayer,
    zoom: state.zoomLevel,
    bbox,
    center
  });
}

export {
  state,
  getState,

  refreshMap,

  getUserGeoLocation,

  setSelectedTool,
  getSelectedTool,
  isSelectedTool,

  setZoomLevel,
  getZoomLevel,

  getRoadVisibility,
  toggleRoadVisibility,

  selectLayer,
  getSelectedLayer,

  getCurrentLocation,

  setSelectionType,
  getSelectionType,
  selectionTypeIs,

  getSessionUsername,
  getSessionUserRoles,
  setUserData,

  setStartupParameters,
  getStartupParameters,

  setProjectCollection,
  getProjectCollection,

  specialSelectionTypes
};