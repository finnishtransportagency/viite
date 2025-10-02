(function (root) {
  root.ProjectEditForm = function (map, projectCollection, selectedProjectLinkProperty, projectLinkLayer, projectChangeTable, backend, startupParameters) {
    const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    const CalibrationCode = ViiteEnumerations.CalibrationCode;
    const editableStatus = [ViiteEnumerations.ProjectStatus.Incomplete.value, ViiteEnumerations.ProjectStatus.Unknown.value];
    const validEvks = _.map(ViiteEnumerations.EVKCodes, evk => evk);
    let selectedProjectLink = false;
    let editedNameByUser = false;
    const LinkSources = ViiteEnumerations.LinkGeomSource;
    const ProjectStatus = ViiteEnumerations.ProjectStatus;
    const formCommon = new FormCommon('');

    let endDistanceOriginalValue = '--';


    const transitionModifiers = (targetStatus, currentStatus) => {
      const mod = _.includes(targetStatus.transitionFrom, currentStatus) ? '' : 'disabled hidden';
      return currentStatus === targetStatus.value ? `${mod} selected` : mod;
    };

    const defineOptionModifiers = (option, selection) => {
      const roadAddressChangeType = selection[0].status;
      const targetRoadAddressChangeType = _.find(RoadAddressChangeType, 
        ls => ls.description === option || (option === '' && ls.value === 99)
      );
      return transitionModifiers(targetRoadAddressChangeType, roadAddressChangeType);
    };

    const selectedProjectLinkTemplate = (project, selected, errorMessage) => {
      const road = {
        roadNumber: selected[0].roadNumber,
        roadPartNumber: selected[0].roadPartNumber,
        trackCode: selected[0].trackCode
      };

      const roadLinkSources = _.chain(selected)
        .map(s => s.roadLinkSource)
        .uniq()
        .map(a => {
          const linkGeom = _.find(LinkSources, source => source.value === parseInt(a));
          return _.isUndefined(linkGeom) ? LinkSources.Unknown.descriptionFI : linkGeom.descriptionFI;
        })
        .uniq()
        .join(", ")
        .value();

      const selection = formCommon.selectedData(selected);
      return `
        <header>${formCommon.title(project.name)}</header>
        <div class="wrapper read-only">
          <div class="form form-horizontal form-dark">
            <div class="edit-control-group project-choice-group">
              ${insertErrorMessage(errorMessage)}
              ${formCommon.staticField('Lisätty järjestelmään', `${project.createdBy} ${project.startDate}`)}
              ${formCommon.staticField('Muokattu viimeksi', `${project.modifiedBy} ${project.dateModified}`)}
              ${formCommon.staticField('Geometrian lähde', roadLinkSources)}
              ${showLinkId(selected)}
              ${showLinkLength(selected)}
              <div class="form-group editable form-editable-roadAddressProject">
                ${selectionForm(project, selection, selected, road)}
                ${formCommon.changeDirection(selected, project)}
                ${formCommon.actionSelectedField()}
              </div>
            </div>
          </div>
        </div>
        <footer>${formCommon.actionButtons('project-', projectCollection.isDirty())}</footer>`;
    };

    const showLinkId = (selected) => {
      if (selected.length !== 1) return '';
      return String(formCommon.staticField('Linkin ID', selected[0].linkId));
    };

    const showLinkLength = (selected) => {
      if (selected.length === 1) {
        const length = Math.round(selected[0].endMValue - selected[0].startMValue);
        return String(formCommon.staticField('Geometrian pituus', length));
      } else {
        const combinedLength = _.reduce(selected, (sum, roadLink) => sum + 
          Math.round(roadLink.endMValue - roadLink.startMValue), 0);
        return `
          <div class="form-group-metadata">
            <p class="form-control-static asset-log-info-metadata">
              Geometrioiden yhteenlaskettu pituus: ${combinedLength}
            </p>
          </div>`;
      }
    };

    const selectionForm = (project, selection, selected, road) => {
      const devTool = _.includes(startupParameters.roles, 'dev') 
        ? formCommon.devAddressEditTool(selected, project) 
        : '';
      
      const defaultOption = selected[0].status === RoadAddressChangeType.NotHandled.value 
        ? RoadAddressChangeType.NotHandled.description 
        : RoadAddressChangeType.Undefined.description;
      
      const changeTypes = [
        RoadAddressChangeType.Unchanged,
        RoadAddressChangeType.Transfer,
        RoadAddressChangeType.New,
        RoadAddressChangeType.Terminated,
        RoadAddressChangeType.Numbering,
        RoadAddressChangeType.Revert
      ];

      const createOption = (type) => `
        <option 
          id="drop_0_${type.description}" 
          value="${type.description}" 
          ${defineOptionModifiers(type.description, selected)}>
          ${type.displayText}
        </option>`;

      return `
        <form id="roadAddressProjectForm" class="input-unit-combination form-group form-horizontal roadAddressProject">
          <label>Toimenpiteet,${selection}</label>
          <div class="input-unit-combination">
            <select class="action-select" id="dropDown_0" size="1">
              <option id="drop_0_" ${defineOptionModifiers(defaultOption, selected)}>Valitse</option>
              ${changeTypes.map(createOption).join('')}
            </select>
          </div>
          ${formCommon.newRoadAddressInfo(project, selected, selectedProjectLink, road)}
          ${devTool}
        </form>`;
    };

    const insertErrorMessage = (errorMessage) => {
      if (_.isUndefined(errorMessage) || errorMessage === "") return "";
      return addSmallLabelLowercase(`VIRHE: ${errorMessage}`);
    };

    const addSmallLabelLowercase = (label) => `<label class="control-label-small" style="text-transform: none">${label}</label>`;

    const emptyTemplateDisabledButtons = (project) => _.template(`
      <header>${formCommon.titleWithEditingTool(project)}</header>
      <div class="wrapper read-only">
        <div class="form form-horizontal form-dark">
          <label class="highlighted">JATKA VALITSEMALLA KOHDE KARTALTA.</label>
          <div class="form-group" id="project-errors"></div>
        </div>
      </div>
      <footer>${formCommon.actionButtons('project-', false)}</footer>
    `)();

    const isProjectPublishable = () => projectCollection.getPublishableStatus();
    const isProjectEditable = () => _.includes(editableStatus, projectCollection.getCurrentProject().project.statusCode);

    const checkInputs = () => {
      const rootElement = $('#feature-attributes');
      const inputs = rootElement.find('input');
      const pedestrianRoads = 70000;
      
      let filled = _.every(inputs, input => {
        if (input.type !== 'text') return true;
        if (input.value) return true;
        const isPedestrian = $('#tie')[0].value >= pedestrianRoads;
        return isPedestrian && input.id === 'roadName';
      });

      const trackCodeDropdown = $('#trackCodeDropdown')[0];
      filled = filled && trackCodeDropdown && trackCodeDropdown.value && trackCodeDropdown.value !== '99';

      const administrativeClassCodeDropdown = $('#administrativeClassDropdown')[0];
      filled = filled && 
        administrativeClassCodeDropdown && 
        administrativeClassCodeDropdown.value && 
        administrativeClassCodeDropdown.value !== '0' && 
        administrativeClassCodeDropdown.value !== '99';

      const updateButton = rootElement.find('.project-form button.update');
      updateButton.prop('disabled', !(filled && !projectChangeTable.isChangeTableOpen()));
    };

    // This is called when a project link is selected to display the correct change type in the dropdown
    // It also handles the visibility of the new road address form and the change direction div
    const changeDropDownValue = function (statusCode) {
      const dropdown_0_new = $("#dropDown_0 option[value=" + RoadAddressChangeType.New.description + "]");
      const rootElement = $('#feature-attributes');
      switch (statusCode) {
        case RoadAddressChangeType.Unchanged.value:
          dropdown_0_new.prop('disabled', true);
          $("#dropDown_0 option[value=" + RoadAddressChangeType.Unchanged.description + "]").attr('selected', 'selected').change();
          break;
        case RoadAddressChangeType.New.value:
          dropdown_0_new.attr('selected', 'selected').change();
          projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
          rootElement.find('.new-road-address').prop("hidden", false);
          if (selectedProjectLink[0].id !== 0)
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
          break;
        case RoadAddressChangeType.Transfer.value:
          dropdown_0_new.prop('disabled', true);
          $("#dropDown_0 option[value=" + RoadAddressChangeType.Transfer.description + "]").attr('selected', 'selected').change();
          rootElement.find('.changeDirectionDiv').prop("hidden", true); // TODO remove this line when Velho integration can handle road reversing
          break;
        case RoadAddressChangeType.Numbering.value:
          $("#dropDown_0 option[value=" + RoadAddressChangeType.Numbering.description + "]").attr('selected', 'selected').change();
          break;
        case RoadAddressChangeType.Terminated.value:
          $("#dropDown_0 option[value=" + RoadAddressChangeType.Terminated.description + "]").attr('selected', 'selected').change();
          break;
        default:
          break;
      }
      $('#discontinuityDropdown').val(selectedProjectLink[selectedProjectLink.length - 1].discontinuity);
    };

    const fillDistanceValues = (selectedLinks) => {
      const beginDistance = $('#beginDistance');
      const endDistance = $('#endDistance');
      
      if (selectedLinks.length === 1 && selectedLinks[0].calibrationCode === CalibrationCode.AtBoth.value) {
        beginDistance.val(selectedLinks[0].addrMRange.start);
        if (isProjectEditable()) {
          endDistance.prop('readonly', false).val(selectedLinks[0].addrMRange.end);
        } else {
          endDistance.val(selectedLinks[0].addrMRange.end);
        }
      } else {
        const orderedByStartM = _.sortBy(selectedLinks, l => l.addrMRange.start);
        if (orderedByStartM[0].calibrationCode === CalibrationCode.AtBeginning.value) {
          beginDistance.val(orderedByStartM[0].addrMRange.start);
        }
        
        const lastLink = orderedByStartM[orderedByStartM.length - 1];
        if (lastLink.calibrationCode === CalibrationCode.AtEnd.value) {
          if (isProjectEditable()) {
            endDistance.prop('readonly', false).val(lastLink.addrMRange.end);
          } else {
            endDistance.val(lastLink.addrMRange.end);
          }
          endDistanceOriginalValue = lastLink.addrMRange.end;
        }
      }
    };

    const disableFormInputs = () => {
      if (!isProjectEditable()) {
        const project = projectCollection.getCurrentProject().project;
        const formSelects = $('#roadAddressProjectForm select, #roadAddressProjectFormCut select');
        const updateButtons = $('.update, .btn-pencil-edit');
        
        formSelects.prop('disabled', true);
        updateButtons.prop('disabled', true);
        
        if ([ProjectStatus.InUpdateQueue.value, ProjectStatus.UpdatingToRoadNetwork.value].includes(project.statusCode)) {
          $(':input').prop('disabled', true);
          $('.project-form button.cancelLink').prop('disabled', false);
        }
      }
    };

    const setFormDirty = () => {
      selectedProjectLinkProperty.setDirty(true);
      eventbus.trigger('roadAddressProject:toggleEditingRoad', false);
    };

    const bindEvents = () => {
      const rootElement = $('#feature-attributes');

      eventbus.on('projectLink:clicked', (selected) => {
        selectedProjectLink = selected;
        const currentProject = projectCollection.getCurrentProject();
        formCommon.clearInformationContent();
        rootElement.html(selectedProjectLinkTemplate(currentProject.project, selectedProjectLink));
        formCommon.replaceAddressInfo(backend, selectedProjectLink, currentProject.project.id);
        updateForm();
        
        // disable form interactions (action dropdown, save and cancel buttons) if change table is open
        if (projectChangeTable.isChangeTableOpen()) {
          formCommon.disableFormInteractions();
        }
        
        _.defer(() => {
          $('#beginDistance').on('change', (changedData) => {
            eventbus.trigger('projectLink:editedBeginDistance', changedData.target.value);
          });
          $('#endDistance').on('change', (changedData) => {
            eventbus.trigger('projectLink:editedEndDistance', changedData.target.value);
          });
        });
      });

      const updateForm = () => {
        changeDropDownValue(selectedProjectLink[0].status);
        checkInputs();
        
        disableFormInputs();
        
        const projectLinkMaxByEndAddressM = _.maxBy(
          selectedProjectLink, 
          projectLink => projectLink.addrMRange.end
        );
        
        // If there are non-calculated new links, display the lowest value of discontinuity in selection
        const selectedDiscontinuity = projectLinkMaxByEndAddressM.addrMRange.end === 0
          ? _.minBy(selectedProjectLink, pl => pl.discontinuity).discontinuity
          : projectLinkMaxByEndAddressM.discontinuity;
          
        $('#discontinuityDropdown').val(selectedDiscontinuity.toString());
      };

      eventbus.on('projectLink:errorClicked', (selected, errorMessage) => {
        selectedProjectLink = [selected[0]];
        const currentProject = projectCollection.getCurrentProject();
        formCommon.clearInformationContent();
        rootElement.html(selectedProjectLinkTemplate(currentProject.project, selectedProjectLink, errorMessage));
        formCommon.replaceAddressInfo(backend, selectedProjectLink);
        updateForm();
      });

      eventbus.on('roadAddress:projectFailed', () => {
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectLinksUpdateFailed', (errorCode) => {
        applicationModel.removeSpinner();
        const errorMessages = {
          400: 'Päivitys epäonnistui puutteelisten tietojen takia. Ota yhteyttä järjestelmätukeen.',
          401: 'Sinulla ei ole käyttöoikeutta muutoksen tekemiseen.',
          412: 'Täyttämättömien vaatimusten takia siirtoa ei saatu tehtyä. Ota yhteyttä järjestelmätukeen.',
          500: 'Siirto ei onnistunut taustajärjestelmässä tapahtuneen virheen takia, ota yhteyttä järjestelmätukeen.'
        };
        
        const message = errorMessages[errorCode] || 
          'Siirto ei onnistunut taustajärjestelmässä tapahtuneen tuntemattoman virheen takia, ota yhteyttä järjestelmätukeen.';
        
        return new ModalConfirm(message);
      });

      eventbus.on('roadAddress:projectLinksUpdated', (response) => {
        //eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        projectCollection.setDirty([]);
        selectedProjectLinkProperty.setCurrent([]);
        selectedProjectLinkProperty.setDirty(false);
        selectedProjectLink = false;
        selectedProjectLinkProperty.cleanIds();
        
        const { projectErrors } = response;
        // show disabled buttons
        rootElement.html(emptyTemplateDisabledButtons(projectCollection.getCurrentProject().project));
        
        if (Object.keys(projectErrors).length === 0) {
          // if no (high priority) validation errors are present, then show recalculate button and remove title
          formCommon.setDisabledAndTitleAttributesById("recalculate-button", false, "");
          formCommon.setInformationContent();
          formCommon.setInformationContentText("Päivitä etäisyyslukemat jatkaaksesi projektia.");
        } else {
          projectCollection.setAndWriteProjectErrorsToUser(projectErrors);
        }
        formCommon.toggleAdditionalControls();
        // changes made to project links, set recalculated flag to false
        eventbus.trigger('roadAddressProject:setRecalculatedAfterChangesFlag', false);
        applicationModel.removeSpinner();
        if (typeof response !== 'undefined' && typeof response.publishable !== 'undefined' && response.publishable) {
          eventbus.trigger('roadAddressProject:projectLinkSaved', response.id, response.publishable);
        } else {
          eventbus.trigger('roadAddressProject:projectLinkSaved');
        }
      });

      eventbus.on('roadAddress:projectSentSuccess', () => {
        new ModalConfirm('Muutoksia viedään tieosoiteverkolle.');
        // TODO: make more generic layer change/refresh
        applicationModel.selectLayer('linkProperty');
        selectedProjectLinkProperty.close();
        projectCollection.clearRoadAddressProjects();
        projectCollection.reset();
        applicationModel.setOpenProject(false);
        
        // Trigger cleanup and UI update events
        [
          'layer:enableButtons',
          'roadAddressProject:deselectFeaturesSelected',
          'roadLinks:refreshView'
        ].forEach(event => eventbus.trigger(event, event === 'layer:enableButtons'));
      });

      eventbus.on('roadAddress:projectSentFailed', (error) => {
        new ModalConfirm(error);
      });

      eventbus.on('roadAddress:projectLinksCreateSuccess', () => {
        eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        selectedProjectLinkProperty.cleanIds();
        rootElement.find('.changeDirectionDiv').prop('hidden', false);
      });

      eventbus.on('roadAddress:changeDirectionFailed', (error) => {
        new ModalConfirm(error);
      });

      rootElement.on('click', '.changeDirection', () => {
        const projectId = projectCollection.getCurrentProject().project.id;
        projectCollection.changeNewProjectLinkDirection(projectId, selectedProjectLinkProperty.get());
      });

      eventbus.on('roadAddress:projectLinksSaveFailed', (result) => {
        new ModalConfirm(result.toString());
      });

      const cancelChanges = () => {
        projectCollection.revertRoadAddressChangeType();
        projectCollection.setDirty([]);
        projectCollection.setTmpDirty([]);
        projectLinkLayer.clearHighlights();
        selectedProjectLinkProperty.cleanIds();
        selectedProjectLinkProperty.clean();
        $('.wrapper').remove();
        
        // Trigger cleanup events
        [
          'roadAddress:projectLinksEdited',
          'roadAddressProject:toggleEditingRoad',
          'roadAddressProject:reOpenCurrent'
        ].forEach(event => eventbus.trigger(event, event.includes('toggleEditingRoad') ? true : undefined));
      };

      eventbus.on('roadAddressProject:discardChanges', cancelChanges);

      const canChangeDirection = () => {
        const hasTerminatedOrNotHandled = _.some(selectedProjectLink, link => link.status === RoadAddressChangeType.Terminated.value || 
          link.status === RoadAddressChangeType.NotHandled.value);
        rootElement.find('.changeDirectionDiv').prop('hidden', hasTerminatedOrNotHandled);
      };

      const saveChanges = () => {
        const evkValue = parseInt($('#evk')[0].value);
        const isValidEvk = _.some(validEvks, evk => evk.value === evkValue);

        console.log("EVK VALUE :: ", evkValue)
        console.log("IS VALID :: ", isValidEvk)

        if (!isValidEvk) {
          return new ModalConfirm('Tarkista antamasi EVK-koodi. Annettu arvo on virheellinen.');
        }

        const statusDropdownValue = $('#dropDown_0').val();
        const changeType = _.find(RoadAddressChangeType, obj => obj.description === statusDropdownValue);

        if (changeType.value === RoadAddressChangeType.Revert.value) {
          projectCollection.revertChangesRoadlink(selectedProjectLink);
        } else {
          const linksToSave = projectCollection.getTmpDirty().length > 0 
            ? projectCollection.getTmpDirty() 
            : selectedProjectLink;
          const isEndDistanceModified = projectCollection.getTmpDirty().length > 0 
            ? isEndDistanceTouched() 
            : false;
            
          projectCollection.saveProjectLinks(linksToSave, changeType.value, isEndDistanceModified);
        }
        return true;
      };

      const isEndDistanceTouched = () => {
        const endDistance = $('#endDistance')[0];
        if (!endDistance) return false;
        
        const changedValue = Number(endDistance.value);
        const orderedByStartM = _.sortBy(selectedProjectLink, l => -l.addrMRange.start);
        
        // Check if end distance is a valid number and has changed from the original value
        return !isNaN(changedValue) && 
               typeof changedValue === 'number' &&
               changedValue !== orderedByStartM[0].addrMRange.end;
      };

      rootElement.on('change', '#endDistance', (eventData) => {
        setFormDirty();
        const changedValue = parseInt(eventData.target.value);
        const originalValue = parseInt(endDistanceOriginalValue);
        const shouldShowWarning = !isNaN(changedValue) && !isNaN(originalValue) && 
                               changedValue !== originalValue;
        $('#manualCPWarning').css('display', shouldShowWarning ? 'inline-block' : 'none');
      });

      // Handle form control changes
      rootElement.on('change', '#administrativeClassDropdown, .form-select-control', setFormDirty);

      rootElement.on('click', '.project-form button.update', () => {
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        saveChanges();
      });

      const updateFormControls = (changeType) => {
        // Common form controls
        const formControls = {
          tie: $('#tie'),
          osa: $('#osa'),
          trackCode: $('#trackCodeDropdown'),
          discontinuity: $('#discontinuityDropdown'),
          adminClass: $('#administrativeClassDropdown')
        };

        // UI elements
        const uiElements = {
          devTool: rootElement.find('.dev-address-tool'),
          newRoadAddress: rootElement.find('.new-road-address'),
          changeDirection: rootElement.find('.changeDirectionDiv'),
          distanceValue: rootElement.find('#distanceValue'),
          updateButton: rootElement.find('.project-form button.update')
        };

        // Enable/disable common fields
        const enableFields = (enabled) => {
          Object.values(formControls).forEach(control => control.prop('disabled', !enabled));
        };

        // Map link data for project collection
        const mapLinkData = (link, status) => ({
          id: link.id,
          linkId: link.linkId,
          status: status,
          roadLinkSource: link.roadLinkSource,
          points: link.points,
          linearLocationId: link.linearLocationId
        });

        // Handle different change types
        switch (changeType) {
          case RoadAddressChangeType.Terminated.description:
            enableFields(false);
            uiElements.devTool.prop('hidden', false);
            uiElements.newRoadAddress.prop('hidden', true);
            uiElements.changeDirection.prop('hidden', true);
            
            projectCollection.setDirty(selectedProjectLink.map(link => mapLinkData(link, RoadAddressChangeType.Terminated.value)));
            break;

          case RoadAddressChangeType.New.description:
            enableFields(true);
            uiElements.devTool.prop('hidden', false);
            uiElements.newRoadAddress.prop('hidden', false);
            projectCollection.setDirty(selectedProjectLink.map(link => mapLinkData(link, RoadAddressChangeType.New.value)));

            if (selectedProjectLink[0].id !== -1) {
              fillDistanceValues(selectedProjectLink);
              uiElements.changeDirection.prop('hidden', false);
              uiElements.distanceValue.prop('hidden', false);
            }
            break;

          case RoadAddressChangeType.Unchanged.description:
            formControls.tie.prop('disabled', true);
            formControls.osa.prop('disabled', true);
            formControls.trackCode.prop('disabled', true);
            formControls.discontinuity.prop('disabled', false);
            formControls.adminClass.prop('disabled', false);
            
            uiElements.devTool.prop('hidden', false);
            uiElements.newRoadAddress.prop('hidden', false);
            uiElements.changeDirection.prop('hidden', true);
            
            projectCollection.setDirty(selectedProjectLink.map(link => mapLinkData(link, RoadAddressChangeType.Unchanged.value)));
            break;

          case RoadAddressChangeType.Transfer.description:
            enableFields(true);
            uiElements.newRoadAddress.prop('hidden', false);
            uiElements.devTool.prop('hidden', false);
            projectCollection.setDirty(selectedProjectLink.map(link => mapLinkData(link, RoadAddressChangeType.Transfer.value)));
            break;

          case RoadAddressChangeType.Numbering.description:
            uiElements.devTool.prop('hidden', false);
            new ModalConfirm("Numerointi koskee kokonaista tieosaa. Valintaasi on tarvittaessa laajennettu koko tieosalle.");
            formControls.trackCode.prop('disabled', true);
            formControls.discontinuity.prop('disabled', false);
            formControls.adminClass.prop('disabled', true);
            
            projectCollection.setDirty(selectedProjectLink.map(link => mapLinkData(link, RoadAddressChangeType.Numbering.value)));
            uiElements.newRoadAddress.prop('hidden', false);
            uiElements.updateButton.prop('disabled', false);
            canChangeDirection();
            break;

          case RoadAddressChangeType.Revert.description:
            uiElements.devTool.prop('hidden', true);
            uiElements.newRoadAddress.prop('hidden', true);
            uiElements.changeDirection.prop('hidden', true);
            uiElements.updateButton.prop('disabled', false);
            break;

          default:
            uiElements.devTool.prop('hidden', true);
            uiElements.newRoadAddress.prop('hidden', true);
            uiElements.changeDirection.prop('hidden', true);
            break;
        }

        // Update the project collection with the dirty state
        projectCollection.setTmpDirty(projectCollection.getDirty());
      };

      // Initialize event handlers
      rootElement.on('change', '#roadAddressProjectForm #dropDown_0', (e) => {
        updateFormControls(e.target.value);
      });

      rootElement.on('change', '#trackCodeDropdown, #administrativeClassDropdown', checkInputs);
      
      rootElement.on('change', '.form-group', () => {
        rootElement.find('.action-selected-field').prop('hidden', false);
      });

      rootElement.on('click', '.project-form button.cancelLink', cancelChanges);

      rootElement.on('click', '.project-form button.send', () => {
        new GenericConfirmPopup("Haluatko hyväksyä projektin muutokset osaksi tieosoiteverkkoa?", {
          successCallback: () => {
            projectCollection.publishProject();
            closeProjectMode(true, true);
          },
          closeCallback: () => {}
        });
      });

      const closeProjectMode = (changeLayerMode, noSave) => {
        eventbus.trigger('roadAddressProject:startAllInteractions');
        eventbus.trigger('projectChangeTable:hide');
        applicationModel.setOpenProject(false);
        
        // Toggle UI elements
        ['header', '.wrapper', 'footer'].forEach(selector => {
          rootElement.find(selector).toggle();
        });
        
        projectCollection.clearRoadAddressProjects();
        eventbus.trigger('layer:enableButtons', false);
        eventbus.trigger('form:showPropertyForm');
        
        if (changeLayerMode) {
          eventbus.trigger('roadAddressProject:clearOnClose');
          applicationModel.selectLayer('linkProperty', true, noSave);
        }
      };

      rootElement.on('click', '.project-form button.show-changes', function () {
        projectChangeTable.show();
        if (isProjectPublishable() && isProjectEditable()) {
          formCommon.setInformationContent();
          formCommon.setInformationContentText("Validointi ok. Voit tehdä tieosoitteen muutosilmoituksen tai jatkaa muokkauksia.");
          formCommon.setDisabledAndTitleAttributesById("send-button", false, "");
        }
      });

      rootElement.on('click', '.project-form button.validate', function () {
        var currentProject = projectCollection.getCurrentProject();
        // add spinner
        applicationModel.addSpinner();
        backend.validateProject(currentProject.project.id, function (response) {
          // validation did not throw exceptions in the backend
          if (response.success) {
            // set project errors that were returned by the backend validations and write them to user (removes the spinner also)
            projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);
            if (Object.keys(response.validationErrors).length === 0) {
              // if no validation errors are present, show changes button and remove title
              formCommon.setDisabledAndTitleAttributesById("changes-button", false, "");
            }
          } // if something went wrong during validation, show error to the user
          else if ('validationErrors' in response && !_.isEmpty(response.validationErrors)) {
            // set project errors that were returned by the backend validations and write them to user (removes the spinner also)
            projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);
          } else {
            new ModalConfirm(response.errorMessage);
            applicationModel.removeSpinner();
          }
        });
      });

      // when recalculate button is clicked
      rootElement.on('click', '.project-form button.recalculate', function () {
        // clear the information text
        $('#information-content').empty();
        // get current project
        var currentProject = projectCollection.getCurrentProject();
        // add spinner
        applicationModel.addSpinner();

        $('.validation-warning').remove();
        // fire backend call to recalculate and validate the current project with the project id
        backend.recalculateAndValidateProject(currentProject.project.id, function (response) {
          // if recalculation and validation did not throw exceptions in the backend
          if (response.success) {

            const trackGeometryLengthDeviationErrorCode = 38;
            if (response.validationErrors.filter((error) => error.errorCode === trackGeometryLengthDeviationErrorCode).length > 0) {
              const trackGeometryLengthDeviationError = response.validationErrors.filter((error) => error.errorCode === trackGeometryLengthDeviationErrorCode)[0];
              // "Ajoratojen geometriapituuksissa yli 20% poikkeama."
              new GenericConfirmPopup(trackGeometryLengthDeviationError.errorMessage, {
                type: "alert"
              });
              $('.form,.form-horizontal,.form-dark').append('<label class="validation-warning">' +trackGeometryLengthDeviationError.errorMessage + "<br> LinkId: " + trackGeometryLengthDeviationError.info + '</label>');
              response.validationErrors = response.validationErrors.filter((error) => error.errorCode !== trackGeometryLengthDeviationErrorCode);
            }

            // set project errors that were returned by the backend validations and write them to user (removes the spinner also)
            projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);

            if (Object.keys(response.validationErrors).length === 0) {
              // if no validation errors are present, show changes button and remove title
              formCommon.setDisabledAndTitleAttributesById("changes-button", false, "");
            }
            // fetch the recalculated project links and redraw map
            projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, currentProject.project.id, projectCollection.getPublishableStatus());
            // disable recalculate button after recalculation is done
            formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "Etäisyyslukemat on päivitetty");
            // project was recalculated, set recalculated flag to true
            eventbus.trigger('roadAddressProject:setRecalculatedAfterChangesFlag', true);
          }
          // if something went wrong during recalculation or validation, show error to user
          else if ('validationErrors' in response && !_.isEmpty(response.validationErrors)) {
            // set project errors that were returned by the backend validations and write them to user (removes the spinner also)
            projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);
          } else {
            new ModalConfirm(response.errorMessage);
            applicationModel.removeSpinner();
          }
        });
      });

      rootElement.on('input', '.form-control.small-input', function (event) {
        var dropdown_0 = $('#dropDown_0');
        var roadNameField = $('#roadName');
        checkInputs();
        setFormDirty();
        if (event.target.id === "tie" && (dropdown_0.val() === 'New' || dropdown_0.val() === 'Transfer' || dropdown_0.val() === 'Numbering')) {
          rootElement.find('.project-form button.update').prop("disabled", true);
          backend.getRoadName($(this).val(), projectCollection.getCurrentProject().project.id, function (data) {
            if (data.roadName) {
              editedNameByUser = false;
              roadNameField.val(data.roadName).change();
              if (data.isCurrent) {
                roadNameField.prop('disabled', true);
              } else {
                roadNameField.prop('disabled', false);
              }
            } else {
              if (roadNameField.prop('disabled') || !editedNameByUser) {
                $('#roadName').val('').change();
                editedNameByUser = false;
              }
              roadNameField.prop('disabled', false);
            }
            checkInputs();
          });
        }
      });

      rootElement.on('keyup, input', '#roadName', function () {
        checkInputs();
        editedNameByUser = $('#roadName').val !== '';
      });

      // show project errors' link id list in a popup window
      rootElement.on('click', '.linkIdList', function (event) {
        const error = projectCollection.getProjectErrors()[event.currentTarget.id];

          const linkIdsText = error.linkIds.join(', ');
          new GenericConfirmPopup(linkIdsText, {type: "alert"});

      });

      rootElement.on('click', '.projectErrorButton', function (event) {
        var error = projectCollection.getProjectErrors()[event.currentTarget.id];
        var roadPartErrors = [
          ViiteEnumerations.ProjectError.TerminationContinuity.value,
          ViiteEnumerations.ProjectError.DoubleEndOfRoad.value,
          ViiteEnumerations.ProjectError.RoadNotReserved.value
        ];
        if (_.includes(roadPartErrors, error.errorCode)) {
          var attributeElement = $('#feature-attributes');
          attributeElement.find('#editProjectSpan').click();
        } else {
          eventbus.trigger('projectCollection:clickCoordinates', event, map);
          if (error.errorMessage !== "") {
            projectCollection.getProjectLinks().then(function (projectLinks) {
              var projectLinkIds = projectLinks.map(function (link) {
                return link.linkId;
              });
              if (_.every(error.linkIds, function (link) {
                return projectLinkIds.indexOf(link) > -1;
              })) {
                selectedProjectLinkProperty.openWithErrorMessage(error.ids, error.errorMessage);
              } else {
                new ModalConfirm("Sinun täytyy varata tieosa projektille, jotta voit korjata sen.");
              }
            });
          }
        }
      });

      rootElement.on('input', '#addrStart, #addrEnd', function () {
        const start = Number(document.getElementById("addrStart").value) || 0;
        const end = Number(document.getElementById("addrEnd").value) || 0;
        const res = end - start;
        document.getElementById("addrLength").textContent = res.toString();
      });

      rootElement.on('input', '#origAddrStart, #origAddrEnd', function () {
        const start = Number(document.getElementById("origAddrStart").value) || 0;
        const end = Number(document.getElementById("origAddrEnd").value) || 0;
        const res = end - start;
        document.getElementById("origAddrLength").textContent = res.toString();
      });

    };
    bindEvents();
  };
}(this));
