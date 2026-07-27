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
import { selectLayer } from '@model/ApplicationModel.js';
import { fetchProjectLinks, clearOnProjectClose as clearProjectLinkLayer, setProjectLinkDiscardChanges } from '@view/map/layers/ProjectLinkLayer.js';
import { clearLinkPropertyLayer } from '@view/map/layers/LinkPropertyLayer.js';
import { getNavigation } from '@router.js';

const States = {
	CONFIGURATION:   'CONFIGURATION',
	ROAD_ADDRESSING: 'ROAD_ADDRESSING',
	LINK_EDIT:       'LINK_EDIT'
};

let updateProjectMenuBridge = function () {};
export function updateProjectMenu(selected) { return updateProjectMenuBridge(selected); }

export function ProjectMenu(containerSelector, options = {}) {
	const rootElement = $(containerSelector || '#menu-container');
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
		roadAddressingState = { hasErrors: false, changeTableOpen: false, recalculated: false, publishable: false };
  
    

		// Restore main UI state
		setMainMenuState('main');
		if (options.projectChangeTable) {
			options.projectChangeTable.hide();
		}
		clearLinkPropertyLayer();
		clearProjectLinkLayer();
		setProjectLinkDiscardChanges();
		clearSelectedProjectLinks();
		selectLayer('linkProperty', true, noSave);
	};

	// --- State & Project Management ---
	let currentState = States.CONFIGURATION;
    
	let editFlag = false;
	const project = { data: null, isNew: true };

	let additionalData = {
		selectedLinks: []
	};

	let roadAddressingState = {
		hasErrors: false,
		changeTableOpen: false,
		recalculated: false,
		publishable: false,
		isSaveInFlight: false
	};

	// Reference to the currently active ProjectActionMenu instance so we can
	// call updateState() on it after the background fetch completes, without a full re-render.
	let currentActionMenu = null;

	const syncRoadAddressingState = function (newState) {
		roadAddressingState = Object.assign({}, roadAddressingState, newState || {});
	};

	const render = function () {
		let contentHtml = '';
		let footerHtml = '';
		let childInstance = null;

		// Clear stale ActionMenu reference; will be set below if entering ROAD_ADDRESSING
		currentActionMenu = null;

		setProjectLinkDiscardChanges();

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
			if (roadAddressingState.hasFormedLinks === undefined) {
				const links = options.projectCollection ? options.projectCollection.getAll() : [];

        if (links.length > 0) {
					// Recalculate is allowed only when every project link has been processed.
					syncRoadAddressingState({ hasFormedLinks: links.every(l => l.status !== 0) });
				}
			}

			const actionMenu = new ProjectActionMenu({
				...options,
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
			currentActionMenu = actionMenu;
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
					// Capture all form values before the DOM is replaced by updateUI.
					const statusDropdownValue = $('#dropDown_0').val();
					const form = $('#roadAddressProjectForm');
					const capturedFormData = {
						'#tie':                         form.find('#tie').val(),
						'#osa':                         form.find('#osa').val(),
						'#trackCodeDropdown':           form.find('#trackCodeDropdown').val(),
						'#discontinuityDropdown':       form.find('#discontinuityDropdown').val(),
						'#elinvoimakeskus':             form.find('#elinvoimakeskus').val(),
						'#administrativeClassDropdown': form.find('#administrativeClassDropdown').val(),
						'#roadName':                    form.find('#roadName').val(),
						'#addrStart':                   form.find('#addrStart').val(),
						'#addrEnd':                     form.find('#addrEnd').val(),
						'#origAddrStart':               form.find('#origAddrStart').val(),
						'#origAddrEnd':                 form.find('#origAddrEnd').val(),
						'#startCPDropdown':             form.find('#startCPDropdown').val(),
						'#endCPDropdown':               form.find('#endCPDropdown').val(),
						'#sideCodeDropdown':            form.find('#sideCodeDropdown').val(),
						endDistance:                    form.find('#endDistance').val(),
						newRoadwayNumber:               form.find('#newRoadwayNumber').prop('checked') || null
					};

					// Validate Ennallaan / Numerointi constraints while the form is still open.
					// If invalid the modal is shown and the menu stays open for the user to correct.
					if (typeof projectLinkEditor.validate === 'function') {
						const isValid = projectLinkEditor.validate(
							additionalData.selectedLinks,
							statusDropdownValue,
							capturedFormData,
							options.projectCollection
						);
						if (!isValid) return;
					}

					const pendingSave = projectLinkEditor.validateAndSave(options.projectCollection, additionalData.selectedLinks, {
						projectLinkLayer: options.projectLinkLayer,
						selectedProjectLinkProperty: options.selectedProjectLinkProperty
					}, statusDropdownValue, capturedFormData);

					if (!pendingSave) {
						return;
					}

					// Immediately switch to ROAD_ADDRESSING with all buttons disabled so the user
					// sees the action menu before the HTTP response and map fetch complete.
					syncRoadAddressingState({
						recalculated: false,
						changeTableOpen: false,
						hasFormedLinks: true,
						isSaveInFlight: true
					});
					updateUI(States.ROAD_ADDRESSING, project.data, false);

					pendingSave.then(handleProjectLinkSaveResult).catch(handleProjectLinkSaveError);
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

			setProjectLinkDiscardChanges(() => {
				onCancel();
			});

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

	const clearSaveInFlight = function () {
		syncRoadAddressingState({ isSaveInFlight: false });
		if (currentActionMenu) {
			currentActionMenu.updateState({ isSaveInFlight: false });
		}
	};

	const showProjectLinkSaveError = function (message, details = null) {
		clearSaveInFlight();
		console.error('Project link save failed', details || message);
		new ConfirmPopup(message, {
			type: 'alert',
			okButtonLbl: 'OK'
		});
	};

	const handleProjectLinkSaveResult = function (result) {
		if (!result) {
			clearSaveInFlight();
			return;
		}

		if (!result.ok) {
			showProjectLinkSaveError(result.message, result.details);
			return;
		}

		if (result.operation === 'created') {
			onProjectLinksCreateSuccess(result.response);
		}

		onProjectLinksUpdated(result.response);
	};

	const handleProjectLinkSaveError = function (error) {
		showProjectLinkSaveError((error && error.message), error);
	};

	const bindInternalEvents = function (activeChild) {
		// Always work with fresh DOM references (disposable pattern)
		// Unbind any previous listeners before binding new ones
		const editSpan = rootElement.find('#editProjectSpan');
		editSpan.off('click').on('click', () => {
			if (options.projectChangeTable) { options.projectChangeTable.hide(); }
			syncRoadAddressingState({ changeTableOpen: false });
			selectLayer('roadAddressProject', true, false);

			const projectId = project.data && project.data.id;
			if (projectId && options.projectCollection) {
				Spinner.show();
				options.projectCollection.getProjectsWithLinksById(projectId)
					.then((result) => {
						onOpenProject(result);
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
						selectedProjectLinkProperty: options.selectedProjectLinkProperty,
						onChangeDirectionFailed: onChangeDirectionFailed
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
      
		render();
	};

	// --- Listeners ---
	const updateProjectMenuInternal = function (selected) {
		const currentProject = options.projectCollection ? options.projectCollection.getCurrentProject() : null;
		if (currentProject) {
			if (options.projectChangeTable && typeof options.projectChangeTable.hide === 'function') {
				options.projectChangeTable.hide();
			}
			syncRoadAddressingState({ changeTableOpen: false });
			updateUI(States.LINK_EDIT, currentProject.project, false, { selectedLinks: selected });
		}
	};

	updateProjectMenuBridge = updateProjectMenuInternal;

	const onProjectLinksUpdated = async function () {
		if (options.projectCollection) {
			options.projectCollection.setTmpDirty([]);
			options.projectCollection.setDirty([]);
		}

		syncRoadAddressingState({recalculated: false, changeTableOpen: false, hasFormedLinks: true });

		// Fetch updated project links and re-enable action buttons when the refresh completes.
		await fetchProjectLinks();
		if (!roadAddressingState.isSaveInFlight) return;
		syncRoadAddressingState({ isSaveInFlight: false });
		if (currentActionMenu && currentState === States.ROAD_ADDRESSING) {
			currentActionMenu.updateState({ isSaveInFlight: false });
		}
	};

	const onProjectLinksCreateSuccess = function () {
		if (options.projectCollection) {
			options.projectCollection.setTmpDirty([]);
		}
		syncRoadAddressingState({ hasFormedLinks: true });
		if (
			options.projectChangeTable &&
			typeof options.projectChangeTable.isChangeTableOpen === 'function' &&
			options.projectChangeTable.isChangeTableOpen()
		) {
			options.projectChangeTable.refresh();
		}
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
		syncRoadAddressingState({ hasFormedLinks: undefined });

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
			getNavigation().navigateToSelectedProject(result.linkId, project.data);
		}
		Spinner.hide();
	};

	const destroy = function () {
		setProjectLinkDiscardChanges();
	};

	return {
		openProject: onOpenProject,
		updateProjectMenu: updateProjectMenuInternal,
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
