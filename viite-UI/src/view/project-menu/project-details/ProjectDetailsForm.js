/*
 * ProjectDetailsForm: Configuration form for project metadata (name, start date, additional info).
 * Manages project creation and editing, road part reservations, and table displays.
 * Tracks unsaved changes state and integrates with ProjectMenu for state transitions.
 * Component is rebuilt on each show() to support disposable lifecycle pattern.
 */
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { DatePicker } from '@components/date-picker/DatePicker.js';
import { numberInput } from '@components/number-input/NumberInput.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { ValidationUtils } from './ValidationUtils.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/eventbus.js';
import { zoomlevels } from '@utils/ZoomLevels.js';

export function ProjectDetailsForm(callbacks = {}) {
  const applicationModel = callbacks.applicationModel;
    let startDatePicker = null;
    const projectCollection = callbacks.projectCollection;
    const map = callbacks.map;
    
    // Track unsaved changes state
    let hasUnsavedChanges = false;
    let projectValidationFailedHandler = null;
    let projectFailedHandler = null;

    const deleteRoadPartButton = function (roadNumber, roadPartNumber, selector) {
      return `
        <button class="delete btn-delete ${selector} delete-btn" 
          data-roadNumber="${roadNumber}" 
          data-roadPartNumber="${roadPartNumber}">
          <i class="fas fa-trash-alt fa-lg"></i>
        </button>`;
    };

    const roadPartList = function (list, type) {
      if (!list || !Array.isArray(list)) return '';
      const isReserved = type === 'reserved';
      const selector = isReserved ? 'reservedList' : 'formedList';
      const props = isReserved ? 
        { length: 'currentLength', disc: 'currentDiscontinuity', evk: 'currentEvk' } : 
        { length: 'newLength',    disc: 'newDiscontinuity',      evk: 'newEvk'     };

      return _.map(list, (line, _index) => {
        const lengthVal = line[props.length];
        if (_.isUndefined(lengthVal)) return '';

      return `
          <tr class="form-reserved-roads-list road-table-row">
            <td class="road-table-cell-center road-table-cell-no-wrap">${line.roadNumber || ''}</td>
            <td class="road-table-cell-center road-table-cell-no-wrap">${line.roadPartNumber || ''}</td>
            <td class="road-table-cell-center">${lengthVal || ''}</td>
            <td class="road-table-cell-center">${line[props.disc] || ''}</td>
            <td class="road-table-cell-center">${line[props.evk] || ''}</td>
            <td class="road-table-cell-delete">
              ${deleteRoadPartButton(line.roadNumber, line.roadPartNumber, selector)}
            </td>
          </tr>`;
        }).join('');
    };

    const generateTableStructure = function (id, title, rowsHtml) {
      return `
        <div class="form-result">
          <label class="form-result-label">${title}</label>
          <table class="table road-table">
            <colgroup>
              <col class="col-10">
              <col class="col-10">
              <col class="col-17-5">
              <col class="col-17-5">
              <col class="col-35">
              <col class="col-10">
            </colgroup>
            <thead class="road-table-header">
              <tr class="road-table-row">
                <th class="road-table-cell-center road-table-cell-no-wrap">TIE</th>
                <th class="road-table-cell-center road-table-cell-no-wrap">OSA</th>
                <th class="road-table-cell-center road-table-cell-no-wrap">PITUUS</th>
                <th class="road-table-cell-center">JATKUU</th>
                <th class="road-table-cell-center">ELINVOIMAKESKUS</th>
                <th class="road-table-cell"></th> 
              </tr>
            </thead>
            <tbody id="${id}" class="road-table-body">
              ${rowsHtml || ''}
            </tbody>
          </table>
        </div>`;
    };

    const renderForm = function (project, isNewProject, reservedRoadsHtml, formedRoadsHtml) {
      const createdString = isNewProject ? '-' : `${project.createdBy} ${project.startDate}`;
      const modifiedString = isNewProject ? '-' : `${project.modifiedBy} ${project.dateModified}`;
      if (startDatePicker) startDatePicker.destroy();
      startDatePicker = new DatePicker({
        id: 'projectStartDate',
        className: 'form-control',
        containerClass: '', 
        value: project.startDate || '',
        required: true
      });

      const reservationControls = function() {
        return `
          <div class="reservation-container">
          <label class="reservation-label">Tieosat</label>
            <div class="reservation-column">
              <label>Tie</label>
              ${numberInput('tie', 5)}
            </div>

            <div class="reservation-column">
              <label>Aosa</label>
              ${numberInput('aosa', 3)}
            </div>

            <div class="reservation-column">
              <label>Losa</label>
              ${numberInput('losa', 3)}
            </div>

            <button class="btn-primary btn-reserve" disabled>Varaa</button>
          </div>
        `;
      };

      const metadataForm = (projectData) => {
        const info = projectData.additionalInfo || "";
        
        return `
          <form id="roadAddressProject" class="metadata-form">
            <div class="form-row">
              <div class="form-group field-name">
                <label>*Nimi</label>
                <input autocomplete="off" type="text" class="form-control" id="nimi" maxlength="32" value="${projectData.name || ''}"/>
              </div>
              <div class="form-group field-date">
                <label>*Alkupvm</label>
                ${startDatePicker.render()}
              </div>
            </div>

            <div class="form-check-date-notifications"> 
              <p id="projectStartDate-validation-notification"></p>
            </div>

            <div class="form-group">
              <label class="control-label">Lisätiedot</label>
              <textarea class="form-control large-input" id="lisatiedot">${info}</textarea>
            </div>
          </form>
        `;
      };
                  
      return `
        <div class="form-dark">

          <div>Lisätty järjestelmään : ${createdString}</div>
          <div>Muokattu viimeksi : ${modifiedString}</div>

          ${metadataForm(project)}
          ${reservationControls()}
          ${generateTableStructure('reservedRoads', 'PROJEKTIIN VARATUT TIEOSAT', reservedRoadsHtml)}
          
          ${!isNewProject ? `
            <div class="new-reserved-roads">
              ${generateTableStructure('newReservedRoads', 'PROJEKTISSA MUODOSTETUT TIEOSAT:', formedRoadsHtml)}
            </div>
          ` : ''}
        </div>`;
    };

    const renderFooter = function (project, isEditMode = false) {
      const ProjectStatus = ViiteEnumerations.ProjectStatus;
      const isProjectPublished = isPublishedProject(project);
      const isFormIncomplete = !(project && project.name && project.startDate);
      const isNewProject = project.name === '';
      const isSaveDisabled = isProjectPublished || isFormIncomplete || !hasUnsavedChanges;
      const showDelete = !isNewProject && ![ProjectStatus.Accepted.value, ProjectStatus.InUpdateQueue.value, ProjectStatus.UpdatingToRoadNetwork.value].includes(project.statusCode);
      const actionButton = (isNewProject || !isEditMode)
        ? `<button id="generalNext" class="save btn-primary btn-save action-button" ${isFormIncomplete ? 'disabled' : ''}>Jatka toimenpiteisiin</button>`
        : `<button id="saveProject" class="save btn-primary btn-save action-button" ${isSaveDisabled ? 'disabled' : ''}>Tallenna</button>`;
      
      const cancelButton = (isNewProject || !isEditMode)
        ? `<button id="saveAndCancelDialogue" class="cancel btn-cancel">Poistu</button>`
        : `<button id="cancelEdit" class="cancel btn-cancel">Peruuta</button>`;
      
      return `
        <div class="footer-project-details ${!showDelete ? 'no-delete' : ''}" id="actionButtons">
          ${showDelete ? `<span id="deleteProjectSpan" class="deleteSpan">POISTA PROJEKTI <i id="deleteProject_${project.id}" class="fas fa-trash-alt" value="${project.id}"></i></span>` : ''}
          ${actionButton}
          ${cancelButton}
        </div>`;
    };

    const updateReserveButtonState = function () {
      const $form = $('#roadAddressProject');
      const validationUtils = new ValidationUtils();
      const isRoadPartInvalidResult = validationUtils.isRoadPartInvalid($form);
      const dateValue = $('#projectStartDate').val() || '';
      const hasDate = dateValue.trim() !== '';
      const dateRegex = /^\d{1,2}.\d{1,2}.\d{4}$/;
      const isDateValid = !hasDate || dateRegex.test(dateValue);
      const shouldDisable = isRoadPartInvalidResult || !hasDate || !isDateValid;
      
      $('.btn-reserve').prop('disabled', shouldDisable);
    };

    const updateContinueButtonState = function (_project) {
      const nameValue = $('#nimi').val() || '';
      const dateValue = $('#projectStartDate').val() || '';
      const hasName = nameValue.trim() !== '';
      const hasDate = dateValue.trim() !== '';
      const isFormIncomplete = !hasName || !hasDate;
      const dateRegex = /^\d{1,2}.\d{1,2}.\d{4}$/;
      const isDateValid = !hasDate || dateRegex.test(dateValue);
      const shouldDisableButton = isFormIncomplete || !isDateValid;
      $('#generalNext').prop('disabled', shouldDisableButton);
    };

    const isPublishedProject = function (projectData) {
      const ProjectStatus = ViiteEnumerations.ProjectStatus;
      return Boolean(
        projectData &&
        !_.isUndefined(projectData.statusCode) &&
        ![
          ProjectStatus.Incomplete.value,
          ProjectStatus.ErrorInViite.value,
          ProjectStatus.Unknown.value
        ].includes(projectData.statusCode)
      );
    };

    const isProjectFormIncomplete = function () {
      const nameValue = $('#nimi').val() || '';
      const dateValue = $('#projectStartDate').val() || '';
      const hasName = nameValue.trim() !== '';
      const hasDate = dateValue.trim() !== '';
      const dateRegex = /^\d{1,2}.\d{1,2}.\d{4}$/;
      const isDateValid = !hasDate || dateRegex.test(dateValue);
      return !hasName || !hasDate || !isDateValid;
    };

    const updateSaveButtonState = function (projectData) {
      const shouldDisable = isPublishedProject(projectData) || isProjectFormIncomplete() || !hasUnsavedChanges;
      $('#saveProject').prop('disabled', shouldDisable);
    };
    
    const markAsChanged = function() {
      hasUnsavedChanges = true;
    };
    
    const markAsSaved = function() {
      hasUnsavedChanges = false;
    };
    
    const getUnsavedChangesState = function() {
      return hasUnsavedChanges;
    };

    const getBackendErrorMessage = function(result, fallback) {
      if (!result) return fallback;
      if (typeof result === 'string') return result;
      if (result.errorMessage) return result.errorMessage;
      return fallback;
    };

    const navigateToActionMenu = function(projectData) {
      if (typeof callbacks.continueToActions === 'function') {
        callbacks.continueToActions({ project: projectData });
      }
    };

    const bindEvents = function (project, projCollection, currentProject) {
      const projectData = project || { name: '', startDate: '', additionalInfo: '', id: null };
      markAsSaved();
      
      // Clean up old listeners before binding new ones (disposable pattern)
      if (startDatePicker) {
        startDatePicker.addToElement($('#projectStartDate'));
        // Unbind any previous listeners from date picker
        startDatePicker.getElement().off('input change').on('input change', function() {
          const validationUtils = new ValidationUtils();
          $('#projectStartDate-validation-notification').text(validationUtils.checkDateNotification($(this).val()));
          updateContinueButtonState(projectData);
          updateReserveButtonState();
          markAsChanged();
          updateSaveButtonState(projectData);
        });
      }
      
      // Unbind form inputs before rebinding (prevents duplicate handlers on re-render)
      $('#nimi').off('input change').on('input change', () => {
        updateContinueButtonState(projectData);
        markAsChanged();
        updateSaveButtonState(projectData);
      });
      
      $('#lisatiedot').off('input change').on('input change', () => {
        markAsChanged();
        updateSaveButtonState(projectData);
      });
      
      $('#tie, #aosa, #losa').off('input change').on('input change', () => updateReserveButtonState());
      
      updateContinueButtonState(projectData);
      updateReserveButtonState();
      updateSaveButtonState(projectData);

      // Backbone.Events does not support jQuery-style event namespaces.
      // Keep stable handler references so we can safely rebind on each render.
      if (projectValidationFailedHandler) {
        eventbus.off('roadAddress:projectValidationFailed', projectValidationFailedHandler);
      }
      projectValidationFailedHandler = function(errorMessage) {
        Spinner.hide();
        console.error(errorMessage);
        new ConfirmPopup(errorMessage || 'Projektin tallennus epäonnistui.', {
          type: 'alert',
          okButtonLbl: 'OK'
        });
      };
      eventbus.on('roadAddress:projectValidationFailed', projectValidationFailedHandler);

      if (projectFailedHandler) {
        eventbus.off('roadAddress:projectFailed', projectFailedHandler);
      }
      projectFailedHandler = function(error) {
        Spinner.hide();
        new ConfirmPopup(getBackendErrorMessage(error, 'Projektin tallennus epäonnistui.'), {
          type: 'alert',
          okButtonLbl: 'OK'
        });
      };
      eventbus.on('roadAddress:projectFailed', projectFailedHandler);

      if (projCollection && currentProject) {
        bindReservationHandler(projCollection, currentProject);
        bindReservationEventListeners(projCollection, currentProject);
        bindDeleteRoadPartHandlers(projCollection, currentProject);
      }
      
      $('#generalNext, #saveProject').off('click').on('click', function() {
        const ProjectStatus = ViiteEnumerations.ProjectStatus;
        const isProjectPublished = Boolean(
          projectData &&
          !_.isUndefined(projectData.statusCode) &&
          ![
            ProjectStatus.Incomplete.value,
            ProjectStatus.ErrorInViite.value,
            ProjectStatus.Unknown.value
          ].includes(projectData.statusCode)
        );

        // Prevent saving if project is published, but let them continue to action menu so they can inspect change table
        if (isProjectPublished) {
          applicationModel.selectLayer('roadAddressProject', true, false);

          if (callbacks.continueToActions) {
            callbacks.continueToActions({ project: projectData });
          }
          
          return;
        }

        projectData.name = $('#nimi').val();
        projectData.startDate = $('#projectStartDate').val();
        projectData.additionalInfo = $('#lisatiedot').val();

        // Validate required fields
        if (!projectData.name || !projectData.startDate) {
          new ConfirmPopup('Nimi ja alkupäivämäärä ovat pakollisia tietoja.', {
            type: 'alert',
            okButtonLbl: 'OK'
          });
          return;
        }

        Spinner.show();

        const formData = [
          { value: projectData.name }, 
          { value: projectData.startDate }, 
          { value: projectData.additionalInfo }
        ];

        eventbus.once('roadAddress:projectSaved', function(result) {
          Spinner.hide();

          // Wait before refreshing the map to ensure the layer is selected
          setTimeout(function() {
            applicationModel.refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent(), map.getView().getCenter());
          }, 1800);

          if (result && result.success) {
            markAsSaved();

            const currentProjectState = projectCollection.getCurrentProject ? projectCollection.getCurrentProject() : null;
            const savedProject = result.project || (currentProjectState && currentProjectState.project) || projectData;

            projectData.id = savedProject.id || projectData.id;
            projectData.name = savedProject.name || projectData.name;
            projectData.startDate = savedProject.startDate || projectData.startDate;
            projectData.additionalInfo = savedProject.additionalInfo || projectData.additionalInfo;

            if (savedProject) {
              eventbus.trigger('roadAddressProject:openProject', savedProject);
            }

            if (result.projectAddresses && savedProject) {
              eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId, savedProject);
            }
            
            applicationModel.selectLayer('roadAddressProject', true, false);
            
            // For 'Jatka toimenpiteisiin' button, always continue to action menu
            if (callbacks.continueToActions) {
              callbacks.continueToActions({ project: savedProject });
            }
          } else {
            new ConfirmPopup(getBackendErrorMessage(result, 'Projektin tallennus epäonnistui.'), {
              type: 'alert',
              okButtonLbl: 'OK'
            });
          }
        });

        if (!projectData.id || projectData.id === 0) {
          // Create new project
          projectCollection.createProject(formData, map ? map.getView().getResolution() : null);
        } else {
          // Save existing project
          projectCollection.saveProject(formData, map ? map.getView().getResolution() : null);
        }
      });

      $('#saveAndCancelDialogue, #cancelEdit').off('click').on('click', function() {
        const isEditCancel = $(this).attr('id') === 'cancelEdit';
        const returnToActions = function(savedProject) {
          const targetProject = savedProject || (projectCollection.getCurrentProject() && projectCollection.getCurrentProject().project) || projectData;
          navigateToActionMenu(targetProject);
        };

        if (getUnsavedChangesState()) {
          new ConfirmPopup('Haluatko tallentaa tekemäsi muutokset?', {
            successCallback: function () {
              // Save the project before closing
              projectData.name = $('#nimi').val();
              projectData.startDate = $('#projectStartDate').val();
              projectData.additionalInfo = $('#lisatiedot').val();

              const formData = [
                { value: projectData.name },
                { value: projectData.startDate },
                { value: projectData.additionalInfo }
              ];

              eventbus.once('roadAddress:projectSaved', function(result) {
                Spinner.hide();
                if (result && result.success) {
                  markAsSaved();

                  const latestProject = result.project || (projectCollection.getCurrentProject() && projectCollection.getCurrentProject().project) || projectData;

                  if (latestProject) {
                    eventbus.trigger('roadAddressProject:openProject', latestProject);
                  }

                  if (result.projectAddresses && latestProject) {
                    eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId, latestProject);
                  }
                  
                  // Set the selected layer to roadAddressProject when project is saved
                  applicationModel.selectLayer('roadAddressProject', true, false);

                  if (isEditCancel) {
                    returnToActions(latestProject);
                  } else {
                    callbacks.closeProjectMenu();
                  }
                } else {
                  new ConfirmPopup(getBackendErrorMessage(result, 'Projektin tallennus epäonnistui.'), {
                    type: 'alert',
                    okButtonLbl: 'OK'
                  });
                }
              });

              if (!currentProject.id || currentProject.id === 0) {
                projectCollection.createProject(formData, map ? map.getView().getResolution() : null);
              } else {
                projectCollection.saveProject(formData, map ? map.getView().getResolution() : null);
              }

            },
            closeCallback: function () {
              if (isEditCancel) {
                applicationModel.selectLayer('roadAddressProject', true, false);
                returnToActions();
                return;
              }

              // Close without saving - reset layer to default
              applicationModel.selectLayer('linkProperty', true, false);
              callbacks.closeProjectMenu();
            }
          });
        } else {
          if (isEditCancel) {
            applicationModel.selectLayer('roadAddressProject', true, false);
            returnToActions();
            return;
          }

          // No unsaved changes, close directly - reset layer to default
          applicationModel.selectLayer('linkProperty', true, false);
          callbacks.closeProjectMenu();
        }
      });

      $('#deleteProjectSpan').off('click').on('click', function() {
          new ConfirmPopup('Haluatko varmasti poistaa tämän projektin?', {
            successCallback: function () {
               projectCollection.deleteProject(projectData.id);
               // Reset layer to default after project deletion
               applicationModel.selectLayer('linkProperty', true, false);
               callbacks.closeProjectMenu();
            }
          });
      });
    };

    const bindReservationHandler = function (projCollection, currentProject) {
      $('.btn-reserve').off('click').on('click', function () {
        const roadNumber = $('#tie').val() || '';
        const startPart = $('#aosa').val() || '';
        const endPart = $('#losa').val() || '';
        const projectDate = $('#projectStartDate').val() || '';
        const projectId = (currentProject && currentProject.id) ? currentProject.id : 0;
        
        // Format data as expected by checkIfReserved method
        const data = [
          null, // data[0] - unused
          { value: projectDate }, // data[1] - project date
          null, // data[2] - unused
          { value: roadNumber }, // data[3] - road number
          { value: startPart }, // data[4] - start part
          { value: endPart } // data[5] - end part
        ];
        data.projectId = projectId;
        
        projCollection.checkIfReserved(data);
        return false;
      });
    };

    const bindReservationEventListeners = function (projCollection, currentProject) {
      const ProjectStatus = ViiteEnumerations.ProjectStatus;
      eventbus.off('roadAddress:projectValidationSucceed').on('roadAddress:projectValidationSucceed', function () {
        $('#tie, #aosa, #losa').val('');
        $('#reservedRoads').html(roadPartList(projCollection.getReservedParts(), 'reserved', currentProject, ProjectStatus));
        if ($('#newReservedRoads').length) {
            $('#newReservedRoads').html(roadPartList(projCollection.getFormedParts(), 'formed', currentProject, ProjectStatus));
        }
        updateReserveButtonState();
        markAsChanged(); // Mark project as having unsaved changes when reservation is made
        updateSaveButtonState(currentProject);
      });
    };

    const bindDeleteRoadPartHandlers = function (projCollection, currentProject) {

      const isProjectEditable = function () {
        const ProjectStatus = ViiteEnumerations.ProjectStatus;
        const editableStatus = [ProjectStatus.Incomplete.value, ProjectStatus.Unknown.value];
        return _.isUndefined(currentProject) || editableStatus.includes(currentProject.statusCode);
      };

      const removeRenumberedPart = function (roadNumber, roadPartNumber) {
        projCollection.setFormedParts(_.filter(projCollection.getFormedParts(), function (part) {
          let reNumberedPart = false;
          if (part.roadAddresses) {
            for (let i = 0; i < part.roadAddresses.length; ++i) {
              const ra = part.roadAddresses[i];
              reNumberedPart = (ra.roadAddressNumber.toString() === roadNumber.toString() &&
                  ra.roadAddressPartNumber.toString() === roadPartNumber.toString()) && ra.isNumbering;
              if (reNumberedPart) {
                break;
              }
            }
          }
          return !reNumberedPart;
        }));
      };

      const removeFormedPart = function (roadNumber, roadPartNumber) {
        markAsChanged();
        
        // Recursively remove formed parts tied to this road part
        _.each(projCollection.getRoadAddressesFromFormedRoadPart(roadNumber, roadPartNumber), function (roadAddresses) {
          _.each(roadAddresses, function (ra) {
            removeFormedPart(ra.roadAddressNumber, ra.roadAddressPartNumber);
          });
        });
        
        projCollection.setFormedParts(_.filter(projCollection.getFormedParts(), function (part) {
          return part.roadNumber.toString() !== roadNumber.toString() || part.roadPartNumber.toString() !== roadPartNumber.toString();
        }));
        
        refreshRoadPartsDisplay(projCollection, currentProject);
        updateSaveButtonState(currentProject);
      };

      const removeReservedPart = function (roadNumber, roadPartNumber) {
        markAsChanged();
        
        projCollection.setReservedParts(_.filter(projCollection.getReservedParts(), function (part) {
          return part.roadNumber.toString() !== roadNumber.toString() || part.roadPartNumber.toString() !== roadPartNumber.toString();
        }));
        
        removeRenumberedPart(roadNumber, roadPartNumber);
        refreshRoadPartsDisplay(projCollection, currentProject);
        updateSaveButtonState(currentProject);
      };

      $('.form-result').off('click', '.btn-delete').on('click', '.btn-delete', function () {
        const roadNumber = $(this).attr('data-roadnumber') || $(this).data('roadnumber');
        const roadPartNumber = $(this).attr('data-roadpartnumber') || $(this).data('roadpartnumber');
        const isReserved = $(this).hasClass('reservedList');

        if (isProjectEditable()) {
          const partsList = isReserved ? projCollection.getReservedParts() : projCollection.getFormedParts();
          
          // Verify if the part exists in the collection to determine if confirmation is needed
          const partExists = partsList.some(p => p.roadNumber.toString() === roadNumber.toString() && p.roadPartNumber.toString() === roadPartNumber.toString());

          const executeDeletion = function() {
            if (isReserved) {
              removeReservedPart(roadNumber, roadPartNumber);
              removeFormedPart(roadNumber, roadPartNumber);
            } else {
              removeFormedPart(roadNumber, roadPartNumber);
            }
            
            _.defer(function () {
              $('#generalNext').prop('disabled', false);
              $('#saveProject:disabled').prop('disabled', false);
              markAsChanged();
              eventbus.trigger('projectCollection:partsChanged');
            });
          };

          if (currentProject && partExists) {
            new ConfirmPopup('Haluatko varmasti poistaa tieosan varauksen ja \r\nsiihen mahdollisesti tehdyt tieosoitemuutokset?', {
              successCallback: executeDeletion
            });
          } else {
            executeDeletion();
          }
        }
      });
    };

    const refreshRoadPartsDisplay = function (projCollection, currentProject) {
        const ProjectStatus = ViiteEnumerations.ProjectStatus;
        $('#reservedRoads').html(roadPartList(projCollection.getReservedParts(), 'reserved', currentProject, ProjectStatus));
        if ($('#newReservedRoads').length) {
            $('#newReservedRoads').html(roadPartList(projCollection.getFormedParts(), 'formed', currentProject, ProjectStatus));
        }
    };

    return {
      renderForm,
      renderFooter,
      bindEvents,
      roadPartList,
      getDatePicker: () => startDatePicker,
      markAsChanged,
      markAsSaved,
      getUnsavedChangesState
    };
}
