/**
 * ProjectButtons - Reusable component for project action buttons with internal state management
 * 
 * Required Dependencies:
 *   - projectCollection: ProjectCollection instance
 *   - map: Map instance  
 *   - eventbus: EventBus instance
 *   - applicationModel: ApplicationModel instance
 *   - backend: Backend API service
 *   - projectChangeTable: ProjectChangeTable instance
 * 
 * Optional Configuration:
 *   - disabled: Boolean (default: false)
 *   - disabledTitles: Object with custom titles for disabled buttons
 *   - cssClasses: Object with custom CSS classes
 *   - buttonStates: Object with initial button states
 * 
 * Public API:
 *   - render() - Returns HTML string for buttons
 *   - bindEvents() - Binds click event handlers
 *   - updateState(projectState) - Updates button states based on project conditions
 *   - setButtonState(type, disabled, title) - Sets individual button state
 *   - setButtonStates(states) - Sets multiple button states at once
 * 
 * Usage:
 *   const buttons = new ProjectButtons({ projectCollection, map, eventbus, applicationModel, backend, projectChangeTable });
 *   const html = buttons.render();
 *   buttons.bindEvents();
 *   buttons.updateState({ hasErrors: false, changeTableOpen: true, recalculated: true });
 */
(function (root) {
  root.ProjectButtons = function (options) {

    const {
      projectCollection,
      map,
      eventbus,
      applicationModel,
      backend,
      projectChangeTable,
      startupParameters
    } = options;

    const config = Object.assign({
      container: '#feature-attributes', // The parent element to search within
      disabled: false,
      cssClasses: {
        validate: 'validate btn btn-block btn-recalculate',
        recalculate: 'recalculate btn btn-block btn-recalculate',
        changes: 'show-changes btn btn-block btn-show-changes',
        send: 'send btn btn-block btn-send'
      },
      buttonStates: {
        validate: { disabled: false, title: '' },
        recalculate: { disabled: false, title: '' },
        changes: { disabled: true, title: '' },
        send: { disabled: true, title: '' }
      },
      disabledTitles: {
        recalculate: 'Kaikki linkit tulee olla käsiteltyjä',
        changes: 'Projektin tulee läpäistä validoinnit',
        send: 'Hyväksy yhteenvedon jälkeen'
      }
    }, options);

    // --- Internal Logic Helpers ---

    const getBtnConfig = (type) => {
      const state = config.buttonStates[type] || {};
      const isDisabled = config.disabled || state.disabled;
      const title = isDisabled ? (state.title || config.disabledTitles[type] || '') : (state.title || '');
      return { isDisabled, title };
    };

    /**
     * Updates the DOM based on the internal config.buttonStates.
     * This replaces calls to external formCommon helpers.
     */
    const refresh = () => {
      const $root = $(config.container);
      ['validate', 'recalculate', 'changes', 'send'].forEach(type => {
        const { isDisabled, title } = getBtnConfig(type);
        const $btn = $root.find(`#${type}-button`);
        
        if ($btn.length) {
          $btn.prop('disabled', isDisabled);
          $btn.attr('title', title);
        }
      });
    };

    const closeProjectMode = (changeLayerMode, noSave) => {
      eventbus.trigger('roadAddressProject:startAllInteractions');
      eventbus.trigger('projectChangeTable:hide');
      applicationModel.setOpenProject(false);
      
      const rootElement = $(config.container);
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

    const handleRecalculateClick = function () {
      $('#information-content').empty();
      const currentProject = projectCollection.getCurrentProject();
      applicationModel.addSpinner();
      $('.validation-warning').remove();

      backend.recalculateAndValidateProject(currentProject.project.id, function (response) {
        if (response.success) {
          projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);
          
          const hasErrors = Object.keys(response.validationErrors).length > 0;
          
          config.buttonStates.recalculate = { disabled: true, title: 'Etäisyyslukemat on päivitetty' };
          config.buttonStates.changes = { disabled: hasErrors, title: hasErrors ? 'Korjaa virheet ensin' : '' };
          
          const extent = map.getView().calculateExtent(map.getSize()).join(',');
          const zoom = zoomlevels.getViewZoom(map) + 1;
          projectCollection.fetch(extent, zoom, currentProject.project.id, projectCollection.getPublishableStatus());
          
          eventbus.trigger('roadAddressProject:setRecalculatedAfterChangesFlag', true);
        } else {
          new ModalConfirm(response.errorMessage);
          applicationModel.removeSpinner();
        }
        refresh(); // Sync DOM
      });
    };

    const handleChangesClick = function () {
      projectChangeTable.show();
      // Logic for enabling send button
      if (projectCollection.getPublishableStatus()) {
        config.buttonStates.send = { disabled: false, title: '' };
        refresh();
      }
    };

    const handleValidateClick = function () {
      const currentProject = projectCollection.getCurrentProject();
      if (!currentProject || !currentProject.project) {
        new ModalConfirm('Ei aktiivista projektia');
        return;
      }

      applicationModel.addSpinner();
      $('.validation-warning').remove();

      backend.validateProject(currentProject.project.id, function (response) {
        applicationModel.removeSpinner();
        
        if (response.success) {
          const hasErrors = response.validationErrors && Object.keys(response.validationErrors).length > 0;
          
          if (!hasErrors) {
            // Enable the "avaa projektin yhteenvetotaulukko" button
            config.buttonStates.changes = { disabled: false, title: '' };
            refresh();
          } else {
            projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);
            new ModalConfirm('Projektissa on virheitä. Korjaa virheet ennen yhteenvetotaulukon avaamista.');
          }
        } else {
          new ModalConfirm(response.errorMessage || 'Validointi epäonnistui');
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

    return {
      render: function () {
        const btns = {
          rec: getBtnConfig('recalculate'),
          cha: getBtnConfig('changes'),
          snd: getBtnConfig('send'),
          val: getBtnConfig('validate')
        };

        let validateBtn = '';
        if (startupParameters && _.includes(startupParameters.roles, 'dev')) {
          validateBtn = `<button id="validate-button" class="${config.cssClasses.validate}" ${btns.val.isDisabled ? 'disabled' : ''} title="${btns.val.title}">Validoi projekti</button>`;
        }

        return `
          <div class="project-buttons-container">
            ${validateBtn}
            <button id="recalculate-button" class="${config.cssClasses.recalculate}" ${btns.rec.isDisabled ? 'disabled' : ''} title="${btns.rec.title}">Päivitä etäisyyslukemat</button>
            <button id="changes-button" class="${config.cssClasses.changes}" ${btns.cha.isDisabled ? 'disabled' : ''} title="${btns.cha.title}">Avaa projektin yhteenvetotaulukko</button>
            <button id="send-button" class="${config.cssClasses.send}" ${btns.snd.isDisabled ? 'disabled' : ''} title="${btns.snd.title}">Hyväksy tieosoitemuutokset</button>
          </div>`;
      },
      
      // Make buttons interactive
      bindEvents: function () {
        const rootElement = $(config.container);
        rootElement.off('click', 'button.recalculate').on('click', 'button.recalculate', handleRecalculateClick);
        rootElement.off('click', 'button.show-changes').on('click', 'button.show-changes', handleChangesClick);
        rootElement.off('click', 'button.send').on('click', 'button.send', handleSendClick);
        rootElement.off('click', 'button.validate').on('click', 'button.validate', handleValidateClick);
        return this;
      },
      
      updateState: function (projectState) {
        const { hasErrors, changeTableOpen, recalculated } = projectState;
        
        // Logical State Tree
        if (hasErrors) {
          config.buttonStates.send = { disabled: true, title: config.disabledTitles.changes };
        } else if (changeTableOpen) {
          config.buttonStates.recalculate = { disabled: true, title: 'Yhteenveto auki' };
          config.buttonStates.send = { disabled: false, title: '' };
        } else if (recalculated) {
          config.buttonStates.recalculate = { disabled: true, title: 'Päivitetty' };
          config.buttonStates.changes = { disabled: false, title: '' };
        }

        refresh(); // Update DOM
        return this;
      },

      setButtonState: function (type, disabled, title = '') {
        config.buttonStates[type] = { disabled, title };
        refresh();
        return this;
      },

      setButtonStates: function (states) {
        Object.keys(states).forEach(type => {
          const { disabled, title = '' } = states[type];
          config.buttonStates[type] = { disabled, title };
        });
        refresh();
        return this;
      }
    };
  };
}(window));
