/*
 * LinkEditForm: Form for editing individual road links (change type, address, distance).
 * Manages complex state (FormState) for change tracking, validation, and unsaved changes.
 * Renders complete form via render() and footer via renderFooter() for MenuContainer integration.
 * Supports disposable lifecycle: rebuilt per show, all listeners bound to fresh DOM.
 * Key methods: bindEvents(), cancelChanges(), validateAndSave() for form interaction.
 */

import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { fetchProjectLinksForCurrentMap } from '@view/map/layers/ProjectLinkLayer.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { createProjectLinkEditorLogic } from './ProjectLinkEditorLogic.js';
import { createProjectLinkEditorHTML } from './ProjectLinkEditorHTML.js';
import { DevAddressTool } from './DevTool.js';

export function ProjectLinkEditor(canUseDevTools) {
	const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
	const Track = ViiteEnumerations.Track;
	const AdministrativeClass = ViiteEnumerations.AdministrativeClass;
	const LinkSources = ViiteEnumerations.LinkGeomSource;
	const CalibrationCode = ViiteEnumerations.CalibrationCode;
	const editableStatus = [ViiteEnumerations.ProjectStatus.Incomplete.value, ViiteEnumerations.ProjectStatus.ErrorInViite.value];
	const validEvks = _.map(ViiteEnumerations.EVKCodes, evk => evk);

	// ==========================================
	// STATE MANAGEMENT
	// ==========================================
	const FormState = {
		editedNameByUser: false,
		endDistanceOriginalValue: '--',
		hasUnsavedChanges: false,
		currentChangeType: null,

		setUnsavedChanges: function(status) {
			this.hasUnsavedChanges = status;
		},

		setChangeType: function(type) {
			this.currentChangeType = type;
		},

		setNameEdited: function(status) {
			this.editedNameByUser = status;
		},

		setEndDistanceOriginal: function(value) {
			this.endDistanceOriginalValue = value;
		},
      
		isEndDistanceModified: function(currentValue) {
			const changedValue = Number(currentValue);
			if (isNaN(changedValue)) return false;
			const originalValue = Number(this.endDistanceOriginalValue);
			if (isNaN(originalValue)) return true;
			return changedValue !== originalValue;
		}
	};

	// ==========================================
	// SESSION STORAGE — ORIGINAL LINK VALUES
	// ==========================================
	const originalLinksStorageKey = (projectId) => `original_links_${projectId}`;

	const getStoredOriginalLinks = (projectId) => {
		const raw = sessionStorage.getItem(originalLinksStorageKey(projectId));
		if (!raw) return {};
		try {
			const parsed = JSON.parse(raw);
			return _.isObject(parsed) ? parsed : {};
		} catch (e) {
			console.error('Failed to parse stored original links from sessionStorage', e);
			return {};
		}
	};

	const setStoredOriginalLinks = (projectId, linksByLinkId) => {
		sessionStorage.setItem(originalLinksStorageKey(projectId), JSON.stringify(linksByLinkId));
	};

	// Store the road address fields for newly seen links (first-open-wins).
	// Must be called after each link selection so originals are captured before the user edits.
	const storeOriginalLinksIfNew = (selected, projectCollection) => {
		if (!projectCollection || !_.isArray(selected) || selected.length === 0) return;
		const projectId = projectCollection.getCurrentProject().project.id;
		const stored = getStoredOriginalLinks(projectId);
		let hasChanges = false;
		_.each(selected, (link) => {
			if (!stored[link.linkId]) {
				stored[link.linkId] = {
					roadNumber: link.roadNumber,
					roadPartNumber: link.roadPartNumber,
					trackCode: link.trackCode
				};
				hasChanges = true;
			}
		});
		if (hasChanges) {
			setStoredOriginalLinks(projectId, stored);
		}
	};

	const behavior = createProjectLinkEditorLogic({
		RoadAddressChangeType,
		CalibrationCode,
		editableStatus,
		validEvks,
		formState: FormState
	});

	const {
		defineOptionModifiers,
		checkInputs,
		updateForm,
		updateFormControls
	} = behavior;

	const renderer = createProjectLinkEditorHTML({
		canUseDevTools,
		RoadAddressChangeType,
		Track,
		AdministrativeClass,
		LinkSources,
		ViiteEnumerations,
		editableStatus,
		defineOptionModifiers,
		DevAddressTool
	});

	const { render, renderFooter } = renderer;

	const getLatestLinkForSelection = (selectedLink, projectCollection) => {
		if (!selectedLink || !projectCollection || typeof projectCollection.getAll !== 'function') {
			return selectedLink;
		}

		const allLinks = projectCollection.getAll() || [];
		const latestById = _.find(allLinks, (link) => selectedLink.id && link.id === selectedLink.id);
		if (latestById) {
			return latestById;
		}

		const latestByLinkId = _.find(allLinks, (link) => selectedLink.linkId && link.linkId === selectedLink.linkId);
		return latestByLinkId || selectedLink;
	};

	const shouldAttemptPrefill = (selectedLink, projectCollection) => {
		const latestLink = getLatestLinkForSelection(selectedLink, projectCollection);

		const isUnaddressed = latestLink &&
			Number(latestLink.roadNumber) === 0 &&
			Number(latestLink.roadPartNumber) === 0 &&
			Number(latestLink.trackCode) === 99;

		return isUnaddressed;
	};

	// ==========================================
	// EVENT LISTENERS
	// ==========================================
	const bindEvents = function (project, selected, backend, projectCollection, projectChangeTable, editContext = {}) {
		const rootElement = $('#menu-container');
		// Remove all delegated listeners from previous bindEvents calls to prevent accumulation.
		// Without this, each re-render adds a new handler closure (with a stale `selected` reference),
		// causing the wrong link's data to be written to dirtyProjectLinks on dropdown change.
		rootElement.off('.projectLinkEditor');
		let isInitializing = true;
		const bindingContext = {
			projectCollection: projectCollection || editContext.projectCollection || null,
			projectLinkLayer: editContext.projectLinkLayer || null,
			selectedProjectLinkProperty: editContext.selectedProjectLinkProperty || null,
			onChangeDirectionFailed: editContext.onChangeDirectionFailed || null
		};

		const markSelectedLinksDirty = () => {
			if (bindingContext.projectCollection && _.isArray(selected) && selected.length > 0) {
				bindingContext.projectCollection.setTmpDirty(selected);
			}
		};

		const disableFormInputs = () => {
			if (!project || _.includes(editableStatus, project.statusCode)) {
				return;
			}

			rootElement.find('#roadAddressProjectForm select, #roadAddressProjectForm input').prop('disabled', true);
			rootElement.find('.footer-project-link-edit .update').prop('disabled', true);
			rootElement.find('.changeDirection').prop('disabled', true);
		};

		_.defer(() => {
			$('#beginDistance').on('change.projectLinkEditor', () => {
				if (bindingContext.projectCollection) {
					bindingContext.projectCollection.markEditedBeginDistance();
				}
			});
			$('#endDistance').on('change.projectLinkEditor', () => {
				if (bindingContext.projectCollection) {
					bindingContext.projectCollection.markEditedEndDistance();
				}
			});
		});

		rootElement.on('change.projectLinkEditor', '#administrativeClassDropdown, .form-select-control', () => {
			FormState.setUnsavedChanges(true);
			markSelectedLinksDirty();
		});

		rootElement.on('change.projectLinkEditor', '#roadAddressProjectForm #dropDown_0', (e) => {
			FormState.setChangeType(e.target.value);
			updateFormControls(e.target.value, selected, projectCollection, { markDirty: !isInitializing });
			if (projectChangeTable) {
				checkInputs(projectChangeTable, project ? project.statusCode : null);
			}
		});

		rootElement.on('change.projectLinkEditor', '#trackCodeDropdown, #administrativeClassDropdown', () => {
			if (projectChangeTable) {
				checkInputs(projectChangeTable, project ? project.statusCode : null);
			}
		});
      
		rootElement.on('change.projectLinkEditor', '.form-group', () => {
			rootElement.find('.action-selected-field').prop('hidden', false);
		});

		rootElement.on('input.projectLinkEditor', '.form-control.small-input, .number-input', function (event) {
			const dropdown_0 = $('#dropDown_0');
			const roadNameField = $('#roadName');
			if (projectChangeTable) {
				checkInputs(projectChangeTable, project ? project.statusCode : null);
			}
			FormState.setUnsavedChanges(true);
			markSelectedLinksDirty();

			if (event.target.id === "tie" && backend && projectCollection && 
            (dropdown_0.val() === 'New' || dropdown_0.val() === 'Transfer' || dropdown_0.val() === 'Numbering')) {
				rootElement.find('#saveButton').prop('disabled', true);
				const currentProject = projectCollection.getCurrentProject();
				backend.getRoadName($(this).val(), currentProject.project.id, function (data) {
					if (data.roadName) {
						FormState.setNameEdited(false);
						roadNameField.val(data.roadName).change();
						if (data.isCurrent) {
							roadNameField.prop('disabled', true);
						} else {
							roadNameField.prop('disabled', false);
						}
					} else {
						if (roadNameField.prop('disabled') || !FormState.editedNameByUser) {
							$('#roadName').val('').change();
							FormState.setNameEdited(false);
						}
						roadNameField.prop('disabled', false);
					}
					if (projectChangeTable) {
						checkInputs(projectChangeTable, project ? project.statusCode : null);
					}
				});
			}
		});

		rootElement.on('keyup.projectLinkEditor input.projectLinkEditor', '#roadName', function () {
			if (projectChangeTable) {
				checkInputs(projectChangeTable, project ? project.statusCode : null);
			}
			FormState.setNameEdited($('#roadName').val() !== '');
		});

		rootElement.on('change.projectLinkEditor', '#endDistance', () => {
			FormState.setUnsavedChanges(true);
			markSelectedLinksDirty();
		});

		rootElement.on('click.projectLinkEditor', '.changeDirection', () => {
			if (projectCollection) {
				const projectId = projectCollection.getCurrentProject().project.id;
				projectCollection.changeNewProjectLinkDirection(projectId, selected, {
					onChangeProjectDirectionClicked: function () {
						fetchProjectLinksForCurrentMap();
					},
					onChangeDirectionFailed: function (error) {
						if (typeof bindingContext.onChangeDirectionFailed === 'function') {
							bindingContext.onChangeDirectionFailed(error);
						}
					}
				});
			}
		});

		rootElement.on('input.projectLinkEditor', '#addrStart, #addrEnd', function () {
			const start = Number(document.getElementById("addrStart").value) || 0;
			const end = Number(document.getElementById("addrEnd").value) || 0;
			const res = end - start;
			document.getElementById("addrLength").textContent = res.toString();
		});

		rootElement.on('input.projectLinkEditor', '#origAddrStart, #origAddrEnd', function () {
			const start = Number(document.getElementById("origAddrStart").value) || 0;
			const end = Number(document.getElementById("origAddrEnd").value) || 0;
			const res = end - start;
			document.getElementById("origAddrLength").textContent = res.toString();
		});

		if (backend && selected && selected[0] && shouldAttemptPrefill(selected[0], projectCollection)) {
			const currentProject = projectCollection ? projectCollection.getCurrentProject() : null;
			if (currentProject) {
				backend.getPrefillValuesForLink(selected[0].linkId, currentProject.project.id, function (response) {
					if (response.success) {
						$('#tie').val(response.roadNumber);
						$('#osa').val(response.roadPartNumber);
						$('#elinvoimakeskus').val(response.evk);
              
						const roadNameField = $('#roadName');
						if (response.roadName !== '') {
							roadNameField.val(response.roadName);
							roadNameField.prop('disabled', response.roadNameSource === ViiteEnumerations.RoadNameSource.RoadAddressSource.value);
						}
              
						if (!_.isUndefined(response.roadNumber) && response.roadNumber >= 20000 && response.roadNumber <= 39999) {
							$('#trackCodeDropdown').val("0");
						}
					}

					if (projectChangeTable) {
						checkInputs(projectChangeTable, project ? project.statusCode : null);
					}
				});
			}
		}

		updateForm(selected, projectCollection);
		storeOriginalLinksIfNew(selected, projectCollection);
		updateFormControls($('#dropDown_0').val(), selected, projectCollection, { markDirty: false });
		disableFormInputs();
		isInitializing = false;
      
		if (projectChangeTable) {
			checkInputs(projectChangeTable, project ? project.statusCode : null);
		}
	};

	// ==========================================
	// VALIDATION: Ennallaan & Numerointi
	// ==========================================
	// Must be called before closing the menu so the user can correct errors while the form is still open.
	// Returns true if the save may proceed, false if a constraint is violated (modal already shown).
	const validate = (selectedLinks, statusDropdownValue, capturedFormData, projectCollection) => {
		const changeType = _.find(RoadAddressChangeType, obj => obj.description === statusDropdownValue);
		if (!changeType) return true;

		if (
			changeType.value === RoadAddressChangeType.Unchanged.value ||
        changeType.value === RoadAddressChangeType.Numbering.value
		) {
			const isUnchanged = changeType.value === RoadAddressChangeType.Unchanged.value;

			const currentRoadNumber     = Number(capturedFormData['#tie']);
			const currentRoadPartNumber = Number(capturedFormData['#osa']);
			const currentTrackCode      = Number(capturedFormData['#trackCodeDropdown']);

			const projectId = projectCollection.getCurrentProject().project.id;
			const storedOriginalLinks = getStoredOriginalLinks(projectId);

			const storedOriginals = selectedLinks.map((link) => {
				const stored = storedOriginalLinks[link.linkId];
				return stored || {
					roadNumber: link.roadNumber,
					roadPartNumber: link.roadPartNumber,
					trackCode: link.trackCode
				};
			});

			const expectedRoadNumbers     = _.chain(storedOriginals).map(o => Number(o.roadNumber)).uniq().value();
			const expectedRoadPartNumbers = _.chain(storedOriginals).map(o => Number(o.roadPartNumber)).uniq().value();
			const expectedTrackCodes      = _.chain(storedOriginals).map(o => Number(o.trackCode)).uniq().value();

			const roadNumberMismatch     = isUnchanged && !expectedRoadNumbers.includes(currentRoadNumber);
			const roadPartNumberMismatch = isUnchanged && !expectedRoadPartNumbers.includes(currentRoadPartNumber);
			const trackCodeMismatch      = !expectedTrackCodes.includes(currentTrackCode);

			if (roadNumberMismatch || roadPartNumberMismatch || trackCodeMismatch) {
				const formatExpected = (values) => (values.length === 1 ? values[0] : values.join(' / '));

				const changes = [
					roadNumberMismatch     && `Muuta tie ${currentRoadNumber} -> ${formatExpected(expectedRoadNumbers)}`,
					roadPartNumberMismatch && `Muuta osa ${currentRoadPartNumber} -> ${formatExpected(expectedRoadPartNumbers)}`,
					trackCodeMismatch      && `Muuta ajr ${currentTrackCode} -> ${formatExpected(expectedTrackCodes)}`
				].filter(Boolean);

				new ConfirmPopup(
					`${
						isUnchanged
							? 'Ennallaan-toimenpiteellä tie, osa ja ajr eivät saa muuttua.'
							: 'Numerointi-toimenpiteellä ajr ei saa muuttua.'
					}<br>${changes.join('<br>')}`,
					{ type: 'alert' }
				);
				return false;
			}
		}
		return true;
	};

	const cancelChanges = (callbacks = {}, context = {}) => {
		const projectCollectionRef = context.projectCollection || null;
		const projectLinkLayerRef = context.projectLinkLayer || null;
		const selectedProjectLinkPropertyRef = context.selectedProjectLinkProperty || null;

		if (projectCollectionRef) {
			projectCollectionRef.revertRoadAddressChangeType();
			projectCollectionRef.setDirty([]);
			projectCollectionRef.setTmpDirty([]);
		}
		if (projectLinkLayerRef) {
			projectLinkLayerRef.clearHighlights();
		}
		if (selectedProjectLinkPropertyRef) {
			selectedProjectLinkPropertyRef.cleanIds();
			selectedProjectLinkPropertyRef.clean();
		}

		if (typeof callbacks.onCancel === 'function') {
			callbacks.onCancel();
		}
	};

	const validateAndSave = (projectCollection, selectedLinks, callbacks = {}, context = {}, capturedStatusValue = null, preBuiltFormData = null) => {
		const statusDropdownValue = capturedStatusValue !== null ? capturedStatusValue : $('#dropDown_0').val();
		const changeType = _.find(RoadAddressChangeType, obj => obj.description === statusDropdownValue);
		if (!changeType) {
			console.error('validateAndSave: unknown changeType for dropdown value', statusDropdownValue);
			return;
		}
		// Use pre-captured form data when provided (caller already destroyed the DOM),
		// otherwise capture from live DOM now.
		const capturedFormData = preBuiltFormData || (() => {
			const form = $('#roadAddressProjectForm');
			return {
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
		})();
		const tmpDirty = projectCollection ? projectCollection.getTmpDirty() : [];

		if (context.projectLinkLayer) {
			context.projectLinkLayer.clearHighlights();
		}
		if (context.selectedProjectLinkProperty) {
			context.selectedProjectLinkProperty.cleanIds();
			context.selectedProjectLinkProperty.clean();
		}

		if (!validate(selectedLinks, statusDropdownValue, capturedFormData, projectCollection)) return;

		if (changeType.value === RoadAddressChangeType.Revert.value) {
			if (projectCollection) {
				projectCollection.revertChangesRoadlink(selectedLinks, {
					onProjectLinksUpdated: callbacks.onProjectLinksUpdated,
					onProjectLinksUpdateFailed: callbacks.onProjectLinksUpdateFailed
				});
			}
		} else {
			const linksToSave = tmpDirty.length > 0 ? tmpDirty : selectedLinks;
          
			if (projectCollection) {
				const isEndDistanceModified = FormState.isEndDistanceModified($('#endDistance').val());
              
				projectCollection.saveProjectLinks(linksToSave, changeType.value, isEndDistanceModified, {
					onProjectLinksCreateSuccess: callbacks.onProjectLinksCreateSuccess,
					onProjectLinksUpdated: callbacks.onProjectLinksUpdated,
					onProjectLinksUpdateFailed: callbacks.onProjectLinksUpdateFailed
				}, capturedFormData);
			}
		}
		return true;
	};

  // Public api
	return {
		render,
		bindEvents,
		renderFooter,
		checkInputs,
		updateForm,
		updateFormControls,
		cancelChanges,
		validate,
		validateAndSave
	};
}
