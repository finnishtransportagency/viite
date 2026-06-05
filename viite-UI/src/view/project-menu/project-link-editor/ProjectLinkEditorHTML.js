import { dropdown } from '@components/dropdowns/Dropdown.js';
import { numberInput } from '@components/number-input/NumberInput.js';
import { button } from '@components/button/Button.js';

export function createProjectLinkEditorHTML(dependencies) {
  const {
    canUseDevTools,
    RoadAddressChangeType,
    Track,
    AdministrativeClass,
    LinkSources,
    ViiteEnumerations,
    editableStatus,
    defineOptionModifiers,
    DevAddressTool
  } = dependencies;

  const render = (project, selected, errorMessage, links = []) => {
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
            ${renderNewRoadAddressForm(selected, links, road)}
            ${renderDevTool(selected, project)}
          </form>

          ${renderChangeDirection(selected, project)}
          ${renderActionSelectedField()}
        </div>
      </div>`;
  };

  const renderFooter = (_project, projectCollection, onSave, onCancel) => {
    const hasChanges = projectCollection ? projectCollection.isDirty() : false;
    return actionButtonsForSelectedLinks(!hasChanges, onSave, onCancel);
  };

  const renderStaticInformation = (project, selected) => {
    const roadLinkSources = _.chain(selected)
      .map(s => s.roadLinkSource)
      .uniq()
      .map(a => {
        const linkGeom = _.find(LinkSources, source => source.value === parseInt(a, 10));
        return linkGeom === undefined ? LinkSources.Unknown.descriptionFI : linkGeom.descriptionFI;
      })
      .uniq()
      .join(', ')
      .value();

    return `
      ${staticField('Lisätty järjestelmään', `${project.createdBy} ${project.startDate}`)}
      ${staticField('Muokattu viimeksi', `${project.modifiedBy} ${project.dateModified}`)}
      ${staticField('Geometrian lähde', roadLinkSources)}
      ${renderLinkId(selected)}
      ${renderLinkLength(selected)}`;
  };

  const renderSelectedData = (selected) => {
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

  const renderChangeTypeSelect = (selected, project) => {
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
      className: 'project-link-change-type-dropdown',
      disabled: !projectEditable
    });
  };

  const renderNewRoadAddressForm = (selected, links, road) => {
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
              ${addDiscontinuityDropdown()}
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
        ${renderDistanceValue()}
      </div>
    `;
  };

  const renderDevTool = (links, project) => {
    if (!canUseDevTools) return '';
    const devTool = new DevAddressTool('');
    return devTool.render(links, project);
  };

  const renderChangeDirection = (selected, project) => {
    if (!editableStatus.includes(project.statusCode)) return '';

    return `
      <div hidden class="form-group changeDirectionDiv change-direction-container">
        <button id="changeDirectionButton" class="form-group changeDirection btn-primary">Käännä tieosan kasvusuunta</button>
      </div>`;
  };

  const renderDistanceValue = () => {
    return `
      <div id="distanceValue" hidden>
        <div class="form-group distance-header">
          <img src="images/calibration-point.svg" class="calibration-point"/>
          <label>ETÄISYYSLUKEMA VALINNAN</label>
        </div>
        <div class="distance-inputs">
          <label>ALUSSA</label>
          ${numberInput('beginDistance', 5, false, '--')}
          <label>LOPUSSA</label>
          ${numberInput('endDistance', 5, false, '--')}
        </div>
      </div>`;
  };

  const renderErrorMessage = (errorMessage) => {
    if (!errorMessage) return '';
    return `<label class="project-link-error-message">VIRHE: ${errorMessage}</label>`;
  };

  const renderLinkId = (selected) => {
    if (selected.length !== 1) return '';
    return staticField('Linkin ID', selected[0].linkId);
  };

  const renderLinkLength = (selected) => {
    if (selected.length === 1) {
      const length = Math.round(selected[0].endMValue - selected[0].startMValue);
      return staticField('Geometrioiden yhteenlaskettu pituus', length);
    }
    const combinedLength = selected.reduce((sum, link) => sum + Math.round(link.endMValue - link.startMValue), 0);
    return `
      <div class="form-group-metadata">
        <p class="form-control-static project-link-static-text">
          Geometrioiden yhteenlaskettu pituus: ${combinedLength}
        </p>
      </div>`;
  };

  const renderActionSelectedField = () => {
    return `
      <div class="form-group action-selected-field" hidden="true">
        <div class="project-link-static-text">Tarkista tekemäsi muutokset.<br>Jos muutokset ok, tallenna.</div>
      </div>`;
  };

  const staticField = (labelText, dataField) => {
    return `
      <div class="form-group">
        <p class="form-control-static project-link-static-text">${labelText} : ${dataField}</p>
      </div>`;
  };

  const addRoadNameField = (name, isBlocked, maxLength) => {
    const nameToDisplay = (!name || name === 'null') ? '' : name;
    const disabled = nameToDisplay !== '' && isBlocked;
    const lengthLimit = maxLength ? `maxlength="${maxLength}"` : '';
    return `
      <input type="text" class="form-control administrativeClassAndRoadName project-link-road-name" id="roadName" value="${nameToDisplay}" ${disabled ? 'disabled' : ''} ${lengthLimit}/>`;
  };

  const addTrackCodeDropdown = (trackDefaultValue) => {
    let value = trackDefaultValue;

    if (trackDefaultValue === '') {
      value = Track.Unknown.value;
    }

    return dropdown({
      id: 'trackCodeDropdown',
      className: 'form-select-small-control',
      defaultValue: value,
      options: [
        { value: '0', text: '0' },
        { value: '1', text: '1' },
        { value: '2', text: '2' }
      ]
    });
  };

  const addElinvoimakeskusDropdown = (selectedValue, isDisabled) => {

    // Build dropdown options from EVK enum values, skipping code 0 (unknown) and ordering by numeric code
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

  const addDiscontinuityDropdown = () => {
    return dropdown({
      id: 'discontinuityDropdown',
      className: 'form-select-control',
      defaultValue: '',
      options: [
        { value: '', text: '5 Jatkuva', disabled: true, hidden: true },
        { value: '1', text: '1 Tien loppu' },
        { value: '2', text: '2 Epäjatkuva' },
        { value: '3', text: '3 Elinvoimakeskuksen raja' },
        { value: '4', text: '4 Lievä epäjatkuvuus' },
        { value: '5', text: '5 Jatkuva' }
      ]
    });
  };

  const administrativeClassDropdown = (defaultValue) => {

    return dropdown({
      id: 'administrativeClassDropdown',
      className: 'form-control administrativeClassAndRoadName',
      defaultValue: defaultValue,
      options: [
        { value: '1', text: '1 Valtio' },
        { value: '2', text: '2 Kunta' },
        { value: '3', text: '3 Yksityinen' }
      ]
    });
  };

  const actionButtonsForSelectedLinks = (notDisabled, onSave, onCancel) => {
    return `
      <div class="footer-project-link-edit" id="actionButtons">
        <div>
          ${button({ id: 'saveButton', label: 'Tallenna', className: 'btn-primary update btn-save action-button', disabled: !notDisabled, onClick: onSave })}
          ${button({ id: 'cancelButton', label: 'Peruuta', className: 'cancelLink btn-cancel', onClick: onCancel })}
        </div>
      </div>`;
  };

  return {
    render,
    renderFooter
  };
}
