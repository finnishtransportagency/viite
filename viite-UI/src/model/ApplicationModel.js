(function (root) {
  root.ApplicationModel = function (models) {
    const zoom = {
      level: undefined
    };
    const specialSelectionTypes = [ViiteEnumerations.SelectionType.Unknown.value];
    const minEditModeZoomLevel = zoomlevels.minZoomForEditMode;

    let selectedLayer;
    let selectedTool = ViiteEnumerations.Tool.Unknown.value;
    let centerLonLat;
    let minDirtyZoomLevel = zoomlevels.minZoomForRoadLinks;
    let readOnly = true;
    let activeButtons = false;
    let openProject = false;
    let projectButton = false;
    let projectFeature;
    let currentAction;
    let selectionType = ViiteEnumerations.SelectionType.All;
    let sessionUsername = '';
    let sessionUserRoles = '';

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

    const setReadOnly = function (newState) {
      if (readOnly !== newState) {
        readOnly = newState;
        setActiveButtons(false);
        setSelectedTool(ViiteEnumerations.Tool.Default.value);
        eventbus.trigger('application:readOnly', newState);
      }
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

    const isDirty = function () {
      return _.some(models, function (model) {
        return model.isDirty();
      });
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

    const getCurrentAction = function () {
      return currentAction;
    };

    const getUserGeoLocation = function () {
      return {
        x: centerLonLat[0],
        y: centerLonLat[1],
        zoom: zoom.level
      };
    };

    const setCurrentAction = function (action) {
      currentAction = action;
    };

    const resetCurrentAction = function () {
      currentAction = null;
    };

    function spinnerClassName(spinnerEvent) {
      return spinnerEvent ? spinnerEvent : 'default-spinner';
    }

    const canZoomOutEditMode = function () {
      return (zoom.level > minEditModeZoomLevel && !readOnly && activeButtons) || (!readOnly && !activeButtons) || (readOnly);
    };

    const canZoomOut = function () {
      return !(isDirty() && (zoom.level <= minDirtyZoomLevel));
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

    const setMinDirtyZoomLevel = function (level) {
      minDirtyZoomLevel = level;
    };

    const getSelectedLayer = function () {
      return selectedLayer;
    };

    const getProjectFeature = function () {
      return projectFeature;
    };

    const isReadOnly = function () {
      return readOnly;
    };

    const getCurrentLocation = function () {
      return centerLonLat;
    };

    const getSessionUsername = function () {
      return sessionUsername;
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
      const underConstructionVisibleCheckbox = $('#underConstructionVisibleCheckbox')[0];
      if (layer !== selectedLayer || toggleStart) {
        if (underConstructionVisibleCheckbox) {
          $('#underConstructionVisibleCheckbox')[0].checked = true;
          $('#underConstructionVisibleCheckbox')[0].disabled = false;
        }
        eventbus.trigger('underConstructionProjectRoads:toggleVisibility', true);
      }
      const unAddressedRoadsVisibleCheckbox = $('#unAddressedRoadsVisibleCheckbox')[0];
      if (layer !== selectedLayer || toggleStart) {
        if (unAddressedRoadsVisibleCheckbox) {
          $('#unAddressedRoadsVisibleCheckbox')[0].checked = true;
          $('#unAddressedRoadsVisibleCheckbox')[0].disabled = false;
        }
        eventbus.trigger("unAddressedProjectRoads:toggleVisibility", true);
      }
    };

    const addSpinner = function (spinnerEvent) {
      jQuery('.container').append(
        $('<div></div>').addClass("spinner-overlay").addClass(spinnerClassName(spinnerEvent)).addClass("modal-overlay").append(
          $('<div></div>').addClass("spinner")
        )
      );
    };

    const removeSpinner = function (spinnerEvent) {
      jQuery('.spinner-overlay.' + spinnerClassName(spinnerEvent)).remove();
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
      getCurrentAction: getCurrentAction,
      setCurrentAction: setCurrentAction,
      resetCurrentAction: resetCurrentAction,
      refreshMap: refreshMap,
      getUserGeoLocation: getUserGeoLocation,
      setSelectedTool: setSelectedTool,
      getSelectedTool: getSelectedTool,
      isSelectedTool: isSelectedTool,
      zoom: zoom,
      setZoomLevel: setZoomLevel,
      getRoadVisibility: getRoadVisibility,
      toggleRoadVisibility: toggleRoadVisibility,
      setMinDirtyZoomLevel: setMinDirtyZoomLevel,
      selectLayer: selectLayer,
      getSelectedLayer: getSelectedLayer,
      setReadOnly: setReadOnly,
      setActiveButtons: setActiveButtons,
      setProjectButton: setProjectButton,
      setProjectFeature: setProjectFeature,
      setOpenProject: setOpenProject,
      getProjectFeature: getProjectFeature,
      addSpinner: addSpinner,
      removeSpinner: removeSpinner,
      isReadOnly: isReadOnly,
      isActiveButtons: isActiveButtons,
      isProjectButton: isProjectButton,
      isProjectOpen: isProjectOpen,
      isDirty: isDirty,
      canZoomOut: canZoomOut,
      canZoomOutEditMode: canZoomOutEditMode,
      assetDragDelay: 100,
      getCurrentLocation: getCurrentLocation,
      setSelectionType: setSelectionType,
      getSelectionType: getSelectionType,
      selectionTypeIs: selectionTypeIs,
      getSessionUsername: getSessionUsername,
      specialSelectionTypes: specialSelectionTypes
    };
  };
}(this));

