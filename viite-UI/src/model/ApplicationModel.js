import { eventbus } from "@utils/Eventbus.js";

// ApplicationModel manages global application state, including selected layer, user session data, and road visibility settings. 
// It provides functions to get and set these states, and triggers events on changes for other components to react accordingly.

const state = {
  selectedLayer: undefined,
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

function toggleRoadVisibility() {
  state.roadsVisibility = !state.roadsVisibility;
}

function getRoadVisibility() {
  return state.roadsVisibility;
}

function getSelectedLayer() {
  return state.selectedLayer;
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
}

function setStartupParameters(startupParameters) {
  state.appContext.startupParameters = startupParameters;
}

function getStartupParameters() {
  return state.appContext.startupParameters;
}

function selectLayer(layer, toggleStart, noSave) {

  if (layer !== state.selectedLayer) {
    state.selectedLayer = layer;
    const previousLayer = state.selectedLayer;
    eventbus.trigger("layer:selected", layer, previousLayer, toggleStart);

  } else if (layer === "linkProperty" && toggleStart) {
    eventbus.trigger("roadLayer:toggleProjectSelectionInForm", layer, noSave);
  }
}

export {
  getRoadVisibility,
  toggleRoadVisibility,
  selectLayer,
  getSelectedLayer,
  getSessionUsername,
  getSessionUserRoles,
  setUserData,
  setStartupParameters,
  getStartupParameters
};