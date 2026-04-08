/*
 * LinkEditForm: Form for editing individual road links (change type, address, distance).
 * Manages complex state (FormState) for change tracking, validation, and unsaved changes.
 * Renders complete form via render() and footer via renderFooter() for MenuContainer integration.
 * Supports disposable lifecycle: rebuilt per show, all listeners bound to fresh DOM.
 * Key methods: bindEvents(), cancelChanges(), validateAndSave() for form interaction.
 */
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { dropdown } from '@components/dropdowns/Dropdown.js';
import { numberInput } from '@components/number-input/NumberInput.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/eventbus.js';
import { DevAddressTool } from './DevTool.js';

export function LinkEditForm(startupParameters) {
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
        const originalValue = Number(this.endDistanceOriginalValue);
        return !isNaN(changedValue) && 
               !isNaN(originalValue) && 
               changedValue !== originalValue;
      }
    };

    // ==========================================
    // HTML RENDERING (VIEW)
    // ==========================================
    const render = function (project, selected, errorMessage, links = []) {
      const road = {
        roadNumber: selected[0].roadNumber,
        roadPartNumber: selected[0].roadPartNumber,
        trackCode: selected[0].trackCode
      };

      return `
        <div class="edit-control-group project-choice-group">
          ${renderErrorMessage(errorMessage)}
          ${renderStaticInformation(project, selected)}
          
          <div class="form-group editable form-editable-roadAddressProject">
            <form id="roadAddressProjectForm" class="input-unit-combination form-group form-horizontal roadAddressProject">
              ${renderSelectedData(selected)}
              ${renderChangeTypeSelect(selected, project)}
              ${renderNewRoadAddressForm(project, selected, links, road)}
              ${renderDevTool(selected, project)}
            </form>
            
            ${renderChangeDirection(selected, project)}
            ${renderActionSelectedField()}
          </div>
        </div>`;
    };

    const renderStaticInformation = function (project, selected) {
      const roadLinkSources = _.chain(selected)
        .map(s => s.roadLinkSource)
        .uniq()
        .map(a => {
          const linkGeom = _.find(LinkSources, source => source.value === parseInt(a));
          return linkGeom === undefined ? LinkSources.Unknown.descriptionFI : linkGeom.descriptionFI;
        })
        .uniq()
        .join(", ")
        .value();

      return `
        ${staticField('Lisätty järjestelmään', `${project.createdBy} ${project.startDate}`)}
        ${staticField('Muokattu viimeksi', `${project.modifiedBy} ${project.dateModified}`)}
        ${staticField('Geometrian lähde', roadLinkSources)}
        ${renderLinkId(selected)}
        ${renderLinkLength(selected)}`;
    };

    const renderSelectedData = function (selected) {
      if (!selected[0]) return '';
      
      const link = selected[0];
      const startM = Math.min(...selected.map(l => l.addrMRange.start));
      const endM = Math.max(...selected.map(l => l.addrMRange.end));
      
      return `
        <div class="project-edit-selections">
          <div class="project-edit">
            <span>Toimenpiteet,</span>
            TIE <span class="project-edit">${link.roadNumber}</span>
            OSA <span class="project-edit">${link.roadPartNumber}</span>
            AJR <span class="project-edit">${link.trackCode}</span>
            M: <span class="project-edit">${startM} - ${endM}</span>
            ${selected.length > 1 ? `(${selected.length} linkkiä)` : ''}
          </div>
        </div>`;
    };

    const renderChangeTypeSelect = function (selected, project) {
      const defaultOption = selected[0].status === RoadAddressChangeType.NotHandled.value 
        ? RoadAddressChangeType.NotHandled.description 
        : RoadAddressChangeType.Undefined.description;
      
      const changeTypes = [
        RoadAddressChangeType.Unchanged,
        RoadAddressChangeType.Transfer,
        RoadAddressChangeType.New,
        RoadAddressChangeType.Terminated,
        RoadAddressChangeType.Numbering,
        RoadAddressChangeType.Revert
      ];

      const options = [
        { value: '', text: 'Valitse', id: 'drop_0_' },
        ...changeTypes.map(type => {
          const modifiers = defineOptionModifiers(type.description, selected);
          return {
            value: type.description,
            text: type.displayText,
            id: `drop_0_${type.description}`,
            disabled: modifiers.includes('disabled'),
            selected: modifiers.includes('selected')
          };
        })
      ];

      const projectEditable = project && editableStatus.includes(project.statusCode);
      return dropdown({
        id: 'dropDown_0',
        options: options,
        defaultValue: defaultOption,
        style: "width: 220px;",
        disabled: !projectEditable
      });
    };

    const renderNewRoadAddressForm = function (project, selected, links, road) {
      const roadNumber = road.roadNumber;
      const track = road.trackCode;
      const roadName = selected[0].roadName;
      
      let link = (links && links.length > 0) ? _.head(_.filter(links, l => l.status !== undefined)) : selected[0];
      if (!link) link = selected[0];

      const administrativeClass = link.administrativeClassId ? link.administrativeClassId : AdministrativeClass.Empty.value;
      
      let trackCodeDropdown = track;
      if (track === Track.Unknown.value) {
        trackCodeDropdown = (roadNumber >= 20000 && roadNumber <= 39999) ? '0' : '';
      }

      // Get existing values from the link if they exist
      const existingTie = link.roadNumber || roadNumber || '';
      const existingOsa = link.roadPartNumber || '';

      return `
        <div class="form-group new-road-address" hidden>
          <div><label class="section-header">TIEOSOITTEEN TIEDOT</label></div>
          <div class="road-address-fields-wrapper">
            <div class="road-address-fields">
              <div class="road-address-field field-tie">
                <label>TIE</label>
                ${numberInput('tie', 5, false, existingTie)}
              </div>
              <div class="road-address-field field-osa">
                <label>OSA</label>
                ${numberInput('osa', 5, false, existingOsa)}
              </div>
              <div class="road-address-field field-ajr">
                <label>AJR</label>
                ${addTrackCodeDropdown(trackCodeDropdown)}
              </div>
              <div class="road-address-field field-evk">
                <label>ELINVOIMAKESKUS</label>
                ${addElinvoimakeskusDropdown(link.evkCode, false)}
              </div>
              <div class="road-address-field field-jatkuu">
                <label>JATKUU</label>
                ${addDiscontinuityDropdown('road-address-input')}
              </div>
            </div>
          </div>
          <div class="road-address-horizontal-sections">
            <div class="road-address-section">
              <label class="control-label-wide">HALL. LUOKKA</label>
              ${administrativeClassDropdown(administrativeClass)}
            </div>
            <div class="road-address-section">
              <label class="control-label-wide">NIMI</label>
              ${addRoadNameField(roadName, selected[0].roadNameBlocked, 50)}
            </div>
          </div>
          <div>Tarkista tekemäsi muutokset. Jos muutokset ok, tallenna.</div>
          ${renderDistanceValue()}
        </div>
      `;
    };

    const renderDevTool = function (links, project) {
      if (!startupParameters || !_.includes(startupParameters.roles, 'dev')) return '';
      const devTool = new DevAddressTool('');
      return devTool.render(links, project);
    };

    const renderChangeDirection = function (selected, project) {
      if (!editableStatus.includes(project.statusCode)) return '';
      
      const reversedInGroup = _.uniq(selected.map(s => s.reversed));
      const isPartialReversed = reversedInGroup.length > 1;
      let infoLabel = '';
      
      if (selected[0].status !== RoadAddressChangeType.New.value) {
        if (isPartialReversed) {
          infoLabel = `<label class="form-group" style="color: white;">Osittain käännetty</label>`;
        } else if (selected[0].reversed) {
          infoLabel = `<label class="form-group" style="color: white;"><span">&#9745;</span> Käännetty</label>`;
        } else {
          infoLabel = `<label class="form-group" style="color: white;"><span>&#9744;</span> Käännetty</label>`;
        }
      }

      return `
        <div hidden class="form-group changeDirectionDiv" style="margin-top:15px">
          <button id="changeDirectionButton" class="form-group changeDirection btn-primary">Käännä tieosan kasvusuunta</button>
          ${infoLabel}
        </div>`;
    };

    const renderDistanceValue = function () {
      return `
        <div id="distanceValue">
          <div class="form-group distance-header">
            <img src="images/calibration-point.svg" class="calibration-point"/>
            <label>ETÄISYYSLUKEMA VALINNAN</label>
          </div>
          <div class="distance-inputs">
            <label>ALUSSA</label>
            ${numberInput('beginDistance', 5, false, '--')}
            <label>LOPUSSA</label>
            ${numberInput('endDistance', 5, false, '--')}
            <span id="manualCPWarning" class="manualCPWarningSpan">!</span>
          </div>
        </div>`;
    };

    const renderErrorMessage = function (errorMessage) {
      if (!errorMessage) return "";
      return `<label style="text-transform: none; color: white;">VIRHE: ${errorMessage}</label>`;
    };

    const renderLinkId = function (selected) {
      if (selected.length !== 1) return '';
      return staticField('Linkin ID', selected[0].linkId);
    };

    const renderLinkLength = function (selected) {
      if (selected.length === 1) {
        const length = Math.round(selected[0].endMValue - selected[0].startMValue);
        return staticField('Geometrian pituus', length);
      }
      const combinedLength = selected.reduce((sum, link) => sum + Math.round(link.endMValue - link.startMValue), 0);
      return `
        <div class="form-group-metadata">
          <p class="form-control-static asset-log-info-metadata" style="color: white;">
            Geometrian pituus: ${combinedLength}
          </p>
        </div>`;
    };

    const renderActionSelectedField = function () {
      return `
        <div class="form-group action-selected-field" hidden="true">
          <div class="asset-log-info" style="color: white;">Tarkista tekemäsi muutokset.<br>Jos muutokset ok, tallenna.</div>
        </div>`;
    };

    const staticField = function (labelText, dataField) {
      return `
        <div class="form-group">
          <p class="form-control-static asset-log-info" style="color: white;">${labelText} : ${dataField}</p>
        </div>`;
    };

    const addRoadNameField = function (name, isBlocked, maxLength) {
      const nameToDisplay = (!name || name === 'null') ? "" : name;
      const disabled = nameToDisplay !== "" && isBlocked;
      const lengthLimit = maxLength ? `maxlength="${maxLength}"` : '';
      return `
        <input type="text" class="form-control administrativeClassAndRoadName" style="float:none; display:inline-block; color: white; background-color: #646461;" id="roadName" value="${nameToDisplay}" ${disabled ? 'disabled' : ''} ${lengthLimit}/>`;
    };

    const addTrackCodeDropdown = function (trackDefaultValue) {
      let value = trackDefaultValue;
      let toShow = trackDefaultValue;
    
      if (trackDefaultValue === '') {
        value = Track.Unknown.value;
        toShow = '--';
      }

      return dropdown({
        id: 'trackCodeDropdown',
        className: 'form-select-small-control',
        defaultValue: value,
        options: [
          { value: value, text: toShow, disabled: toShow === '--' },
          { value: '0', text: '0' },
          { value: '1', text: '1' },
          { value: '2', text: '2' }
        ]
      });
    };

    const addElinvoimakeskusDropdown = function (selectedValue, isDisabled) {
      const evkOptions = Object.entries(ViiteEnumerations.EVKCodes)
        .filter(([, val]) => val.value !== 0)
        .sort((a, b) => a[1].value - b[1].value)
        .map(([, val]) => ({
          value: val.value,
          text: `${val.value} ${val.name}`
        }));

      const defaultOption = selectedValue === 0 
        ? { value: '0', text: '0 Tuntematon elinvoimakeskus' }
        : { value: '', text: 'Valitse elinvoimakeskus' };

      return dropdown({
        id: 'elinvoimakeskus',
        className: 'form-select-control',
        defaultValue: selectedValue === 0 ? '0' : selectedValue,
        disabled: isDisabled,
        options: [defaultOption, ...evkOptions]
      });
    };

    const addDiscontinuityDropdown = function () {
      return dropdown({
        id: 'discontinuityDropdown',
        className: 'form-select-control',
        defaultValue: '5',
        options: [
          { value: '5', text: '5 Jatkuva', disabled: true, hidden: true },
          { value: '1', text: '1 Tien loppu' },
          { value: '2', text: '2 Epäjatkuva' },
          { value: '3', text: '3 Elinvoimakeskuksen raja' },
          { value: '4', text: '4 Lievä epäjatkuvuus' },
          { value: '5', text: '5 Jatkuva' }
        ]
      });
    };

    const administrativeClassDropdown = function (defaultValue) {
      const adminClassInfo = _.find(AdministrativeClass, obj => obj.value === defaultValue);
      const labelText = adminClassInfo ? adminClassInfo.displayText : '';
      
      return dropdown({
        id: 'administrativeClassDropdown',
        className: 'form-control administrativeClassAndRoadName',
        defaultValue: defaultValue,
        options: [
          { value: defaultValue, text: labelText, hidden: true },
          { value: '1', text: '1 Valtio' },
          { value: '2', text: '2 Kunta' },
          { value: '3', text: '3 Yksityinen' }
        ]
      });
    };

    const actionButtonsForSelectedLinks = function (btnPrefix, notDisabled) {
      return `
      <div class="footer-project-link-edit" id="actionButtons">
        <div>
          <button id="saveButton" class="btn-primary update btn-save action-button" ${notDisabled ? '' : 'disabled'}>Tallenna</button>
          <button id="cancelButton" class="cancelLink btn-cancel">Peruuta</button>
        </div>
      </div>`;
    };

    const renderFooter = function (project, projectCollection) {
      const hasChanges = projectCollection ? projectCollection.isDirty() : false;
      return actionButtonsForSelectedLinks('link-', !hasChanges);
    };

    // ==========================================
    // 3. UTILITIES & LOGIC
    // ==========================================
    const transitionModifiers = (targetStatus, currentStatus) => {
      const mod = _.includes(targetStatus.transitionFrom, currentStatus) ? '' : 'disabled hidden';
      return currentStatus === targetStatus.value ? `${mod} selected` : mod;
    };

    const defineOptionModifiers = (option, selection) => {
      const roadAddressChangeType = selection[0].status;
      const targetRoadAddressChangeType = _.find(RoadAddressChangeType, ls => ls.description === option || (option === '' && ls.value === 99));
      return transitionModifiers(targetRoadAddressChangeType, roadAddressChangeType);
    };

    const isProjectEditable = (projectCollection) => {
      if (!projectCollection || !projectCollection.getCurrentProject()) return false;
      return _.includes(editableStatus, projectCollection.getCurrentProject().project.statusCode);
    };

    const checkInputs = (projectChangeTable) => {
      const rootElement = $('#menu-container');
      const inputs = rootElement.find('input');
      const pedestrianRoads = 70000;
      
      let filled = _.every(inputs, input => {
        if (input.type !== 'text') return true;
        if (input.value) return true;
        const isPedestrian = $('#tie')[0].value >= pedestrianRoads;
        return isPedestrian && input.id === 'roadName';
      });

      const trackCodeDropdown = $('#trackCodeDropdown')[0];
      filled = filled && trackCodeDropdown && trackCodeDropdown.value && trackCodeDropdown.value !== '99';

      const administrativeClassCodeDropdown = $('#administrativeClassDropdown')[0];
      filled = filled && 
        administrativeClassCodeDropdown && 
        administrativeClassCodeDropdown.value && 
        administrativeClassCodeDropdown.value !== '0' && 
        administrativeClassCodeDropdown.value !== '99';

      const updateButton = rootElement.find('.link-form button.update');
      updateButton.prop('disabled', !(filled && !projectChangeTable.isChangeTableOpen()));
    };

    const changeDropDownValue = function (statusCode, selectedLinks, projectCollection) {
      const dropdown_0_new = $("#dropDown_0 option[value=" + RoadAddressChangeType.New.description + "]");
      const rootElement = $('#menu-container');
      
      switch (statusCode) {
        case RoadAddressChangeType.Unchanged.value:
          dropdown_0_new.prop('disabled', true);
          $("#dropDown_0 option[value=" + RoadAddressChangeType.Unchanged.description + "]").attr('selected', 'selected').change();
          break;
        case RoadAddressChangeType.New.value:
          dropdown_0_new.attr('selected', 'selected').change();
          if (projectCollection) {
            projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedLinks));
          }
          rootElement.find('.new-road-address').prop("hidden", false);
          if (selectedLinks[0].id !== 0)
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
          break;
        case RoadAddressChangeType.Transfer.value:
          dropdown_0_new.prop('disabled', true);
          $("#dropDown_0 option[value=" + RoadAddressChangeType.Transfer.description + "]").attr('selected', 'selected').change();
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          break;
        case RoadAddressChangeType.Numbering.value:
          $("#dropDown_0 option[value=" + RoadAddressChangeType.Numbering.description + "]").attr('selected', 'selected').change();
          break;
        case RoadAddressChangeType.Terminated.value:
          $("#dropDown_0 option[value=" + RoadAddressChangeType.Terminated.description + "]").attr('selected', 'selected').change();
          break;
        default:
          break;
      }
      if (selectedLinks && selectedLinks.length > 0) {
        $('#discontinuityDropdown').val(selectedLinks[selectedLinks.length - 1].discontinuity);
      }
    };

    const fillDistanceValues = (selectedLinks, projectCollection) => {
      const beginDistance = $('#beginDistance');
      const endDistance = $('#endDistance');
      
      if (selectedLinks.length === 1 && selectedLinks[0].calibrationCode === CalibrationCode.AtBoth.value) {
        beginDistance.val(selectedLinks[0].addrMRange.start);
        if (isProjectEditable(projectCollection)) {
          endDistance.prop('readonly', false).val(selectedLinks[0].addrMRange.end);
        } else {
          endDistance.val(selectedLinks[0].addrMRange.end);
        }
      } else {
        const orderedByStartM = _.sortBy(selectedLinks, l => l.addrMRange.start);
        if (orderedByStartM[0].calibrationCode === CalibrationCode.AtBeginning.value) {
          beginDistance.val(orderedByStartM[0].addrMRange.start);
        }
        
        const lastLink = orderedByStartM[orderedByStartM.length - 1];
        if (lastLink.calibrationCode === CalibrationCode.AtEnd.value) {
          if (isProjectEditable(projectCollection)) {
            endDistance.prop('readonly', false).val(lastLink.addrMRange.end);
          } else {
            endDistance.val(lastLink.addrMRange.end);
          }
          FormState.setEndDistanceOriginal(lastLink.addrMRange.end);
        }
      }
    };

    const updateForm = function (selected, projectCollection) {
      if (!selected || !selected[0]) return;
      changeDropDownValue(selected[0].status, selected, projectCollection);
      const projectLinkMaxByEndAddressM = _.maxBy(selected, link => link.addrMRange.end);
      if (projectLinkMaxByEndAddressM) {
        const selectedDiscontinuity = projectLinkMaxByEndAddressM.addrMRange.end === 0
          ? _.minBy(selected, pl => pl.discontinuity).discontinuity
          : projectLinkMaxByEndAddressM.discontinuity;
          
        $('#discontinuityDropdown').val(selectedDiscontinuity.toString());
      }
    };

    const updateFormControls = (changeType, selectedLinks, projectCollection) => {
      const rootElement = $('#menu-container');
      
      const formControls = {
        tie: $('#tie'),
        osa: $('#osa'),
        trackCode: $('#trackCodeDropdown'),
        discontinuity: $('#discontinuityDropdown'),
        adminClass: $('#administrativeClassDropdown')
      };

      const uiElements = {
        devTool: rootElement.find('.dev-address-tool'),
        newRoadAddress: rootElement.find('.new-road-address'),
        changeDirection: rootElement.find('.changeDirectionDiv'),
        distanceValue: rootElement.find('#distanceValue'),
        updateButton: rootElement.find('.link-form button.update')
      };

      const enableFields = (enabled) => {
        Object.values(formControls).forEach(control => control.prop('disabled', !enabled));
      };

      const mapLinkData = (link, status) => ({
        id: link.id,
        linkId: link.linkId,
        status: status,
        roadLinkSource: link.roadLinkSource,
        points: link.points,
        linearLocationId: link.linearLocationId
      });

      switch (changeType) {
        case RoadAddressChangeType.Terminated.description:
          enableFields(false);
          uiElements.devTool.prop('hidden', false);
          uiElements.newRoadAddress.prop('hidden', true);
          uiElements.changeDirection.prop('hidden', true);
          if (projectCollection) {
            projectCollection.setDirty(selectedLinks.map(link => mapLinkData(link, RoadAddressChangeType.Terminated.value)));
          }
          break;

        case RoadAddressChangeType.New.description:
          enableFields(true);
          uiElements.devTool.prop('hidden', false);
          uiElements.newRoadAddress.prop('hidden', false);
          if (projectCollection) {
            projectCollection.setDirty(selectedLinks.map(link => mapLinkData(link, RoadAddressChangeType.New.value)));
          }
          if (selectedLinks[0].id !== -1) {
            fillDistanceValues(selectedLinks, projectCollection);
            uiElements.changeDirection.prop('hidden', false);
            uiElements.distanceValue.prop('hidden', false);
          }
          break;

        case RoadAddressChangeType.Unchanged.description:
          formControls.tie.prop('disabled', true);
          formControls.osa.prop('disabled', true);
          formControls.trackCode.prop('disabled', true);
          formControls.discontinuity.prop('disabled', false);
          formControls.adminClass.prop('disabled', false);
          
          uiElements.devTool.prop('hidden', false);
          uiElements.newRoadAddress.prop('hidden', false);
          uiElements.changeDirection.prop('hidden', true);
          
          if (projectCollection) {
            projectCollection.setDirty(selectedLinks.map(link => mapLinkData(link, RoadAddressChangeType.Unchanged.value)));
          }
          break;

        case RoadAddressChangeType.Transfer.description:
          enableFields(true);
          uiElements.newRoadAddress.prop('hidden', false);
          uiElements.devTool.prop('hidden', false);
          if (projectCollection) {
            projectCollection.setDirty(selectedLinks.map(link => mapLinkData(link, RoadAddressChangeType.Transfer.value)));
          }
          break;

        case RoadAddressChangeType.Numbering.description:
          uiElements.devTool.prop('hidden', false);
          new ConfirmPopup("Numerointi koskee kokonaista tieosaa. Valintaasi on tarvittaessa laajennettu koko tieosale.", { type: "alert" });
          formControls.tie.prop('disabled', false);
          formControls.osa.prop('disabled', false);
          formControls.trackCode.prop('disabled', true);
          formControls.discontinuity.prop('disabled', false);
          formControls.adminClass.prop('disabled', true);
          
          if (projectCollection) {
            projectCollection.setDirty(selectedLinks.map(link => mapLinkData(link, RoadAddressChangeType.Numbering.value)));
          }
          uiElements.newRoadAddress.prop('hidden', false);
          uiElements.updateButton.prop('disabled', false);
          break;

        case RoadAddressChangeType.Revert.description:
          uiElements.devTool.prop('hidden', true);
          uiElements.newRoadAddress.prop('hidden', true);
          uiElements.changeDirection.prop('hidden', true);
          uiElements.updateButton.prop('disabled', false);
          break;

        default:
          uiElements.devTool.prop('hidden', true);
          uiElements.newRoadAddress.prop('hidden', true);
          uiElements.changeDirection.prop('hidden', true);
          break;
      }

      if (projectCollection) {
        projectCollection.setTmpDirty(projectCollection.getDirty());
      }
    };

    const validateEVK = function(evkValue, changeType) {
      if (changeType.value === RoadAddressChangeType.Terminated.value) {
        return true;
      }
      let isValidEvk = _.some(validEvks, evk => evk.value === evkValue);
      if (evkValue === 0 && changeType !== RoadAddressChangeType.Revert) {
        isValidEvk = false;
      }
      return isValidEvk;
    };

    // ==========================================
    // 4. EVENT LISTENERS (CONTROLLER)
    // ==========================================
    const bindEvents = function (project, selected, backend, projectCollection, projectChangeTable, editContext = {}) {
      const rootElement = $('#menu-container');
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
        updateFormControls(e.target.value, selected, projectCollection);
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
          rootElement.find('.link-form button.update').prop("disabled", true);
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

      rootElement.on('keyup, input', '#roadName', function () {
        if (projectChangeTable) {
          checkInputs(projectChangeTable);
        }
        FormState.setNameEdited($('#roadName').val() !== '');
      });

      rootElement.on('change', '#endDistance', (eventData) => {
        FormState.setUnsavedChanges(true);
        const shouldShowWarning = FormState.isEndDistanceModified(eventData.target.value);
        $('#manualCPWarning').css('display', shouldShowWarning ? 'inline-block' : 'none');
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

      eventbus.on('roadAddressProject:discardChanges', cancelChanges);

    // ==========================================
    // 5. PUBLIC API
    // ==========================================
    return {
      render,
      bindEvents,
      renderFooter,
      checkInputs,
      updateForm,
      updateFormControls,
      validateEVK,
      cancelChanges,
      validateAndSave: function(projectCollection, selectedLinks) {
        const statusDropdownValue = $('#dropDown_0').val();
        const changeType = _.find(RoadAddressChangeType, obj => obj.description === statusDropdownValue);
        
        if (!this.validateEVK(parseInt($('#elinvoimakeskus')[0].value), changeType)) {
          new ConfirmPopup('Tarkista antamasi Elinvoimakeskus-koodi. Annettu arvo on virheellinen.', { type: "alert" });
          return false;
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
            const isEndDistanceModified = projectCollection.getTmpDirty().length > 0 
              ? FormState.isEndDistanceModified($('#endDistance').val())
              : false;
              
            projectCollection.saveProjectLinks(linksToSave, changeType.value, isEndDistanceModified);
          }
        }
        return true;
      }
    };
}
