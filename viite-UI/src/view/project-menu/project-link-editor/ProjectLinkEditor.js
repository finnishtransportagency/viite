/*
 * LinkEditForm: Form for editing individual road links (change type, address, distance).
 * Manages complex state (FormState) for change tracking, validation, and unsaved changes.
 * Renders complete form via render() and footer via renderFooter() for MenuContainer integration.
 * Supports disposable lifecycle: rebuilt per show, all listeners bound to fresh DOM.
 * Key methods: bindEvents(), cancelChanges(), validateAndSave() for form interaction.
 */

import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/Eventbus.js';
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
    const activeContext = {
      projectCollection: null,
      projectLinkLayer: null,
      selectedProjectLinkProperty: null
    };

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
        if (typeof eventbus !== 'undefined') {
          eventbus.trigger('roadAddressProject:toggleEditingRoad', !status);
        }
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

    // ==========================================
    // EVENT LISTENERS
    // ==========================================
    const bindEvents = function (project, selected, backend, projectCollection, projectChangeTable, editContext = {}) {
      const rootElement = $('#menu-container');
      let isInitializing = true;
      activeContext.projectCollection = projectCollection || editContext.projectCollection;
      activeContext.projectLinkLayer = editContext.projectLinkLayer;
      activeContext.selectedProjectLinkProperty = editContext.selectedProjectLinkProperty;

      const disableFormInputs = () => {
        if (!project || _.includes(editableStatus, project.statusCode)) {
          return;
        }

        rootElement.find('#roadAddressProjectForm select, #roadAddressProjectForm input').prop('disabled', true);
        rootElement.find('.footer-project-link-edit .update').prop('disabled', true);
        rootElement.find('.changeDirection').prop('disabled', true);
      };

      _.defer(() => {
        $('#beginDistance').on('change', (changedData) => {
          if (typeof eventbus !== 'undefined') {
            eventbus.trigger('projectLink:editedBeginDistance', changedData.target.value);
          }
        });
        $('#endDistance').on('change', (changedData) => {
          if (typeof eventbus !== 'undefined') {
            eventbus.trigger('projectLink:editedEndDistance', changedData.target.value);
          }
        });
      });

      rootElement.on('change', '#administrativeClassDropdown, .form-select-control', () => {
        FormState.setUnsavedChanges(true);
      });

      rootElement.on('change', '#roadAddressProjectForm #dropDown_0', (e) => {
        FormState.setChangeType(e.target.value);
        updateFormControls(e.target.value, selected, projectCollection, { markDirty: !isInitializing });
        if (projectChangeTable) {
          checkInputs(projectChangeTable);
        }
      });

      rootElement.on('change', '#trackCodeDropdown, #administrativeClassDropdown', () => {
        if (projectChangeTable) {
          checkInputs(projectChangeTable);
        }
      });
      
      rootElement.on('change', '.form-group', () => {
        rootElement.find('.action-selected-field').prop('hidden', false);
      });

      rootElement.on('input', '.form-control.small-input, .number-input', function (event) {
        const dropdown_0 = $('#dropDown_0');
        const roadNameField = $('#roadName');
        if (projectChangeTable) {
          checkInputs(projectChangeTable);
        }
        FormState.setUnsavedChanges(true);

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
              checkInputs(projectChangeTable);
            }
          });
        }
      });

      rootElement.on('keyup input', '#roadName', function () {
        if (projectChangeTable) {
          checkInputs(projectChangeTable);
        }
        FormState.setNameEdited($('#roadName').val() !== '');
      });

      rootElement.on('change', '#endDistance', (eventData) => {
        FormState.setUnsavedChanges(true);
      });

      rootElement.on('click', '.changeDirection', () => {
        if (projectCollection) {
          const projectId = projectCollection.getCurrentProject().project.id;
          projectCollection.changeNewProjectLinkDirection(projectId, selected);
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

      if (backend && selected && selected[0] && 
          selected[0].roadNumber === 0 && selected[0].roadPartNumber === 0 && selected[0].trackCode === 99) {
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
          });
        }
      }

      updateForm(selected, projectCollection);
      disableFormInputs();
      isInitializing = false;
      
      if (projectChangeTable) {
        checkInputs(projectChangeTable);
      }
    };

      const cancelChanges = (callbacks = {}) => {
        const projectCollectionRef = activeContext.projectCollection;
        const projectLinkLayerRef = activeContext.projectLinkLayer;
        const selectedProjectLinkPropertyRef = activeContext.selectedProjectLinkProperty;

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

        eventbus.trigger('roadAddress:projectLinksEdited');
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);

        if (typeof callbacks.onCancel === 'function') {
          callbacks.onCancel();
        } else {
          eventbus.trigger('roadAddressProject:reOpenCurrent');
        }
      };

      const validateAndSave = (projectCollection, selectedLinks) => {
        const statusDropdownValue = $('#dropDown_0').val();
        const changeType = _.find(RoadAddressChangeType, obj => obj.description === statusDropdownValue);

        if (activeContext.projectLinkLayer) {
          activeContext.projectLinkLayer.clearHighlights();
        }
        if (activeContext.selectedProjectLinkProperty) {
          activeContext.selectedProjectLinkProperty.cleanIds();
          activeContext.selectedProjectLinkProperty.clean();
        }

        if (changeType.value === RoadAddressChangeType.Revert.value) {
          if (projectCollection) {
            projectCollection.revertChangesRoadlink(selectedLinks);
          }
        } else {
          const linksToSave = projectCollection && projectCollection.getTmpDirty().length > 0 
            ? projectCollection.getTmpDirty() 
            : selectedLinks;
          
          if (projectCollection) {
            const isEndDistanceModified = FormState.isEndDistanceModified($('#endDistance').val());
              
            projectCollection.saveProjectLinks(linksToSave, changeType.value, isEndDistanceModified);
          }
        }
        return true;
      };

      eventbus.off('roadAddressProject:discardChanges', cancelChanges);
      eventbus.on('roadAddressProject:discardChanges', cancelChanges);

      
    // ==========================================
    // PUBLIC API
    // ==========================================
    return {
      render,
      bindEvents,
      renderFooter,
      checkInputs,
      updateForm,
      updateFormControls,
      cancelChanges,
      validateAndSave
    };
}
