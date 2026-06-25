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
import { fetchProjectLinksForCurrentMap, clearOnProjectClose as clearProjectLinkLayer } from '@view/map/layers/ProjectLinkLayer.js';
import { clearLinkPropertyLayer } from '@view/map/layers/LinkPropertyLayer.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { selectLayer } from '@model/ApplicationModel.js';
import { button } from '@components/button/Button.js';

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
		backend,
		projectChangeInfoModel,
		container = '#menu-container',
		closeProjectMenu,
		initialState = {},
		onStateChange
	} = options;
	const mainMenu = options.mainMenu;
	const projectChangeTable = getOrCreateProjectChangeTable(projectChangeInfoModel, projectCollection);

	const state = Object.assign({
		hasErrors: false,
		changeTableOpen: false,
		recalculated: false,
		publishable: false,
		isProjectPublished: false,
		isSaveInFlight: false
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

		// Short-circuit: a save is in-flight — keep all action buttons disabled until the
		// background map fetch completes and ProjectMenu clears isSaveInFlight.
		if (state.isSaveInFlight) {
			const waitTitle = 'Projektilinkkien käsittely kesken, odota hetki';
			config.buttonStates.recalculate = { disabled: true, title: waitTitle };
			config.buttonStates.changes    = { disabled: true, title: waitTitle };
			config.buttonStates.send       = { disabled: true, title: waitTitle };
			return;
		}

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
				config.buttonStates.recalculate = state.hasFormedLinks !== false
					? { disabled: false, title: '' }
					: { disabled: true, title: config.disabledTitles.recalculate };
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
			const infoMessage = _.trim((error.info || '').toString());
			const errorLabel = errorMessage ? `<label class="orange">VIRHE: ${errorMessage}</label>` : '';
			const infoLabel = (infoMessage && infoMessage !== 'N/A') ? `<label class="orange">INFO: ${infoMessage}</label>` : '';

			if (coords) {
				const fixCoords = coords;
				fixButton = button({
					id: `project-error-fix-${buttonIndex}`,
					label: 'Korjaa',
					className: 'btn-primary btn-error-fix',
					onClick: () => {
						if (Array.isArray(fixCoords) && fixCoords.length >= 2 && isFinite(fixCoords[0]) && isFinite(fixCoords[1])) {
							const view = map.getView();
							view.animate({ center: fixCoords, zoom: Math.max(view.getZoom(), 15), duration: 0 });
						} else {
							new ConfirmPopup('Virheelliset koordinaatit. Ei voida siirtyä kohteeseen.', { type: 'alert', okButtonLbl: 'OK' });
						}
					}
				});
				coordinates.push({index: buttonIndex, html: fixButton, coordinates: coords});
				buttonIndex++;
			}

			const errorLinkIds = error.linkIds;
			const linkIdButton = (errorLinkIds && errorLinkIds.length > 0)
				? button({
					id: `project-error-link-ids-${errorIndex}`,
					label: 'Linkkien id:t',
					className: 'btn-primary',
					onClick: () => {
						const linkIdText = errorLinkIds.length > 0 ? errorLinkIds.join(', ') : 'Ei linkkejä';
						new ConfirmPopup(`Linkkien ID:t: ${linkIdText}`, { type: 'alert', okButtonLbl: 'OK' });
					}
				})
				: '';

			// Use divider if not last error
			const divider = (errorIndex < projectErrors.length - 1) ? '<div class="error-divider"></div>' : '';

			errorLines += `
          <div class="form-project-errors-list">
            ${errorLabel}
            ${infoLabel}
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

		const validateBtn = options.canUseDevTools
			? button({ id: 'validate-button', label: 'Validoi projekti', className: config.cssClasses.validate, disabled: btns.validate.disabled, title: btns.validate.title, onClick: handleValidateClick })
			: '';

		return `
        <div class="footer-project-action-menu">
          ${validateBtn}
          ${button({ id: 'recalculate-button', label: 'Päivitä etäisyyslukemat', className: config.cssClasses.recalculate, disabled: btns.recalculate.disabled, title: btns.recalculate.title, onClick: handleRecalculateClick })}
          ${button({ id: 'changes-button', label: 'Avaa projektin yhteenvetotaulukko', className: config.cssClasses.changes, disabled: btns.changes.disabled, title: btns.changes.title, onClick: handleChangesClick })}
          ${button({ id: 'send-button', label: 'Hyväksy tieosoitemuutokset', className: config.cssClasses.send, disabled: btns.send.disabled, title: btns.send.title, onClick: handleSendClick })}
        </div>`;
	};

	// ==========================================
	// EVENT HANDLERS
	// ==========================================

	const closeProjectMode = (_changeLayerMode, noSave) => {
		if (projectChangeTable) { projectChangeTable.hide(); }
		projectCollection.clearRoadAddressProjects();

		if (typeof closeProjectMenu === 'function') {
			closeProjectMenu({ noSave: Boolean(noSave) });
		} else if (mainMenu && typeof mainMenu.setState === 'function') {

			clearLinkPropertyLayer();
			clearProjectLinkLayer();
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

				fetchProjectLinksForCurrentMap();
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
				projectCollection.publishProject({
					onProjectSentSuccess: function () {
						if (typeof options.onProjectSentSuccess === 'function') {
							options.onProjectSentSuccess();
						}
						closeProjectMode(true, true);
					},
					onProjectSentFailed: function (error) {
						if (typeof options.onProjectSentFailed === 'function') {
							options.onProjectSentFailed(error);
						}
					}
				});
			}
		});
	};

	// ==========================================
	// BINDINGS & PUBLIC API
	// ==========================================

	const bindEvents = function () {
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

		evaluateButtonStates();
		refresh();
	};

	return {
		renderContent,
		renderFooter,
		bindEvents,
		updateState,
		getProjectChangeTable: () => projectChangeTable
	};
}
