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
import { selectLayer, getSelectedTool } from '@model/ApplicationModel.js';
import { fetchProjectLinksForCurrentMap } from '@view/map/layers/ProjectLinkLayer.js';

const States = {
    CONFIGURATION:   'CONFIGURATION',
    ROAD_ADDRESSING: 'ROAD_ADDRESSING',
    LINK_EDIT:       'LINK_EDIT'
  };

export function ProjectMenu(containerSelector, eventBus, options = {}) {
    const rootElement = $(containerSelector || '#menu-container');
    const eventbus = eventBus;
    const selectedProjectLinkProperty = options.selectedProjectLinkProperty;
    const menu = options.menu || null;

    const clearSelectedProjectLinks = () => {
      if (!selectedProjectLinkProperty) {
        return;
      }

      selectedProjectLinkProperty.cleanIds();
      selectedProjectLinkProperty.clean();
      selectedProjectLinkProperty.setDirty(false);
      selectedProjectLinkProperty.clearFeaturesToKeep();
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


      eventbus.trigger('roadAddressProject:clearOnClose');
      eventbus.trigger('layer:selected', 'linkProperty', null, true);
      clearSelectedProjectLinks();
      selectLayer('linkProperty', true, noSave);
    };


    // --- State & Project Management ---
    let currentState = States.CONFIGURATION;
    
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

          const reservedParts = options.projectCollection ? options.projectCollection.getReservedParts() : [];
          const formedParts = options.projectCollection ? options.projectCollection.getFormedParts() : [];

          const reservedHtml = detailsForm.roadPartList(reservedParts, 'reserved');
          const formedHtml = detailsForm.roadPartList(formedParts, 'formed');

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
            onStateChange: syncRoadAddressingState,
            onProjectSentSuccess: onProjectSentSuccess,
            onProjectSentFailed: onProjectSentFailed
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

          const onSave = () => {
            if (options.projectCollection) {
              projectLinkEditor.validateAndSave(options.projectCollection, additionalData.selectedLinks, {
                onProjectLinksUpdated,
                onProjectLinksUpdateFailed,
                onProjectLinksCreateSuccess,
                onChangeDirectionFailed
              }, {
                projectLinkLayer: options.projectLinkLayer,
                selectedProjectLinkProperty: options.selectedProjectLinkProperty
              });
            }
          };

          const onCancel = () => {
            if (typeof projectLinkEditor.cancelChanges === 'function') {
              projectLinkEditor.cancelChanges({
                onCancel: () => updateUI(States.ROAD_ADDRESSING, project.data, false)
              }, {
                projectCollection: options.projectCollection,
                projectLinkLayer: options.projectLinkLayer,
                selectedProjectLinkProperty: options.selectedProjectLinkProperty
              });
            } else {
              updateUI(States.ROAD_ADDRESSING, project.data, false);
            }
          };

          footerHtml = projectLinkEditor.renderFooter(project.data, options.projectCollection, onSave, onCancel);
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
        selectLayer('linkProperty', true, false);

        const projectId = project.data && project.data.id;
        if (projectId && options.projectCollection) {
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
    const onProjectLinkClicked = function (selected) {
      const currentProject = options.projectCollection ? options.projectCollection.getCurrentProject() : null;
      if (currentProject) {
        updateUI(States.LINK_EDIT, currentProject.project, false, { selectedLinks: selected });
      }
    };

    const onProjectLinkErrorClicked = function (selected, errorMessage) {
      const currentProject = options.projectCollection ? options.projectCollection.getCurrentProject() : null;
      if (currentProject) {
        const errorSelectedLinks = Array.isArray(selected) ? selected : [selected];
        updateUI(States.LINK_EDIT, currentProject.project, false, { 
          selectedLinks: errorSelectedLinks, 
          errorMessage: errorMessage 
        });
      }
    };

    const onProjectLinksUpdated = function () {
      if (options.projectCollection) {
        options.projectCollection.setTmpDirty([]);
        options.projectCollection.setDirty([]);
      }

      syncRoadAddressingState({
        recalculated: false,
        changeTableOpen: false
      });

      // Refresh the project links layer to update colors after saving
      fetchProjectLinksForCurrentMap();

      Spinner.hide();
      updateUI(States.ROAD_ADDRESSING, project.data, false);
    };

    const onProjectLinksUpdateFailed = function (errorCode) {
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
    };

    const onReOpenCurrent = function () {
      updateUI(States.ROAD_ADDRESSING, project.data, false);
    };

    const onProjectLinksCreateSuccess = function () {
      if (options.projectCollection) {
        options.projectCollection.setTmpDirty([]);
      }
      eventbus.trigger('projectChangeTable:refresh');
      updateUI(States.ROAD_ADDRESSING, project.data, false);
    };

    const onProjectSentSuccess = function () {
      showToast('Muutoksia viedään tieosoiteverkolle.', { type: 'success' });
      closeProjectMenu();
    };

    const onProjectSentFailed = function (error) {
      new ConfirmPopup(error, {
        type: 'alert',
        okButtonLbl: 'OK'
      });
    };

    const onChangeDirectionFailed = function (error) {
      new ConfirmPopup(error, {
        type: 'alert',
        okButtonLbl: 'OK'
      });
    };

    const onOpenProject = function (result) {
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

      options.roadCollection.setPendingHighlight(project.data.id);

      if (!_.isUndefined(project.data)) {
        eventbus.trigger('linkProperties:selectedProject', result.linkId, project.data);

      }
      Spinner.hide();
    };

    eventbus.on('projectLink:clicked',                     onProjectLinkClicked);
    eventbus.on('projectLink:errorClicked',                onProjectLinkErrorClicked);
    eventbus.on('roadAddressProject:reOpenCurrent',        onReOpenCurrent);
    eventbus.on('roadAddress:openProject',                 onOpenProject);

    const destroy = function () {
      eventbus.off('projectLink:clicked',                     onProjectLinkClicked);
      eventbus.off('projectLink:errorClicked',                onProjectLinkErrorClicked);
      eventbus.off('roadAddressProject:reOpenCurrent',        onReOpenCurrent);
      eventbus.off('roadAddress:openProject',                 onOpenProject);
    };

    return {
      showProjectDetails: (proj, isNew) => updateUI(States.CONFIGURATION, proj, isNew),
      showRoadAddressing: (proj) => updateUI(States.ROAD_ADDRESSING, proj, false),
      setState: (newState, newData = project.data, newIsNew = project.isNew, data = additionalData) => updateUI(newState, newData, newIsNew, data),
      clear: () => { 
        currentState = States.CONFIGURATION; 
        project.data = null;
        project.isNew = false;
        rootElement.empty(); 
      },
      closeProjectMenu: closeProjectMenu,
      destroy: destroy
    };
}
