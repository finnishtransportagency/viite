/*
 * ProjectMenu: Orchestrates project workflow via MenuContainer.
 * Manages state transitions (CONFIGURATION → ROAD_ADDRESSING → LINK_EDIT)
 * and delegates rendering to child components (ProjectDetailsForm, ProjectActionMenu, ProjectLinkEditor).
 * Uses MenuContainer's setHeader(), setBody(), setFooter() for clean content updates.
 */
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { ProjectLinkEditor } from './project-link-editor/ProjectLinkEditor.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { ProjectActionMenu } from './project-action-menu/ProjectActionMenu.js';
import { ProjectDetailsForm } from './project-details/ProjectDetailsForm.js';
import { showToast } from '@components/toast/Toast.js';
import { setMainMenuState } from '@view/MainMenu.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { selectLayer, getSelectedTool } from '@model/ApplicationModel.js';

const States = {
    CONFIGURATION:   'CONFIGURATION',
    ROAD_ADDRESSING: 'ROAD_ADDRESSING',
    LINK_EDIT:       'LINK_EDIT'
  };

export function ProjectMenu(containerSelector, eventBus, options = {}) {
    const rootElement = $(containerSelector || '#menu-container');
    const eventbus = eventBus;
    const selectedProjectLinkProperty = options.selectedProjectLinkProperty;
    const menu = options?.menu || null;

    const clearSelectedProjectLinks = () => {
      if (!selectedProjectLinkProperty) {
        return;
      }

      if (_.isFunction(selectedProjectLinkProperty.cleanIds)) {
        selectedProjectLinkProperty.cleanIds();
      }
      if (_.isFunction(selectedProjectLinkProperty.clean)) {
        selectedProjectLinkProperty.clean();
      }
      if (_.isFunction(selectedProjectLinkProperty.setDirty)) {
        selectedProjectLinkProperty.setDirty(false);
      }
      if (_.isFunction(selectedProjectLinkProperty.clearFeaturesToKeep)) {
        selectedProjectLinkProperty.clearFeaturesToKeep();
      }
    };

    const closeProjectMenu = ({ noSave = false } = {}) => {

      // Reset component state
      currentState = States.CONFIGURATION;
      project.data = null;
      project.isNew = false;
      editFlag = false;
      additionalData = { selectedLinks: [] };
      
      // Clean up MenuContainer and release DOM references

      // Restore main UI state
      setMainMenuState('main');

      eventbus.trigger('roadAddressProject:deselectFeaturesSelected');
      eventbus.trigger('roadAddressProject:deactivateAllSelections');
      eventbus.trigger('roadAddressProject:clearOnClose');
      eventbus.trigger('layer:selected', 'linkProperty', null, true);
      clearSelectedProjectLinks();
      selectLayer('linkProperty', true, noSave);
    };


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

    let roadAddressingState = {
      hasErrors: false,
      changeTableOpen: false,
      recalculated: false,
      publishable: false
    };

    const syncRoadAddressingState = function (newState) {
      roadAddressingState = Object.assign({}, roadAddressingState, newState || {});
    };

    const render = function () {
      let contentHtml = '';
      let footerHtml = '';
      let childInstance = null;

      switch (currentState) {
        case States.CONFIGURATION: {
          const detailsForm = new ProjectDetailsForm({
            closeProjectMenu: closeProjectMenu,
            continueToActions: (actionData) => {
              updateUI(States.ROAD_ADDRESSING, actionData.project, false);
            },
            mainMenu: options.mainMenu,
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
          const actionMenu = new ProjectActionMenu({
            ...options,
            eventbus: eventbus,
            project: project.data,
            mainMenu: options.mainMenu,
            closeProjectMenu: closeProjectMenu,
            initialState: roadAddressingState,
            onStateChange: syncRoadAddressingState
          });

          contentHtml = actionMenu.renderContent();
          footerHtml = actionMenu.renderFooter();
          childInstance = actionMenu;
          break;
        }

        case States.LINK_EDIT: {
          const projectLinkEditor = new ProjectLinkEditor(options.canUseDevTools);
          const links = options.projectCollection ? options.projectCollection.getProjectLinks() : [];
          contentHtml = projectLinkEditor.render(
            project.data, 
            additionalData.selectedLinks, 
            additionalData.errorMessage,
            links
          );
          
          footerHtml = projectLinkEditor.renderFooter(project.data, options.projectCollection);
          childInstance = projectLinkEditor;
          break;
        }

        default:
          console.warn("ProjectMenu: Unknown state encountered", currentState);
          break;
      }

      // Update MenuContainer with fresh content from child component
      menu.setHeader(renderTitle(), closeProjectMenu);
      menu.setBody(contentHtml);
      menu.setFooter(footerHtml);
      // Bind event handlers to newly rendered DOM (disposable pattern)
      bindInternalEvents(childInstance);
    };

    const renderTitle = () => {
      if (!project.data || !project.data.name) {
        return 'Uusi tieosoiteprojekti';
      }

      if (currentState === States.CONFIGURATION) {
        const hasPersistedProject = !_.isUndefined(project.data.id) && project.data.id !== null && project.data.id !== 0;
        if (hasPersistedProject) {
          return project.data.name;
        }
        return project.data.name;
      }

      return `${project.data.name} <i id="editProjectSpan" class="btn-pencil-edit fas fa-pencil-alt"></i>`;
    };

    const bindInternalEvents = function (activeChild) {
      // Always work with fresh DOM references (disposable pattern)
      // Unbind any previous listeners before binding new ones
      const editSpan = rootElement.find('#editProjectSpan');
      editSpan.off('click').on('click', () => {
        eventbus.trigger('projectChangeTable:hide');
        syncRoadAddressingState({ changeTableOpen: false });
        eventbus.trigger('roadAddressProject:deselectFeaturesSelected');
        eventbus.trigger('roadAddressProject:deactivateAllSelections');
        selectLayer('linkProperty', true, false);

        const projectId = project.data && project.data.id;
        if (projectId && options.projectCollection && _.isFunction(options.projectCollection.getProjectsWithLinksById)) {
          Spinner.show();
          options.projectCollection.getProjectsWithLinksById(projectId)
            .then((result) => {
              eventbus.trigger('roadAddress:openProject', result);
              editFlag = true;
            })
            .catch(() => {
              Spinner.hide();
              editFlag = true;
              updateUI(States.CONFIGURATION, project.data, false);
            });
          return;
        }

        editFlag = true;
        updateUI(States.CONFIGURATION, project.data, false);
      });

      // Bind save and cancel button events for LINK_EDIT state
      if (currentState === States.LINK_EDIT) {
        const saveButton = rootElement.find('#saveButton');
        const cancelButton = rootElement.find('#cancelButton');

        // Unbind to prevent duplicate handlers
        saveButton.off('click').on('click', () => {
          if (options.projectCollection && activeChild) {
            activeChild.validateAndSave(options.projectCollection, additionalData.selectedLinks);
          }
        });
        
        cancelButton.off('click').on('click', () => {
          if (activeChild && typeof activeChild.cancelChanges === 'function') {
            activeChild.cancelChanges({
              onCancel: function () {
                updateUI(States.ROAD_ADDRESSING, project.data, false);
              }
            });
          } else {
            updateUI(States.ROAD_ADDRESSING, project.data, false);
          }
        });
      }

      // Delegate child-specific event binding
      if (activeChild && typeof activeChild.bindEvents === 'function') {
        if (currentState === States.CONFIGURATION) {
          activeChild.bindEvents(project.data, options.projectCollection, project.data);
        } else if (currentState === States.LINK_EDIT) {
          activeChild.bindEvents(
            project.data,
            additionalData.selectedLinks,
            options.backend,
            options.projectCollection,
            options.projectChangeTable,
            {
              projectCollection: options.projectCollection,
              projectLinkLayer: options.projectLinkLayer,
              selectedProjectLinkProperty: options.selectedProjectLinkProperty
            }
          );
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
        eventbus.trigger('tool:changed', getSelectedTool());
      }
      
      render();
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

    eventbus.on('roadAddress:projectLinksUpdated', function () {
      if (options.projectCollection) {
        options.projectCollection.setTmpDirty([]);
        options.projectCollection.setDirty([]);
      }

      syncRoadAddressingState({
        recalculated: false,
        changeTableOpen: false
      });

      Spinner.hide();
      updateUI(States.ROAD_ADDRESSING, project.data, false);
    });

    eventbus.on('roadAddress:projectLinksUpdateFailed', function (errorCode) {
      const errorMessages = {
        400: 'Päivitys epäonnistui puutteelisten tietojen takia. Ota yhteyttä järjestelmätukeen.',
        401: 'Sinulla ei ole käyttöoikeutta muutoksen tekemiseen.',
        412: 'Täyttämättömien vaatimusten takia siirtoa ei saatu tehtyä. Ota yhteyttä järjestelmätukeen.',
        500: 'Siirto ei onnistunut taustajärjestelmässä tapahtuneen virheen takia, ota yhteyttä järjestelmätukeen.'
      };

      Spinner.hide();
      new ConfirmPopup(errorMessages[errorCode] ||
        'Siirto ei onnistunut taustajärjestelmässä tapahtuneen tuntemattoman virheen takia, ota yhteyttä järjestelmätukeen.', {
        type: 'alert',
        okButtonLbl: 'OK'
      });
    });

    eventbus.on('roadAddressProject:reOpenCurrent', function () {
      updateUI(States.ROAD_ADDRESSING, project.data, false);
    });

    eventbus.on('roadAddress:projectLinksCreateSuccess', function () {
      if (options.projectCollection) {
        options.projectCollection.setTmpDirty([]);
      }
      eventbus.trigger('projectChangeTable:refresh');
      updateUI(States.ROAD_ADDRESSING, project.data, false);
    });

    eventbus.on('roadAddress:projectSentSuccess', function () {
      showToast('Muutoksia viedään tieosoiteverkolle.', { type: 'success' });
      closeProjectMenu();
      eventbus.trigger('roadLinks:refreshView');
    });

    eventbus.on('roadAddress:projectSentFailed', function (error) {
      new ConfirmPopup(error, {
        type: 'alert',
        okButtonLbl: 'OK'
      });
    });

    eventbus.on('roadAddress:changeDirectionFailed', function (error) {
      new ConfirmPopup(error, {
        type: 'alert',
        okButtonLbl: 'OK'
      });
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

      eventbus.trigger('roadCollection:pendingProjectHighlight', project.data.id);

      if (!_.isUndefined(project.data)) {
        eventbus.trigger('linkProperties:selectedProject', result.linkId, project.data);
        eventbus.trigger('roadAddressProject:deactivateAllSelections');
      }
      Spinner.hide();
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
}
