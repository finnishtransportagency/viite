(function (root) {
  root.ProjectForm = function (map, projectCollection, selectedProjectLinkProperty, projectLinkLayer, startupParameters) {
    //TODO create uniq project model in ProjectCollection instead using N vars e.g.: project = {id, roads, parts, ely, startingLinkId, publishable, projectErrors}
    const templates = new ProjectFormTemplates();
    const validator = new ProjectFormValidator();
    var currentProject = false;
    var formCommon = new FormCommon('');
    var ProjectStatus = ViiteEnumerations.ProjectStatus;
    var editableStatus = [ProjectStatus.Incomplete.value, ProjectStatus.Unknown.value];

    // flag to keep track if the project links have been recalculated after the changes made to the project links
    var recalculatedAfterChangesFlag = false;

    eventbus.on('roadAddressProject:setRecalculatedAfterChangesFlag', function (bool) {
      recalculatedAfterChangesFlag = bool;
    });

    eventbus.on('roadAddressProject:projectLinkSaved', function() {
      // Get the current state of the validate button if it exists
      $('#actionButtons').empty();
      const $buttons = $('.project-form.form-controls');
      const $validateButton = $buttons.find('#validate-button');
      const hasValidationButton = $validateButton.length > 0;
      const isValidationButtonVisible = hasValidationButton && $validateButton.is(':visible');


      // Rebuild the buttons with proper states
      let buttonsHtml = '';

      const projectButtons = new ProjectButtons({
        showValidate: _.includes(startupParameters.roles, 'dev'),
        validateVisible: isValidationButtonVisible,
        disabled: false
      });
      buttonsHtml += projectButtons.render();

      // Update the buttons container
      $buttons.html(buttonsHtml);

      // Update button states based on project status
      const projectErrors = projectCollection.getProjectErrors();

      // Update button states based on the same logic as in buttonsWhenReOpenCurrent
      const isChangeTableOpen = $('.change-table-frame').is(':visible');
      const hasRecalculated = getRecalculatedAfterChangesFlag();

      // Check for errors first
      if (projectErrors.length > 0) {
          formCommon.setDisabledAndTitleAttributesById("send-button", true, "Projektin tulee läpäistä validoinnit");
          return;
      }

      if (isChangeTableOpen) {
          formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "Etäisyyslukemia ei voida päivittää yhteenvetotaulukon ollessa auki");
          formCommon.setDisabledAndTitleAttributesById("changes-button", true, "Yhteenvetotaulukko on jo auki");
          formCommon.setDisabledAndTitleAttributesById("send-button", false, "");
          return;
      }

      if (hasRecalculated) {
          formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "Etäisyyslukemat on päivitetty");
          formCommon.setDisabledAndTitleAttributesById("changes-button", false, "");
          formCommon.setDisabledAndTitleAttributesById("send-button", true, "Avaa yhteenvetotaulukko ensin");
          return;
      }

      formCommon.setDisabledAndTitleAttributesById("recalculate-button", false, "");
      formCommon.setDisabledAndTitleAttributesById("changes-button", true, "Päivitä etäisyyslukemat ensin");
      formCommon.setDisabledAndTitleAttributesById("send-button", true, "Päivitä etäisyyslukemat ja avaa yhteenvetotaulukko ensin");
      // Rebind event handlers
      if (typeof bindEvents === 'function') {
        bindEvents();
      }
    });

    var getRecalculatedAfterChangesFlag = function () {
      return recalculatedAfterChangesFlag;
    };

    var addDatePicker = function () {
      var $validFrom = $('#projectStartDate');
      dateutil.addSingleDatePicker($validFrom);
      $validFrom.on('change', function () {
        eventbus.trigger('projectStartDate:notificationCheck', $(this).val());
      });
    };

    var bindEvents = function () {

      var rootElement = $('#feature-attributes');

      var removeReservedPart = function (roadNumber, roadPartNumber) {
        currentProject.isDirty = true;
        projectCollection.setReservedParts(_.filter(projectCollection.getReservedParts(), function (part) {
          return part.roadNumber.toString() !== roadNumber || part.roadPartNumber.toString() !== roadPartNumber;
        }));
        removeRenumberedPart(roadNumber, roadPartNumber);
        fillForm(projectCollection.getReservedParts(), projectCollection.getFormedParts());
      };

      const removeRenumberedPart = (roadNumber, roadPartNumber) => {
        const roadNumStr = roadNumber.toString();
        const roadPartNumStr = roadPartNumber.toString();

        const remainingParts = projectCollection.getFormedParts().filter(part => {
          // Check if any address in this part matches the target road/part and is a numbering change
          const isTargetRenumberedPart = (part.roadAddresses || []).some(ra => 
            ra.roadAddressNumber.toString() === roadNumStr &&
            ra.roadAddressPartNumber.toString() === roadPartNumStr &&
            ra.isNumbering
          );
          
          // We keep the parts that are NOT the target renumbered part
          return !isTargetRenumberedPart;
        });

        projectCollection.setFormedParts(remainingParts);
      };

      var removeFormedPart = function (roadNumber, roadPartNumber) {
        currentProject.isDirty = true;
        _.each(projectCollection.getRoadAddressesFromFormedRoadPart(roadNumber, roadPartNumber), function (roadAddresses) {
          _.each(roadAddresses, function (ra) {
            removeFormedPart(ra.roadAddressNumber, ra.roadAddressPartNumber);
          });
        });
        projectCollection.setFormedParts(_.filter(projectCollection.getFormedParts(), function (part) {
          return part.roadNumber.toString() !== roadNumber || part.roadPartNumber.toString() !== roadPartNumber;
        }));
        fillForm(projectCollection.getReservedParts(), projectCollection.getFormedParts());
      };

      var updateReservedParts = function (currParts, newParts) {
        var reservedParts = $("#reservedRoads");
        var formedParts = $("#newReservedRoads");

        reservedParts.append(reservedParts.html(currParts));
        formedParts.append(formedParts.html(newParts));
      };

      var fillForm = function (currParts, newParts) {
        updateReservedParts(templates.reservedHtmlList(currParts, projectCollection), templates.formedHtmlList(newParts, projectCollection));
        applicationModel.setProjectButton(true);
        applicationModel.setProjectFeature(currentProject.id);
        applicationModel.setOpenProject(true);
        rootElement.find('.btn-reserve').prop("disabled", false);
        rootElement.find('.btn-save').prop("disabled", false);
        rootElement.find('.btn-next').prop("disabled", false);
      };

      var toggleAdditionalControls = function () {
        rootElement.find('header').replaceWith(`<header>${
            formCommon.titleWithEditingTool(currentProject)
            }</header>`);
      };

      var createOrSaveProject = function () {
        applicationModel.addSpinner();
        var data = $('#roadAddressProject').get(0);
        if (_.isUndefined(currentProject) || currentProject.id === 0) {
          projectCollection.createProject(data, map.getView().getResolution());
        } else {
          projectCollection.saveProject(data, map.getView().getResolution());
        }
      };

      var deleteProject = function () {
        if (!_.isUndefined(currentProject) && currentProject.id !== 0) {
          projectCollection.deleteProject(currentProject.id);
        }
      };

      var saveChanges = function () {
        applicationModel.addSpinner();
        eventbus.once('roadAddress:projectSaved', function (result) {
          currentProject = result.project;
          currentProject.isDirty = false;
          var text = '';
          var index = 0;
          projectCollection.setReservedParts(result.reservedInfo);
          _.each(result.reservedInfo, function (line) {
            var button = projectCollection.getDeleteButton(index++, line.roadNumber, line.roadPartNumber, 'reservedList');
            const labels = `${templates.addSmallLabel(line.roadNumber)}${templates.addSmallLabel(line.roadPartNumber)}${templates.addSmallLabel(line.roadLength)}${templates.addSmallLabel(line.discontinuity)}${templates.addSmallLabel(line.ely || '0')}${templates.addSmallLabel(line.evk || line.ely || '0')}`;
            text += `<div class="form-reserved-roads-list">${button}${labels}</div>`;
          });
          rootElement.html(templates.projectTemplate()({ 
            project: currentProject, 
            reservedRoads: text, 
            newReservedRoads: '',
            actionButtonsHtml: templates.actionButtons(currentProject, ProjectStatus),
            isNewProject: false
          }));

          jQuery('.modal-overlay').remove();
          addDatePicker();
          if (!_.isUndefined(result.projectAddresses)) {
            eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId, result.project);
          }
          selectedProjectLinkProperty.setDirty(false);
          eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        });
        createOrSaveProject();
      };

      var nextStage = function () {
        applicationModel.addSpinner();
        currentProject.isDirty = false;
        jQuery('.modal-overlay').remove();
        eventbus.trigger('roadAddressProject:openProject', currentProject);
        rootElement.html(templates.selectedProjectLinkTemplateDisabledButtons(currentProject, formCommon, startupParameters));
        _.defer(function () {
          applicationModel.selectLayer('roadAddressProject');
          toggleAdditionalControls();
        });
      };

      /**
       * Only enable the changes button, because user can only inspect the project and the change table data
       * */
      var buttonsWhenInspectingUneditableProject = function () {
        formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "");
        formCommon.setDisabledAndTitleAttributesById("changes-button", false, "");
      };

      /**
       * Set attributes (disabled, title) of the recalculate and changes buttons when the project is opened.
       * User needs to recalculate project when it's opened, so we enable recalculate button and disable changes button.
       * */
      var buttonsWhenOpenProject = function () {
        if (currentProject.statusCode === 10 || currentProject.statusCode === 11 || currentProject.statusCode === 12) {
          buttonsWhenInspectingUneditableProject();
        } else {
          const projectErrors = projectCollection.getProjectErrors();
          if (projectErrors.length === 0) {
            formCommon.setDisabledAndTitleAttributesById("recalculate-button", false, "");
          } else {
            formCommon.setDisabledAndTitleAttributesById("recalculate-button", true);
          }
          formCommon.setDisabledAndTitleAttributesById("changes-button", true, "Päivitä etäisyyslukemat ensin");
          formCommon.setInformationContent();
          formCommon.setInformationContentText("Päivitä etäisyyslukemat jatkaaksesi projektia.");
        }
      };

      /**
       * Set attributes (disabled, title) of the recalculate, changes & send buttons when project link changes are cancelled
       * ("Peruuta" button is clicked or clicking anywhere on the map when project edit form is open (i.e. closing the form))
       * */
      var buttonsWhenReOpenCurrent = function (projectErrors, highPriorityProjectErrors) {
        eventbus.trigger('roadAddressProject:writeProjectErrors');
        if (highPriorityProjectErrors.length === 0) {
          if ($('.change-table-frame').css('display') === "block") {
            formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "Etäisyyslukemia ei voida päivittää yhteenvetotaulukon ollessa auki");
            formCommon.setDisabledAndTitleAttributesById("changes-button", true, "Yhteenvetotaulukko on jo auki");
            formCommon.setDisabledAndTitleAttributesById("send-button", false, "");
          } else if (projectErrors.length === 0 && getRecalculatedAfterChangesFlag() === false) {
            formCommon.setDisabledAndTitleAttributesById("recalculate-button", false, "");
            formCommon.setDisabledAndTitleAttributesById("changes-button", true, "Projektin tulee läpäistä validoinnit");
          } else if (projectErrors.length === 0 && getRecalculatedAfterChangesFlag() === true) {
            formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "Etäisyyslukemat on päivitetty");
            formCommon.setDisabledAndTitleAttributesById("changes-button", false, "");
          } else if (projectErrors.length !== 0 && getRecalculatedAfterChangesFlag() === true) {
            formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "Etäisyyslukemat on päivitetty");
            formCommon.setDisabledAndTitleAttributesById("changes-button", true, "Projektin tulee läpäistä validoinnit");
          }
        }
      };

      var createNewProject = function () {
        applicationModel.addSpinner();
        eventbus.once('roadAddress:projectSaved', function (result) {
          currentProject = result.project;
          currentProject.isDirty = false;
          jQuery('.modal-overlay').remove();
          if (!_.isUndefined(result.projectAddresses)) {
            eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId, result.project);
          }
          eventbus.trigger('roadAddressProject:openProject', result.project);
          rootElement.html(templates.selectedProjectLinkTemplateDisabledButtons(currentProject, formCommon, startupParameters));
          _.defer(function () {
            applicationModel.selectLayer('roadAddressProject');
            toggleAdditionalControls();
            selectedProjectLinkProperty.setDirty(false);
            eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
          });
        });
        createOrSaveProject();
      };

      var isProjectEditable = function () {
        return _.isUndefined(projectCollection.getCurrentProject()) ||
            _.includes(editableStatus, projectCollection.getCurrentProject().project.statusCode);
      };

      var disableFormInputs = function () {
        if (!isProjectEditable()) {
          $('#roadAddressProject :input').prop('disabled', true);
          $('.btn-reserve').prop('disabled', true);
          $('.btn-delete').prop('hidden', true);
        }
      };


      eventbus.on('roadAddress:newProject', function () {
        currentProject = {
          id: 0,
          isDirty: false
        };
        $("#roadAddressProject").html("");
        rootElement.html(templates.projectTemplate()({
          project: currentProject, 
          reservedRoads: [], 
          newReservedRoads: [], 
          actionButtonsHtml: templates.actionButtons(currentProject, ProjectStatus),
          isNewProject: true
        }));
        jQuery('.modal-overlay').remove();
        addDatePicker();
        applicationModel.setOpenProject(true);
        projectCollection.clearRoadAddressProjects();
        $('#generalNext').prop('disabled', true);
      });

      eventbus.on('roadAddress:openProject', function (result) {
        currentProject = result.project;
        projectCollection.setAndWriteProjectErrorsToUser(result.projectErrors);
        currentProject.isDirty = false;
        projectCollection.clearRoadAddressProjects();
        projectCollection.setCurrentProject(result);
        projectCollection.setReservedParts(result.reservedInfo);
        projectCollection.setFormedParts(result.formedInfo);
        var currentReserved = templates.reservedHtmlList(projectCollection.getReservedParts(), projectCollection);
        var newReserved = templates.formedHtmlList(projectCollection.getFormedParts(), projectCollection);
        rootElement.html(templates.projectTemplate()({
          project: currentProject, 
          reservedRoads: currentReserved, 
          newReservedRoads: newReserved,
          actionButtonsHtml: templates.actionButtons(currentProject, ProjectStatus),
          isNewProject: false
        }));
        jQuery('#projectList').remove();
        if (!_.isUndefined(currentProject)) {
          eventbus.trigger('linkProperties:selectedProject', result.linkId, result.project);
          eventbus.trigger('roadAddressProject:deactivateAllSelections');
        }
        applicationModel.setProjectButton(true);
        applicationModel.setProjectFeature(currentProject.id);
        applicationModel.setOpenProject(true);
        disableFormInputs();
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectValidationFailed', function (result) {
        new ModalConfirm(result.toString());
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectValidationSucceed', function () {
        rootElement.find('#generalNext').prop("disabled", validator.formIsInvalid(rootElement));
        $('#saveEdit:disabled').prop('disabled', validator.formIsInvalid(rootElement));
        currentProject.isDirty = true;
        emptyFields(['tie', 'aosa', 'losa']);
      });

      eventbus.on('projectStartDate:notificationCheck', function (projectStartDate) {
        $('#projectStartDate-validation-notification').html(validator.checkDateNotification(projectStartDate));
      });

      eventbus.on('roadAddress:projectFailed', function () {
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddressProject:reOpenCurrent', function () {
        reOpenCurrent();
      });

      eventbus.on('roadAddressProject:writeProjectErrors', function () {
        $('#project-errors').html(templates.errorsList(projectCollection, formCommon));
        applicationModel.removeSpinner();
      });

      var textFieldChangeHandler = function (eventData) {
        if (currentProject) {
          currentProject.isDirty = true;
        }
        var textIsNonEmpty = $('#nimi').val() !== "" && $('#projectStartDate').val() !== "";
        var nextAreDisabled = $('#generalNext').is(':disabled') || $('#saveEdit').is(':disabled');
        var reservedRemoved = !_.isUndefined(eventData) && eventData.removedReserved;

        if ((textIsNonEmpty || reservedRemoved) && nextAreDisabled) {
          $('#generalNext').prop('disabled', false);
          $('#saveEdit:disabled').prop('disabled', false);
          currentProject.isDirty = true;
        }
      };

      var reserveFieldChangeHandler = function (_eventData) {
        var textIsNonEmpty = $('#tie').val() !== "" && $('#aosa').val() !== "" && $('#losa').val() !== "";
        var roadPartValid = !validator.isRoadPartInvalid(rootElement);
        rootElement.find('#roadAddressProject button.btn-reserve').attr('disabled', validator.projDateEmpty(rootElement) && textIsNonEmpty && roadPartValid);
      };

      var emptyFields = function (fieldIds) {
        fieldIds.forEach(function (id) {
          $(`#${id}`).val('');
        });
      };

      rootElement.on('change', '#nimi', function () {
        textFieldChangeHandler();
      });
      rootElement.on('change', '#projectStartDate', function () {
        textFieldChangeHandler();
      });
      rootElement.on('input', '#projectStartDate', function () {
        eventbus.trigger('projectStartDate:notificationCheck', $(this).val());
      });
      rootElement.on('change', '#lisatiedot', function () {
        textFieldChangeHandler();
      });

      rootElement.on('change', '#tie', function () {
        reserveFieldChangeHandler();
      });
      rootElement.on('change', '#aosa', function () {
        reserveFieldChangeHandler();
      });
      rootElement.on('change', '#losa', function () {
        reserveFieldChangeHandler();
      });

      rootElement.on('click', '.btn-reserve', function () {
        var data;
        // Get data from HTML element
        if ($('#roadAddressProject').get(0)) {
          data = $('#roadAddressProject').get(0);
        } else {
          data = $('#reservedRoads').get(0);
        }

        // Set projectId
        if (currentProject && currentProject.id) {
          data.projectId = currentProject.id;
        } else {
          data.projectId = 0;
        }

        // Check if reserved
        projectCollection.checkIfReserved(data);

        // Fill form
        fillForm(projectCollection.getReservedParts(), projectCollection.getFormedParts());
        return false;
      });

      rootElement.on('click', '.btn-delete.reservedList', function () {
        var id = this.id;
        var roadNumber = this.attributes.roadNumber.value;
        var roadPartNumber = this.attributes.roadPartNumber.value;

        if (isProjectEditable()) {
          if (currentProject && projectCollection.getReservedParts()[id]) {
            new GenericConfirmPopup('Haluatko varmasti poistaa tieosan varauksen ja \r\nsiihen mahdollisesti tehdyt tieosoitemuutokset?', {
              successCallback: function () {
                removeReservedPart(roadNumber, roadPartNumber);
                removeFormedPart(roadNumber, roadPartNumber);
                _.defer(function () {
                  textFieldChangeHandler({ removedReserved: true });
                });
              }
            });
          } else {
            removeReservedPart(roadNumber, roadPartNumber);
            removeFormedPart(roadNumber, roadPartNumber);
          }
        }
      });

      rootElement.on('click', '.btn-delete.formedList', function () {
        var id = this.id;
        var roadNumber = this.attributes.roadNumber.value;
        var roadPartNumber = this.attributes.roadPartNumber.value;

        if (isProjectEditable()) {
          if (currentProject && projectCollection.getFormedParts()[id]) {
            new GenericConfirmPopup('Haluatko varmasti poistaa tieosan varauksen ja \r\nsiihen mahdollisesti tehdyt tieosoitemuutokset?', {
              successCallback: function () {
                removeFormedPart(roadNumber, roadPartNumber);
                _.defer(function () {
                  textFieldChangeHandler({ removedReserved: true });
                });
              }
            });
          } else {
            removeFormedPart(roadNumber, roadPartNumber);
          }
        }
      });

      rootElement.on('change', '.form-group', function () {
        rootElement.find('.action-selected-field').prop("hidden", false);
      });


      var closeProjectMode = function (changeLayerMode, noSave) {
        eventbus.trigger('roadAddressProject:startAllInteractions');
        applicationModel.setOpenProject(false);
        eventbus.trigger('projectChangeTable:hide');
        rootElement.find('header').toggle();
        rootElement.find('.wrapper').toggle();
        rootElement.find('footer').toggle();
        projectCollection.clearRoadAddressProjects();
        projectCollection.clearProjectErrors();
        eventbus.trigger('layer:enableButtons', true);
        if (changeLayerMode) {
          applicationModel.selectLayer('linkProperty', true, noSave);
          eventbus.trigger('roadAddressProject:clearOnClose');
          projectLinkLayer.hide();
        }
        eventbus.trigger('layers:removeProjectModeFeaturesFromTheLayers');
        applicationModel.removeSpinner();
      };

      var displayDeleteConfirmMessage = function (popupMessage) {
        new GenericConfirmPopup(popupMessage, {
          successCallback: function () {
            deleteProject();
            closeProjectMode(true);
          },
          closeCallback: function () {
            closeProjectMode(true);
          }
        });
      };

      var cancelChanges = function () {
        projectCollection.revertRoadAddressChangeType();
        projectCollection.setDirty([]);
        projectCollection.setTmpDirty([]);
        projectLinkLayer.clearHighlights();
        $('.wrapper').remove();
        eventbus.trigger('roadAddress:projectLinksEdited');
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        eventbus.trigger('roadAddressProject:reOpenCurrent');
      };

      var reOpenCurrent = function () {
        rootElement.empty();
        selectedProjectLinkProperty.setDirty(false);
        nextStage();
        if (currentProject.statusCode === 10 || currentProject.statusCode === 11 || currentProject.statusCode === 12) {
          buttonsWhenInspectingUneditableProject();
        } else {
          var projectErrors = projectCollection.getProjectErrors();
          var highPriorityProjectErrors = projectErrors.filter((error) => error.errorCode === 8);  // errorCode 8 means there are projectLinks in the project with status "NotHandled"
          buttonsWhenReOpenCurrent(projectErrors, highPriorityProjectErrors);
        }
        toggleAdditionalControls();
        eventbus.trigger('roadAddressProject:enableInteractions');
      };

      rootElement.on('click', '#saveEdit', function () {
        saveAndNext();
        eventbus.trigger('roadAddressProject:enableInteractions');
        eventbus.trigger("roadAddressProject:startAllInteractions");
      });

      rootElement.on('click', '#cancelEdit', function () {
        if (currentProject.isDirty) {
          new GenericConfirmPopup('Haluatko tallentaa tekemäsi muutokset?', {
            successCallback: function () {
              saveAndNext();
              eventbus.trigger('roadAddressProject:enableInteractions');
            },
            closeCallback: function () {
              cancelChanges();
            }
          });
        } else {
          cancelChanges();
        }
        eventbus.trigger('roadAddressProject:startAllInteractions');
      });
      rootElement.on('click', '#saveAndCancelDialogue', function (_eventData) {
        if (currentProject.isDirty) {
          new GenericConfirmPopup('Haluatko tallentaa tekemäsi muutokset?', {
            successCallback: function () {
              saveAndNext();
              closeProjectMode(true);
            },
            closeCallback: function () {
              closeProjectMode(true);
            }
          });
        } else {
          closeProjectMode(true);
        }
      });

      rootElement.on('click', '#editProjectSpan', currentProject, function () {
        applicationModel.setSelectedTool(ViiteEnumerations.Tool.Default.value);
        applicationModel.addSpinner();
        eventbus.trigger('projectChangeTable:hide');
        projectCollection.getProjectsWithLinksById(currentProject.id).then(function (result) {
          rootElement.empty();
          setTimeout(function () {
          }, 0);
          eventbus.trigger('roadAddress:openProject', result);
          if (applicationModel.isReadOnly()) {
            $('.edit-mode-btn:visible').click();
          }
          _.defer(function () {
            buttonsWhenOpenProject();
          });
        });
      });

      rootElement.on('click', '#closeProjectSpan', function () {
        closeProjectMode(true);
      });

      rootElement.on('click', '#deleteProjectSpan', function () {
        displayDeleteConfirmMessage("Haluatko varmasti poistaa tämän projektin?");
      });

      rootElement.on('click', '#generalNext', function () {
        if (currentProject.isDirty) {
          if (currentProject.id === 0) {
            createNewProject();
          } else {
            saveAndNext();
          }
        } else {
          nextStage();
          buttonsWhenOpenProject();
        }
        if (!isProjectEditable()) {
          $('.btn-pencil-edit').prop('disabled', true);
        }
      });

      var saveAndNext = function () {
        saveChanges();
        eventbus.once('roadAddress:projectSaved', function () {
          selectedProjectLinkProperty.setDirty(false);
          nextStage();
          buttonsWhenOpenProject();
        });
      };

      rootElement.on('change', '.input-required', function () {
        rootElement.find('.project-form button.next').attr('disabled', validator.formIsInvalid(rootElement));
        rootElement.find('.project-form button.save').attr('disabled', validator.formIsInvalid(rootElement));
        rootElement.find('#roadAddressProject button.btn-reserve').attr('disabled', validator.projDateEmpty(rootElement));
      });
    };
    bindEvents();
  };
}(this));
