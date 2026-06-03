/*
 * ProjectActionMenu: Renders action buttons and error list for road addressing workflow.
 * Manages project validation, recalculation, change table display, and publication.
 * Tracks state (hasErrors, recalculated, changeTableOpen, publishable) to control button availability.
 * Returns HTML strings (renderContent, renderFooter) for MenuContainer integration.
 * Provides refresh() for selective DOM updates and updateState() for external state management.
 */
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { ProjectChangeTable } from '@view/project-menu/ProjectChangeTable.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/eventbus.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { selectLayer } from '@model/ApplicationModel.js';

const changeTableByProjectCollection = new WeakMap();

function getOrCreateProjectChangeTable(projectChangeInfoModel, projectCollection) {
  if (!projectChangeInfoModel || !projectCollection) {
    return null;
  }

  let changeTable = changeTableByProjectCollection.get(projectCollection);
  if (!changeTable) {
    changeTable = new ProjectChangeTable(projectChangeInfoModel, projectCollection);
    changeTableByProjectCollection.set(projectCollection, changeTable);
  }
  return changeTable;
}

export function ProjectActionMenu(options) {
    const {
      projectCollection,
      map,
      eventbus: injectedEventbus,
      backend,
      projectChangeInfoModel,
      container = '#menu-container',
      closeProjectMenu,
      initialState = {},
      onStateChange
    } = options;
    const mainMenu = options.mainMenu;
    const activeEventbus = injectedEventbus || eventbus;
    const projectChangeTable = getOrCreateProjectChangeTable(projectChangeInfoModel, projectCollection);
    let onProjectErrorsUpdatedHandler;

    const state = Object.assign({
      hasErrors: false,
      changeTableOpen: false,
      recalculated: false,
      publishable: false,
      isProjectPublished: false
    }, initialState);

    const config = {
      coordinates: [],
      cssClasses: {
        validate: 'btn-validate btn-primary btn-lg',
        recalculate: 'btn-recalculate btn-primary btn-lg',
        changes: 'btn-show-changes btn-primary btn-lg',
        send: 'btn-send btn-primary btn-lg'
      },
      disabledTitles: {
        recalculate: 'Kaikki linkit tulee olla käsiteltyjä',
        changes: 'Projektin tulee läpäistä validoinnit',
        send: 'Hyväksy yhteenvedon jälkeen'
      },
      buttonStates: {
        validate: { disabled: false, title: '' },
        recalculate: { disabled: false, title: '' },
        changes: { disabled: true, title: 'Päivitä etäisyyslukemat ensin' },
        send: { disabled: true, title: 'Hyväksy yhteenvedon jälkeen' }
      }
    };

    // ==========================================
    // STATE & DOM MANAGEMENT
    // ==========================================

    const evaluateButtonStates = () => {
      // Sync state with actual project errors before evaluating rules
      const projectErrors = projectCollection.getProjectErrors ? projectCollection.getProjectErrors() : [];
      state.hasErrors = projectErrors && projectErrors.length > 0;

      // Check if project is published
      const currentProject = projectCollection.getCurrentProject();
      const ProjectStatus = ViiteEnumerations.ProjectStatus;
      state.isProjectPublished = currentProject && currentProject.project && 
        (currentProject.project.statusCode !== ProjectStatus.Incomplete.value && 
         currentProject.project.statusCode !== ProjectStatus.ErrorInViite.value);

      // Published projects: Always disable recalculate, always enable changes
      if (state.isProjectPublished) {
        config.buttonStates.recalculate = { disabled: true, title: 'Projekti on julkaistu' };
        config.buttonStates.changes = { disabled: false, title: '' };
        config.buttonStates.send = { disabled: true, title: config.disabledTitles.send };
      }
      // Errors exist: Disable recalculate, changes, and send
      else if (state.hasErrors) {
        config.buttonStates.recalculate = { disabled: true, title: 'Korjaa virheet ensin' };
        config.buttonStates.changes = { disabled: true, title: 'Projektissa on virheitä' };
        config.buttonStates.send = { disabled: true, title: config.disabledTitles.send };
      } 
      // Change table is open: Disable recalculate and changes, conditionally enable send
      else if (state.changeTableOpen) {
        config.buttonStates.recalculate = { disabled: true, title: 'Yhteenveto auki' };
        config.buttonStates.changes = { disabled: true, title: 'Yhteenveto on auki' };
        config.buttonStates.send = { disabled: !state.publishable, title: state.publishable ? '' : config.disabledTitles.send };
      } 
      // Normal state / Recalculated
      else {
        config.buttonStates.send = { disabled: true, title: config.disabledTitles.send };
        
        // After recalculation succeeds: disable recalculate, enable changes
        if (state.recalculated) {
          config.buttonStates.recalculate = { disabled: true, title: 'Avaa yhteenveto ensin' };
          config.buttonStates.changes = { disabled: false, title: '' };
        } else {
          config.buttonStates.recalculate = { disabled: false, title: '' };
          config.buttonStates.changes = { disabled: true, title: 'Päivitä etäisyyslukemat ensin' };
        }
      }
    };

    const refresh = () => {
      const $root = $(container);
      
      // Refresh error display
      const $errorContainer = $root.find('#project-errors');
      if ($errorContainer.length) {
        const errorsHtml = errorsList(projectCollection);
        $errorContainer.html(errorsHtml);
      }
      
      // Refresh button states
      ['validate', 'recalculate', 'changes', 'send'].forEach(type => {
        const btnState = config.buttonStates[type];
        const $btn = $root.find(`#${type}-button`);
        if ($btn.length) {
          $btn.prop('disabled', btnState.disabled);
          $btn.attr('title', btnState.title);
        }
      });
    };

    // This is the function exposed to manage state from outside the file
    const updateState = function (newState) {
      Object.assign(state, newState); // Merge new state flags into current state
      evaluateButtonStates();
      refresh();
      if (typeof onStateChange === 'function') {
        onStateChange(Object.assign({}, state));
      }
    };

    // ==========================================
    // HTML RENDERING
    // ==========================================

    const getErrorCoordinates = function (error, links) {
      if (error.coordinates && error.coordinates.length > 0) {
        const coord = error.coordinates[0];
        if (coord.x !== undefined && coord.y !== undefined) return [coord.x, coord.y];
        if (Array.isArray(coord) && coord.length >= 2) return coord;
      }
      const linkCoords = _.find(links, link => link.linkId === error.linkIds[0]);
      if (!_.isUndefined(linkCoords) && linkCoords.points && linkCoords.points.length > 0) {
        const point = linkCoords.points[0];
        if (point.x !== undefined && point.y !== undefined) return [point.x, point.y];
        if (Array.isArray(point) && point.length >= 2) return point;
      }
      return false;
    };

    const getProjectErrors = function (projectErrors, links) {
      let buttonIndex = 0;
      let errorIndex = 0;
      let errorLines = '';
      const coordinates = [];

      projectErrors.sort((a, b) => a.priority - b.priority);

      _.each(projectErrors, function (error) {
        let fixButton = '';
        const coords = getErrorCoordinates(error, links);
        const errorMessage = _.trim((error.errorMessage || '').toString());
        const errorLabel = errorMessage ? `<label class="orange">VIRHE: ${errorMessage}</label>` : '';

        if (coords) {
          fixButton = `<button id="${buttonIndex}" class="btn-primary projectErrorButton btn-error-fix">Korjaa</button>`;
          coordinates.push({index: buttonIndex, html: fixButton, coordinates: coords});
          buttonIndex++;
        }

        const linkIdButton = (error.linkIds && error.linkIds.length > 0)
          ? `<button id="${errorIndex}" class="btn-primary linkIdList">Linkkien id:t</button>` : '';

        // Use divider if not last error
        const divider = (errorIndex < projectErrors.length - 1) ? '<div class="error-divider"></div>' : '';

        errorLines += `
          <div class="form-project-errors-list">
            ${errorLabel}
            <label class="orange">INFO: ${error.info ? error.info : 'N/A'}</label>
            <div>
               ${fixButton}
               ${linkIdButton}
            </div>
            ${divider}
          </div>`;

        errorIndex++;
      });
      return { html: errorLines, coordinates };
    };

    const errorsList = function (projCollection) {
      if (!projCollection || !projCollection.getProjectErrors) return '';
      const projectErrors = projCollection.getProjectErrors();

      if (projectErrors && projectErrors.length > 0) {
        const links = projCollection.getAll ? projCollection.getAll() : [];
        const { html, coordinates } = getProjectErrors(projectErrors, links);
        config.coordinates = coordinates;
        return `<label>TARKASTUSILMOITUKSET:</label>
          <div id="projectErrors">${html}</div>`;
      }
      config.coordinates = [];
      return '';
    };

    const renderContent = function () {
      const errorsHtml = errorsList(projectCollection);
      
      return `
        <div class="form form-horizontal form-dark">
          <div class="project-errors" id="project-errors">${errorsHtml}</div>
        </div>`;
    };

    const renderFooter = function () {
      evaluateButtonStates();
      const btns = config.buttonStates;
      
      let validateBtn = '';
      if (options.canUseDevTools) {
        validateBtn = `<button id="validate-button" class="${config.cssClasses.validate}" ${btns.validate.disabled ? 'disabled' : ''} title="${btns.validate.title}">Validoi projekti</button>`;
      }

      return `
        <div class="footer-project-action-menu">
          ${validateBtn}
          <button id="recalculate-button" class="${config.cssClasses.recalculate}" ${btns.recalculate.disabled ? 'disabled' : ''} title="${btns.recalculate.title}">Päivitä etäisyyslukemat</button>
          <button id="changes-button" class="${config.cssClasses.changes}" ${btns.changes.disabled ? 'disabled' : ''} title="${btns.changes.title}">Avaa projektin yhteenvetotaulukko</button>
          <button id="send-button" class="${config.cssClasses.send}" ${btns.send.disabled ? 'disabled' : ''} title="${btns.send.title}">Hyväksy tieosoitemuutokset</button>
        </div>`;
    };

    // ==========================================
    // EVENT HANDLERS
    // ==========================================

    const closeProjectMode = (_changeLayerMode, noSave) => {
      activeEventbus.trigger('roadAddressProject:startAllInteractions');
      activeEventbus.trigger('projectChangeTable:hide');
      projectCollection.clearRoadAddressProjects();

      if (typeof closeProjectMenu === 'function') {
        closeProjectMenu({ noSave: Boolean(noSave) });
      } else if (mainMenu && typeof mainMenu.setState === 'function') {
        activeEventbus.trigger('roadAddressProject:deselectFeaturesSelected');
        activeEventbus.trigger('roadAddressProject:deactivateAllSelections');
        activeEventbus.trigger('roadAddressProject:clearOnClose');
        mainMenu.setState('main');
        selectLayer('linkProperty', true, noSave);
      }
    };

    const handleRecalculateClick = function () {

      const currentProject = projectCollection.getCurrentProject();
      Spinner.show();
      $('.validation-warning').remove();

      backend.recalculateAndValidateProject(currentProject.project.id, function (response) {
        if (response.success) {
          projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);
          const hasErrors = Object.keys(response.validationErrors).length > 0;
          
          // Update the state based on response
          updateState({
            hasErrors: hasErrors,
            recalculated: !hasErrors
          });

          const extent = map.getView().calculateExtent(map.getSize()).join(',');
          const zoom = zoomlevels.getViewZoom(map) + 1;
          projectCollection.fetch(extent, zoom, currentProject.project.id, projectCollection.getPublishableStatus());
        } else {
          new ConfirmPopup(response.errorMessage, {
            type: 'alert',
            okButtonLbl: 'OK'
          });
        }
        Spinner.hide();
      });
    };

    const handleChangesClick = function () {
      const isPublishable = projectCollection.getPublishableStatus();
      
      // Update state to lock recalculate and conditionally unlock send
      updateState({
        changeTableOpen: true,
        publishable: isPublishable
      });
      
      projectChangeTable.show();
    };

    const handleValidateClick = function () {
      const currentProject = projectCollection.getCurrentProject();
      if (!currentProject || !currentProject.project) {
        new ConfirmPopup('Ei aktiivista projektia', {
          type: 'alert',
          okButtonLbl: 'OK'
        });
        return;
      }

      Spinner.show();
      $('.validation-warning').remove();

      backend.validateProject(currentProject.project.id, function (response) {
        Spinner.hide();
        
        if (response.success) {
          const hasErrors = response.validationErrors && Object.keys(response.validationErrors).length > 0;
          
          if (!hasErrors) {
            updateState({ hasErrors: false });
          } else {
            projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);
            updateState({ hasErrors: true });
            new ConfirmPopup('Projektissa on virheitä. Korjaa virheet ennen yhteenvetotaulukon avaamista.', {
              type: 'alert',
              okButtonLbl: 'OK'
            });
          }
        } else {
          new ConfirmPopup(response.errorMessage || 'Validointi epäonnistui', {
            type: 'alert',
            okButtonLbl: 'OK'
          });
        }
      });
    };

    const handleSendClick = function () {
      new ConfirmPopup("Haluatko hyväksyä projektin muutokset osaksi tieosoiteverkkoa?", {
        successCallback: () => {
          projectCollection.publishProject();
          closeProjectMode(true, true);
        }
      });
    };

    // ==========================================
    // BINDINGS & PUBLIC API
    // ==========================================

    const bindEvents = function () {
      const rootElement = $(container);
      rootElement.off('click', 'button.btn-recalculate').on('click', 'button.btn-recalculate', handleRecalculateClick);
      rootElement.off('click', 'button.btn-show-changes').on('click', 'button.btn-show-changes', handleChangesClick);
      rootElement.off('click', 'button.btn-send').on('click', 'button.btn-send', handleSendClick);
      rootElement.off('click', 'button.btn-validate').on('click', 'button.btn-validate', handleValidateClick);

      if (projectChangeTable && typeof projectChangeTable.setCallbacks === 'function') {
        projectChangeTable.setCallbacks({
          onClosed: function () {
            updateState({ changeTableOpen: false });
          },
          onValidationResult: function (result) {
            updateState({
              hasErrors: Boolean(result.hasErrors),
              publishable: Boolean(result.publishable)
            });
          }
        });
      }
      
      rootElement.off('click', 'button.projectErrorButton').on('click', 'button.projectErrorButton', function() {
        const buttonId = parseInt($(this).attr('id'), 10);
        const coordinateData = config.coordinates[buttonId];
        
        if (coordinateData && coordinateData.coordinates) {
          const coordinates = coordinateData.coordinates;
          if (Array.isArray(coordinates) && coordinates.length >= 2 && isFinite(coordinates[0]) && isFinite(coordinates[1])) {
            const view = map.getView();
            view.animate({ center: coordinates, zoom: Math.max(view.getZoom(), 15), duration: 0 });
          } else {
            new ConfirmPopup('Virheelliset koordinaatit. Ei voida siirtyä kohteeseen.', {
              type: 'alert',
              okButtonLbl: 'OK'
            });
          }
        } else {
          new ConfirmPopup('Koordinaatit eivät ole saatavilla tälle virheelle.', {
            type: 'alert',
            okButtonLbl: 'OK'
          });
        }
      });
      
      rootElement.off('click', 'button.linkIdList').on('click', 'button.linkIdList', function() {
        const errorIndex = parseInt($(this).attr('id'), 10);
        const projectErrors = projectCollection.getProjectErrors();
        if (projectErrors && projectErrors[errorIndex] && projectErrors[errorIndex].linkIds) {
          const linkIds = projectErrors[errorIndex].linkIds;
          const linkIdText = linkIds.length > 0 ? linkIds.join(', ') : 'Ei linkkejä';
          new ConfirmPopup(`Linkkien ID:t: ${linkIdText}`, {
            type: 'alert',
            okButtonLbl: 'OK'
          });
        }
      });

      if (_.isFunction(onProjectErrorsUpdatedHandler)) {
        activeEventbus.off('roadAddressProject:writeProjectErrors', onProjectErrorsUpdatedHandler);
      }

      onProjectErrorsUpdatedHandler = function () {
        evaluateButtonStates();
        refresh();
      };

      activeEventbus.on('roadAddressProject:writeProjectErrors', onProjectErrorsUpdatedHandler);
    };

    return {
      renderContent,
      renderFooter,
      bindEvents,
      updateState,
      getProjectChangeTable: () => projectChangeTable
    };
}
