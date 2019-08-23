(function (root) {
  root.ApplicationModel = function (models) {
    var zoom = {
      level: undefined
    };
    var selectedLayer;
    var selectedTool = 'Select';
    var centerLonLat;
    var minDirtyZoomLevel = zoomlevels.minZoomForRoadLinks;
    var minEditModeZoomLevel = zoomlevels.minZoomForEditMode;
    var readOnly = true;
    var activeButtons = false;
    var continueButton = false;
    var openProject = false;
    var projectButton = false;
    var projectFeature;
    var actionCalculating = 0;
    var actionCalculated = 1;
    var currentAction;
    var selectionType = LinkValues.SelectionType.All;
    var sessionUsername = '';
    var sessionUserRoles = '';
    var specialSelectionTypes = [LinkValues.SelectionType.Floating.value, LinkValues.SelectionType.Unknown.value];


    var getContinueButtons = function () {
      return continueButton;
    };

    var getSelectionType = function () {
      return selectionType;
    };

    var setSelectionType = function (type) {
      selectionType = type;
    };

    var selectionTypeIs = function (type) {
      if (!_.isUndefined(selectionType.value) || !_.isUndefined(type.value))
        return selectionType.value === type.value;
    };

    var setReadOnly = function (newState) {
      if (readOnly !== newState) {
        readOnly = newState;
        setActiveButtons(false);
        setSelectedTool('Select');
        eventbus.trigger('application:readOnly', newState);
      }
    };

    var setActiveButtons = function (newState) {
      if (activeButtons !== newState) {
        activeButtons = newState;
        eventbus.trigger('application:activeButtons', newState);
      }
    };

    var setProjectFeature = function (featureLinkID) {
      projectFeature = featureLinkID;
    };

    var setProjectButton = function (newState) {
      if (projectButton !== newState) {
        projectButton = newState;
      }
    };

    var setOpenProject = function (newState) {
      if (openProject !== newState) {
        openProject = newState;
      }
    };

    var setContinueButton = function (newState) {
      if (continueButton !== newState) {
        continueButton = newState;
        eventbus.trigger('application:pickActive', newState);
      }
    };

    var roadTypeShown = true;

    var isDirty = function () {
      return _.any(models, function (model) {
        return model.isDirty();
      });
    };

    var setZoomLevel = function (level) {
      zoom.level = Math.round(level);
    };

    var getZoomLevel = function() {
      return zoom.level;
    };

    var roadsVisibility = true;

    function toggleRoadVisibility() {
      roadsVisibility = !roadsVisibility;
    }

    function setSelectedTool(tool) {
      if (tool !== selectedTool) {
        selectedTool = tool;
        eventbus.trigger('tool:changed', tool);
      } else {
        selectedTool = '';
        eventbus.trigger('tool:clear');
      }
    }

    var getCurrentAction = function () {
      return currentAction;
    };

    var getUserGeoLocation = function () {
      return {
        x: centerLonLat[0],
        y: centerLonLat[1],
        zoom: zoom.level
      };
    };

    var setCurrentAction = function (action) {
      currentAction = action;
    };

    var resetCurrentAction = function () {
      currentAction = null;
    };

    var addSpinner = function () {
      jQuery('.container').append('<div class="spinner-overlay modal-overlay"><div class="spinner"></div></div>');
    };

    var removeSpinner = function () {
      jQuery('.spinner-overlay').remove();
    };

    eventbus.on("userData:fetched", function (userData) {
      sessionUsername = userData.userName;
      sessionUserRoles = userData.roles;
    });

    return {
      getCurrentAction: getCurrentAction,
      setCurrentAction: setCurrentAction,
      resetCurrentAction: resetCurrentAction,
      actionCalculating: actionCalculating,
      actionCalculated: actionCalculated,
      refreshMap: function (zoom, bbox, center) {
        var hasZoomLevelChanged = zoom.level !== zoom;
        setZoomLevel(zoom);
        centerLonLat = center;
        eventbus.trigger('map:refresh', {
          selectedLayer: selectedLayer,
          zoom: getZoomLevel(),
          bbox: bbox,
          center: center,
          hasZoomLevelChanged: hasZoomLevelChanged
        });
      },
      getUserGeoLocation: getUserGeoLocation,
      setSelectedTool: setSelectedTool,
      getSelectedTool: function () {
        return selectedTool;
      },
      zoom: zoom,
      setZoomLevel: setZoomLevel,
      getRoadVisibility: function () {
        return roadsVisibility;
      },
      toggleRoadVisibility: toggleRoadVisibility,
      setMinDirtyZoomLevel: function (level) {
        minDirtyZoomLevel = level;
      },
      selectLayer: function (layer, toggleStart, noSave) {
        if (layer !== selectedLayer) {
          var previouslySelectedLayer = selectedLayer;
          selectedLayer = layer;
          setSelectedTool('Select');
          eventbus.trigger('layer:selected', layer, previouslySelectedLayer, toggleStart);
        } else if (layer === 'linkProperty' && toggleStart) {
          eventbus.trigger('roadLayer:toggleProjectSelectionInForm', layer, noSave);
        }
        var underConstructionVisibleCheckbox = $('#underConstructionVisibleCheckbox')[0];
        if (layer === 'roadAddressProject') {
          if (underConstructionVisibleCheckbox) {
            $('#underConstructionVisibleCheckbox')[0].checked = false;
            $('#underConstructionVisibleCheckbox')[0].disabled = true;
          }
          eventbus.trigger('underConstructionProjectRoads:toggleVisibility', false);
        } else {
          if (underConstructionVisibleCheckbox) {
            $('#underConstructionVisibleCheckbox')[0].checked = true;
            $('#underConstructionVisibleCheckbox')[0].disabled = false;
          }
          eventbus.trigger('underConstructionProjectRoads:toggleVisibility', true);
        }
      },
      getSelectedLayer: function () {
        return selectedLayer;
      },
      setReadOnly: setReadOnly,
      setActiveButtons: setActiveButtons,
      setProjectButton: setProjectButton,
      setProjectFeature: setProjectFeature,
      setContinueButton: setContinueButton,
      getContinueButtons: getContinueButtons,
      setOpenProject: setOpenProject,
      getProjectFeature: function () {
        return projectFeature;
      },
      addSpinner: addSpinner,
      removeSpinner: removeSpinner,
      isReadOnly: function () {
        return readOnly;
      },
      isActiveButtons: function () {
        return activeButtons;
      },
      isProjectButton: function () {
        return projectButton;
      },
      isContinueButton: function () {
        return continueButton;
      },
      isProjectOpen: function () {
        return openProject;
      },
      isDirty: function () {
        return isDirty();
      },
      canZoomOut: function () {
        return !(isDirty() && (zoom.level <= minDirtyZoomLevel));
      },
      canZoomOutEditMode: function () {
        return (zoom.level > minEditModeZoomLevel && !readOnly && activeButtons) || (!readOnly && !activeButtons) || (readOnly);
      },
      assetDragDelay: 100,
      setRoadTypeShown: function (bool) {
        if (roadTypeShown !== bool) {
          roadTypeShown = bool;
          eventbus.trigger('road-type:selected', roadTypeShown);
        }
      },
      getCurrentLocation: function () {
        return centerLonLat;
      },
      setSelectionType: setSelectionType,
      getSelectionType: getSelectionType,
      selectionTypeIs: selectionTypeIs,
      getSessionUsername: function () {
        return sessionUsername;
      },
      getSessionUserRoles: function () {
        return sessionUserRoles;
      },
      specialSelectionTypes: specialSelectionTypes
    };
  };
})(this);

