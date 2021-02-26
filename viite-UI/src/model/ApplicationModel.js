(function (root) {
  root.ApplicationModel = function (models) {
    var zoom = {
      level: undefined
    };
    var selectedLayer;
    var selectedTool = LinkValues.Tool.Unknown.value;
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
      else
        return false;
    };

    var setReadOnly = function (newState) {
      if (readOnly !== newState) {
        readOnly = newState;
        setActiveButtons(false);
        setSelectedTool(LinkValues.Tool.Default.value);
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

    var administrativeClassShown = true;

    var isDirty = function () {
      return _.some(models, function (model) {
        return model.isDirty();
      });
    };

    var setZoomLevel = function (level) {
      zoom.level = Math.round(level);
    };

    var getZoomLevel = function () {
      return zoom.level;
    };

    var roadsVisibility = true;

    function toggleRoadVisibility() {
      roadsVisibility = !roadsVisibility;
    }

    function isSelectedTool(tool) {
      var alias = _.has(LinkValues.Tool[selectedTool], 'alias') ? LinkValues.Tool[selectedTool].alias : [];
      return tool === selectedTool || _.includes(alias, tool);
    }

    function setSelectedTool(tool) {
      if (isSelectedTool(tool)) {
        selectedTool = LinkValues.Tool.Unknown.value;
        eventbus.trigger('tool:clear');
      } else {
        selectedTool = tool;
      }
      eventbus.trigger('tool:changed', selectedTool);
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

    function spinnerClassName(spinnerEvent) {
      return spinnerEvent ? spinnerEvent : 'default-spinner';
    }

    var addSpinner = function (spinnerEvent) {
      jQuery('.container').append(
        $('<div/>').addClass("spinner-overlay").addClass(spinnerClassName(spinnerEvent)).addClass("modal-overlay").append(
          $('<div/>').addClass("spinner")
        )
      );
    };

    var removeSpinner = function (spinnerEvent) {
      jQuery('.spinner-overlay.' + spinnerClassName(spinnerEvent)).remove();
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
      refreshMap: function (zoomLevel, bbox, center) {
        var hasZoomLevelChanged = zoomLevel.level !== zoomLevel;
        setZoomLevel(zoomLevel);
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
      isSelectedTool: isSelectedTool,
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
        var tool = layer === 'node' ? LinkValues.Tool.Unknown.value : LinkValues.Tool.Default.value;
        setSelectedTool(tool);
        if (layer !== selectedLayer) {
          var previouslySelectedLayer = selectedLayer;
          selectedLayer = layer;
          eventbus.trigger('layer:selected', layer, previouslySelectedLayer, toggleStart);
        } else if (layer === 'linkProperty' && toggleStart) {
          eventbus.trigger('roadLayer:toggleProjectSelectionInForm', layer, noSave);
        }
        var underConstructionVisibleCheckbox = $('#underConstructionVisibleCheckbox')[0];
        if (layer !== selectedLayer || toggleStart) {
          if (underConstructionVisibleCheckbox) {
            $('#underConstructionVisibleCheckbox')[0].checked = true;
            $('#underConstructionVisibleCheckbox')[0].disabled = false;
          }
          eventbus.trigger('underConstructionProjectRoads:toggleVisibility', true);
        }
        var unAddressedRoadsVisibleCheckbox = $('#unAddressedRoadsVisibleCheckbox')[0];
        if (layer !== selectedLayer || toggleStart) {
          if (unAddressedRoadsVisibleCheckbox) {
            $('#unAddressedRoadsVisibleCheckbox')[0].checked = true;
            $('#unAddressedRoadsVisibleCheckbox')[0].disabled = false;
          }
          eventbus.trigger('unAddressedRoadsProjectRoads:toggleVisibility', true);
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
      setAdministrativeClassShown: function (bool) {
        if (administrativeClassShown !== bool) {
          administrativeClassShown = bool;
          eventbus.trigger('road-type:selected', administrativeClassShown);
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
}(this));

