// The main entrypoint and orchestrator for project creation and editing
(function (root) {
  const States = {
    CONFIGURATION:   'CONFIGURATION',
    ROAD_ADDRESSING: 'ROAD_ADDRESSING',
    LINK_EDIT:       'LINK_EDIT'
  };

  root.ProjectMenu = function (containerSelector, eventBus, options = {}) {
    const rootElement = $(containerSelector || '#feature-attributes');
    const eventbus = eventBus;

    // --- State & Project Management ---
    let currentState = States.ROAD_ADDRESSING;
    
    let editFlag = false;
    const project = {
      data: null,
      isNew: true
    };

    let additionalData = {
      selectedLinks: []
    };

    const render = function () {
      let contentHtml = '';
      let footerHtml = '';
      let childInstance = null;

      switch (currentState) {
        case States.CONFIGURATION: {
          const detailsForm = new root.ProjectDetailsForm({
            closeProjectMenu: closeProjectMenu,
            continueToActions: (actionData) => {
              updateUI(States.ROAD_ADDRESSING, actionData.project, false);
            },
            backend: options.backend,
            projectCollection: options.projectCollection,
            map: options.map,
            projectMenuInstance: {
              setState: (newState, newData = project.data, newIsNew = project.isNew, data = additionalData) => updateUI(newState, newData, newIsNew, data)
            }
          });

          const ProjectStatus = ViiteEnumerations.ProjectStatus;
          const reservedParts = options.projectCollection ? options.projectCollection.getReservedParts() : [];
          const formedParts = options.projectCollection ? options.projectCollection.getFormedParts() : [];

          const reservedHtml = detailsForm.roadPartList(reservedParts, 'reserved', project.data, ProjectStatus);
          const formedHtml = detailsForm.roadPartList(formedParts, 'formed', project.data, ProjectStatus);

          contentHtml = detailsForm.renderForm(project.data, project.isNew, reservedHtml, formedHtml);
          footerHtml = detailsForm.renderFooter(project.data, editFlag);
          childInstance = detailsForm;
          break;
        }

        case States.ROAD_ADDRESSING: {
          const actionMenu = new root.ProjectActionMenu({
            ...options,
            eventbus: eventbus,
            project: project.data
          });

          contentHtml = actionMenu.renderContent();
          footerHtml = actionMenu.renderFooter();
          childInstance = actionMenu;
          break;
        }

        case States.LINK_EDIT: {
          const linkEditForm = new root.LinkEditForm(options.startupParameters);
          const links = options.projectCollection ? options.projectCollection.getProjectLinks() : [];
          contentHtml = linkEditForm.render(
            project.data, 
            additionalData.selectedLinks, 
            additionalData.errorMessage,
            links
          );
          
          footerHtml = linkEditForm.renderFooter(project.data, options.projectCollection);
          childInstance = linkEditForm;
          break;
        }

        default:
          console.warn("ProjectMenu: Unknown state encountered", currentState);
          break;
      }

      const html = `
        <div class="wrapper">
          <header class="menu-header">${renderTitle()}</header>
          <main class="content-area">${contentHtml}</main> <footer>${footerHtml}</footer>
        </div>`;

      rootElement.html(html);
      bindInternalEvents(childInstance);
    };

    const renderTitle = () => {
      if (!project.data || !project.data.name) {
        return `<span class="edit-mode-title">Uusi tieosoiteprojekti</span>`;
      }

      if (currentState === States.CONFIGURATION) {
        return `<span class="edit-mode-title">${project.data.name}</span>`;
      }

      return `
        <span class="edit-mode-title">${project.data.name} 
          <i id="editProjectSpan" class="btn-pencil-edit fas fa-pencil-alt"></i>
        </span>
        <span id="closeProjectSpan">Sulje <i class="fas fa-window-close"></i></span>`;
    };

    const bindInternalEvents = function (activeChild) {
      const editSpan = rootElement.find('#editProjectSpan');
      const closeSpan = rootElement.find('#closeProjectSpan');

      editSpan.on('click', () => { editFlag = true; updateUI(States.CONFIGURATION, project.data, false); });
      closeSpan.on('click', () => closeProjectMenu());

      // Bind save and cancel button events for LINK_EDIT state
      if (currentState === States.LINK_EDIT) {
        const saveButton = rootElement.find('#saveButton');
        const cancelButton = rootElement.find('#cancelButton');


        saveButton.on('click', () => {
          if (options.projectCollection && activeChild) {
            // Use the new validation and save function from LinkEditForm
            const success = activeChild.validateAndSave(options.projectCollection, additionalData.selectedLinks);
            if (success) {
              // Reset state to ROAD_ADDRESSING after successful save
              updateUI(States.ROAD_ADDRESSING, project.data, false);
            }
          }
        });
        
        cancelButton.on('click', () => {
          // Reset state to ROAD_ADDRESSING when cancel is clicked
          updateUI(States.ROAD_ADDRESSING, project.data, false);
        });
      }

      if (activeChild && typeof activeChild.bindEvents === 'function') {
        if (currentState === States.CONFIGURATION) {
          activeChild.bindEvents(project.data, options.projectCollection, project.data);
        } else if (currentState === States.LINK_EDIT) {
          activeChild.bindEvents(project.data, additionalData.selectedLinks, options.backend, options.projectCollection, options.projectChangeTable);
        } else {
          activeChild.bindEvents();
        }
      }
    };

    const updateUI = (newState, newData = project.data, newIsNew = project.isNew, data = additionalData) => {
      // Validate that the state exists in our States object
      if (!Object.values(States).includes(newState)) {
        console.error("Invalid UI State transition attempt:", newState);
        return;
      }

      currentState = newState;
      project.data = newData;
      project.isNew = newIsNew;
      additionalData = data;
      
      // Re-enable link interactions when entering ROAD_ADDRESSING state
      if (newState === States.ROAD_ADDRESSING) {
        eventbus.trigger('roadAddressProject:startAllInteractions');
        // Ensure correct cursor is set by triggering tool change
        eventbus.trigger('tool:changed', applicationModel.getSelectedTool());
      }
      
      render();
    };

    const closeProjectMenu = () => {
      console.log("closing menuy");
      currentState = States.CONFIGURATION;
      project.data = null;
      project.isNew = false;
      editFlag = false;
      rootElement.empty();
      window.mainMenu.setState('main');
      eventbus.trigger('layer:selected', 'linkProperty', null, true);
      applicationModel.setOpenProject(false);
      applicationModel.selectLayer('linkProperty');
    };

    // --- Listeners ---
    eventbus.on('projectLink:clicked', function (selected) {
      const currentProject = options.projectCollection ? options.projectCollection.getCurrentProject() : null;
      if (currentProject) {
        updateUI(States.LINK_EDIT, currentProject.project, false, { selectedLinks: selected });
      }
    });

    eventbus.on('projectLink:errorClicked', function (selected, errorMessage) {
      const currentProject = options.projectCollection ? options.projectCollection.getCurrentProject() : null;
      if (currentProject) {
        // For error clicks, we still show the link edit form but with error information
        const errorSelectedLinks = Array.isArray(selected) ? selected : [selected];
        updateUI(States.LINK_EDIT, currentProject.project, false, { 
          selectedLinks: errorSelectedLinks, 
          errorMessage: errorMessage 
        });
      }
    });

    eventbus.on('roadAddress:openProject', function (result) {
      project.data = result.project;
      project.isNew = false;

      if (options.projectCollection) {
        options.projectCollection.setAndWriteProjectErrorsToUser(result.projectErrors || []);
        options.projectCollection.clearRoadAddressProjects();
        options.projectCollection.setCurrentProject(result);
        options.projectCollection.setReservedParts(result.reservedInfo || []);
        options.projectCollection.setFormedParts(result.formedInfo || []);
      }

      updateUI(States.CONFIGURATION, project.data, false);

      applicationModel.setProjectButton(true);
      applicationModel.setProjectFeature(project.data.id);
      applicationModel.setOpenProject(true);

      if (!_.isUndefined(project.data)) {
        eventbus.trigger('linkProperties:selectedProject', result.linkId, project.data);
        eventbus.trigger('roadAddressProject:deactivateAllSelections');
      }
      applicationModel.removeSpinner();
    });

    return {
      showProjectDetails: (proj, isNew) => updateUI(States.CONFIGURATION, proj, isNew),
      showRoadAddressing: (proj) => updateUI(States.ROAD_ADDRESSING, proj, false),
      setState: (newState, newData = project.data, newIsNew = project.isNew, data = additionalData) => updateUI(newState, newData, newIsNew, data),
      clear: () => { 
        currentState = States.ROAD_ADDRESSING; 
        project.data = null;
        project.isNew = false;
        rootElement.empty(); 
      },
      closeProjectMenu: closeProjectMenu
    };
  };
}(this));
