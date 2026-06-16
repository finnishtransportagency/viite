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
import { eventbus } from "@utils/Eventbus.js";

const state = {
  zoomLevel: undefined,
  selectedLayer: undefined,
  centerLonLat: undefined,
  selectionType: ViiteEnumerations.SelectionType.All,
  sessionUsername: "",
  sessionUserRoles: "",
  roadsVisibility: true,

  appContext: {
    startupParameters: undefined,
    selectedProjectLinkProperty: undefined,
    projectLinkLayer: undefined,
    mainMenu: undefined
  }
};

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

function selectLayer(layer, toggleStart, noSave) {

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
  refreshMap,
  getUserGeoLocation,
  setZoomLevel,
  getZoomLevel,
  getRoadVisibility,
  toggleRoadVisibility,
  selectLayer,
  getSelectedLayer,
  getCurrentLocation,
  getSessionUsername,
  getSessionUserRoles,
  setUserData,
  setStartupParameters,
  getStartupParameters
};