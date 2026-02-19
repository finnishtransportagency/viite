(function (root) {
  root.FormCommon = function (prefix) {
    const Track = ViiteEnumerations.Track;
    const RoadNameSource = ViiteEnumerations.RoadNameSource;
    const editableStatus = ViiteEnumerations.ProjectStatus.Incomplete.value;
    const AdministrativeClass = ViiteEnumerations.AdministrativeClass;

    const smallWidthStyle = 'width: 40px !important;';

    const title = function (titleName) {
      const fixedTitle = titleName || "Uusi tieosoiteprojekti";
      return `<span class ="edit-mode-title"> ${fixedTitle} </span>`;
    };

    const titleWithEditingTool = function (project) {
      return `
      <span class ="edit-mode-title">${project.name} <i id="editProjectSpan" class="btn-pencil-edit fas fa-pencil-alt" value="${project.id}"></i></span>
      <span id="closeProjectSpan" class="rightSideSpan">Sulje <i class="fas fa-window-close"></i></span>`;
    };

    const captionTitle = function (titleName) {
      return `<span class="caption-title"> ${titleName} </span>`;
    };

    const addRoadNameField = function (name, isBlocked, maxLength) {
      const nameToDisplay = _.isUndefined(name) || _.isNull(name) || name === 'null' || name === '' ? "" : name;
      const disabled = nameToDisplay !== "" && isBlocked;
      return `<input type="text" class="form-control administrativeClassAndRoadName" style="float:none; display:inline-block" id="roadName" value="${nameToDisplay}" ${disabled ? 'disabled' : ''} ${_.isUndefined(maxLength) ? '' : `maxlength="${maxLength}"`}/>`;
    };

    const projectButtons = function () {
      return `
      <button class="recalculate btn btn-block btn-recalculate">Päivitä etäisyyslukemat</button>
      <button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>
      <button id="send-button" class="send btn btn-block btn-send">Hyväksy tieosoitemuutokset</button>`;
    };

    const projectButtonsDisabled = function () {
      return `
      <button disabled id="recalculate-button" title="Kaikki linkit tulee olla käsiteltyjä" class="recalculate btn btn-block btn-recalculate">Päivitä etäisyyslukemat</button>
      <button disabled id="changes-button" title="Projektin tulee läpäistä validoinnit" class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>
      <button disabled id="send-button" title="Hyväksy yhteenvedon jälkeen" class="send btn btn-block btn-send">Hyväksy tieosoitemuutokset</button>`;
    };

    const setDisabledAndTitleAttributesById = function (id, disabled, titleText) {
      $(`#${id}`).attr('disabled', disabled);
      $(`#${id}`).attr('title', titleText);
    };

    var disableFormInteractions = function () {
      setDisabledAndTitleAttributesById("dropDown_0", true, 'Sulje yhteenvetotaulukko muokataksesi projektia');
      setDisabledAndTitleAttributesById("saveButton", true, 'Sulje yhteenvetotaulukko muokataksesi projektia');
      setDisabledAndTitleAttributesById("cancelButton", true, 'Sulje yhteenvetotaulukko muokataksesi projektia');
      setDisabledAndTitleAttributesById("changeDirectionButton", true, 'Sulje yhteenvetotaulukko muokataksesi projektia');
    };

    var enableFormInteractions = function () {
      setDisabledAndTitleAttributesById("dropDown_0", false, '');
      setDisabledAndTitleAttributesById("saveButton", false, '');
      setDisabledAndTitleAttributesById("cancelButton", false, '');
      setDisabledAndTitleAttributesById("changeDirectionButton", false, '');
    };

    const devAddressEditTool = function(links, project) {
      const devTool = new root.DevAddressTool(prefix, editableStatus);
      return devTool.render(links, project);
    };


    const newRoadAddressInfo = function (project, selected, links, road) {
      const roadNumber = road.roadNumber;
      const part = road.roadPartNumber;
      const track = road.trackCode;
      const roadName = selected[0].roadName;
      const link = _.head(_.filter(links, function (l) {
        return !_.isUndefined(l.status);
      }));
      const administrativeClass = (link.administrativeClassId) ? link.administrativeClassId : AdministrativeClass.Empty.value;
      const projectEditable = project.statusCode === editableStatus;
      let trackCodeDropdown;
      if (track === Track.Unknown.value) {
        trackCodeDropdown = (roadNumber >= 20000 && roadNumber <= 39999) ? '0' : '';
      } else {
        trackCodeDropdown = track;
      }
    return `
      <div class="${prefix}form-group new-road-address" hidden>
        <div><label style="margin-top: 20px; display: block;">TIEOSOITTEEN TIEDOT</label></div>
        <div class="road-address-fields-wrapper">
          <div class="road-address-fields">
            <!-- TIE -->
            <div class="road-address-field">
              ${addSmallLabel('TIE', 'margin: 0; width: 100%;')}
              ${addSmallInputNumber('tie', (roadNumber === 0 ? '' : roadNumber), !projectEditable, 5, "margin-right: 0")}
            </div>

            <!-- OSA -->
            <div class="road-address-field">
              ${addSmallLabel('OSA', 'margin: 0; width: 100%;')}
              ${addSmallInputNumber('osa', (part === 0 ? '' : part), !projectEditable, 3, "margin-right: 0; width:40px !important")}
            </div>

            <!-- AJR -->
            <div class="road-address-field">
              ${addSmallLabel('AJR', 'margin: 0; width: 100%;')}
              ${addTrackCodeDropdown(trackCodeDropdown)}
            </div>

            <!-- ELINVOIMAKESKUS -->
            <div class="road-address-field">
              ${addSmallLabel('ELINVOIMAKESKUS', 'margin: 0; width: 120px;')}
              ${addElinvoimakeskusDropdown(link.evkCode, !projectEditable)}
            </div>

            <!-- JATKUU -->
            <div class="road-address-field">
              ${addSmallLabel('JATKUU', 'margin: 0; width: 100%;')}
              ${addDiscontinuityDropdown('road-address-input')}
            </div>
          </div>
        </div>
        
        <!-- Additional fields -->
        <div class="road-address-section">
          ${addWideLabel('HALL. LUOKKA')}
          ${administrativeClassDropdown(administrativeClass)}
        </div>
        <div class="road-address-section">
          ${addWideLabel('NIMI')}
          ${addRoadNameField(roadName, selected[0].roadNameBlocked, 50)}
        </div>
        ${(selected.length === 2 && selected[0].linkId === selected[1].linkId) ? '' : distanceValue()}
      </div>`;
    };
    const replaceAddressInfo = function (backend, selectedProjectLink, currentProjectId) {
      const roadNameField = $('#roadName');
      if (selectedProjectLink[0].roadNumber === 0 && selectedProjectLink[0].roadPartNumber === 0 && selectedProjectLink[0].trackCode === 99) {
        backend.getPrefillValuesForLink(selectedProjectLink[0].linkId, currentProjectId, function (response) {
          if (response.success) {
            $('#tie').val(response.roadNumber);
            $('#osa').val(response.roadPartNumber);
            $('#ely').val(response.ely);
            $('#elinvoimakeskus').val(response.evk);
            if (response.roadName !== '') {
              roadNameField.val(response.roadName);
              roadNameField.prop('disabled', response.roadNameSource === RoadNameSource.RoadAddressSource.value);
              $('.project-form button.update').prop("disabled", false);
            }
            if (!_.isUndefined(response.roadNumber) && response.roadNumber >= 20000 && response.roadNumber <= 39999)
              $('#trackCodeDropdown').val("0");
          }
        });
      }
    };
    
    const administrativeClassLabel = function (administrativeClass) {
      const administrativeClassInfo = _.find(ViiteEnumerations.AdministrativeClass, function (obj) {
        return obj.value === administrativeClass;
      });
      return administrativeClassInfo.displayText;
    };

    const administrativeClassDropdown = function (administrativeClassDefaultValue) {
      return `<select class="${prefix}form-control administrativeClassAndRoadName" id="administrativeClassDropdown" size="1" style="width: 190px !important; display: inline">
        <option value="${administrativeClassDefaultValue}" selected hidden>${administrativeClassLabel(administrativeClassDefaultValue)}</option>
        <option value="1">1 Valtio</option>
        <option value="2">2 Kunta</option>
        <option value="3">3 Yksityinen</option>
      </select>`;
    };

    const addSmallLabel = function (label, customStyle) {
      return `<label class="control-label-small" style="${_.isUndefined(customStyle) ? '' : customStyle}">${label}</label>`;
    };

    const addWideLabel = function (label, customStyle) {
      return `<label class="control-label-wide" style="${_.isUndefined(customStyle) ? '' : customStyle}">${label}</label>`;
    };
    
    const addSmallLabelLowercase = function (label) {
      return `<label class="control-label-small" style="text-transform: none">${label}</label>`;
    };

    const addSmallInputNumber = function (id, value, isDisabled, maxCharacters, customStyle) {
      const inputComponent = new root.NumberInput({
        id: id,
        value: value,
        maxCharacters: maxCharacters,
        customStyle: customStyle,
        isDisabled: isDisabled
      });
      return inputComponent.render();
    };
    
    const nodeInputNumber = function (id, maxLength) {
      return `<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || event.keyCode === 9 || event.keyCode === 8" class="form-control node-input" id="${id}" ${_.isUndefined(maxLength) ? '' : `maxlength="${maxLength}"`}/>`;
    };
    
    const addDiscontinuityDropdown = function () {
      return `<select class="form-select-control" id="discontinuityDropdown" size="1" style="width: 104px !important">
        <option value="5" selected disabled hidden>5 Jatkuva</option>
        <option value="1">1 Tien loppu</option>
        <option value="2">2 Epäjatkuva</option>
        <option value="3">3 Elinvoimakeskuksen raja</option>
        <option value="4">4 Lievä epäjatkuvuus</option>
        <option value="5">5 Jatkuva</option>
      </select>`;
    };

    const addElinvoimakeskusDropdown = function (selectedValue) {
      const evkOptions = Object.entries(ViiteEnumerations.EVKCodes)
        .filter(([, value]) => value.value !== 0) // Exclude 0 from dropdown options
        .sort((a, b) => a[1].value - b[1].value) // Sort from smallest value to largest
        .map(([, value]) => {
          const selected = selectedValue === value.value ? 'selected' : '';
          return `<option value="${value.value}" ${selected}>${value.value} ${value.name}</option>`;
        })
        .join('');

      // If selectedValue is 0, show it as selected in the dropdown
      const defaultOption = selectedValue === 0 
        ? `<option value="0" selected>0 Tuntematon elinvoimakeskus</option>`
        : `<option value="" disabled ${!selectedValue ? 'selected' : ''} hidden>Valitse elinvoimakeskus</option>`;

      return `<select class="form-select-control" id="elinvoimakeskus" size="1" style="width: 120px !important;">
        ${defaultOption}
        ${evkOptions}
      </select>`;
    };

    const addTrackCodeDropdown = function (trackDefaultValue, properties) {
      const trackCodeDropdown = {
        value: trackDefaultValue,
        toShow: trackDefaultValue
      };
    
      if (trackDefaultValue === '') {
        trackCodeDropdown.value = Track.Unknown.value;
        trackCodeDropdown.toShow = '--';
      }

      return `<select class="form-select-small-control" id="trackCodeDropdown" size="1" style="${smallWidthStyle}; display: inline" ${properties}>
        <option value="${trackCodeDropdown.value}" selected hidden>${trackCodeDropdown.toShow}</option>
        <option value="0">0</option>
        <option value="1">1</option>
        <option value="2">2</option>
      </select>`;
    };

    const directionChangedInfo = function (selected, isPartialReversed) {
      if (selected[0].status === ViiteEnumerations.RoadAddressChangeType.New.value) return '';
      if (isPartialReversed) {
        return `<label class="form-group">Osittain käännetty</label>`;
      } else if (selected[0].reversed) {
        return `<label class="form-group"><span class="dingbats">&#9745;</span> Käännetty</label>`;
      } else {
        return `<label class="form-group"><span class="dingbats">&#9744;</span> Käännetty</label>`;
      }
    };
    
    const changeDirection = function (selected, project) {
      const projectEditable = project.statusCode === editableStatus;
      if (!projectEditable) {
        return ''; // Don't show the button if project status is not incomplete
      }
      const reversedInGroup = _.uniq(_.map(selected, 'reversed'));
      const isPartialReversed = reversedInGroup.length > 1;
      return `<div hidden class="${prefix}form-group changeDirectionDiv" style="margin-top:15px">
        <button id="changeDirectionButton" class="${prefix}form-group changeDirection btn btn-primary">Käännä tieosan kasvusuunta</button>
        ${directionChangedInfo(selected, isPartialReversed)}
      </div>`;
    };
    
    const selectedData = function (selected) {
      const span = [];
      if (selected[0]) {
        const link = selected[0];
        const startM = Math.min.apply(Math, _.map(selected, l => l.addrMRange.start));
        const endM = Math.max.apply(Math, _.map(selected, l => l.addrMRange.end));
        const div = `<div class="project-edit-selections" style="display:inline-block;padding-left:8px;">
          <div class="project-edit">
            TIE <span class="project-edit">${link.roadNumber}</span>
            OSA <span class="project-edit">${link.roadPartNumber}</span>
            AJR <span class="project-edit">${link.trackCode}</span>
            M: <span class="project-edit">${startM} - ${endM}</span>
            ${selected.length > 1 ? `(${selected.length} linkkiä)` : ''}
          </div>
        </div>`;
        span.push(div);
      }
      return span;
    };
    
    const actionButtons = function (btnPrefix, notDisabled) {
      return `<div class="${btnPrefix}form form-controls" id="actionButtons">
        <button id="saveButton" class="update btn btn-save" ${notDisabled ? '' : 'disabled'} style="width:auto;">Tallenna</button>
        <button id="cancelButton" class="cancelLink btn btn-cancel">Peruuta</button>
      </div>`;
    };
    
    const actionSelectedField = function () {
      return `<div class="${prefix}form-group action-selected-field" hidden="true">
        <div class="asset-log-info">Tarkista tekemäsi muutokset.<br>Jos muutokset ok, tallenna.</div>
      </div>`;
    };
    
    const toggleAdditionalControls = function () {
      $('#editProjectSpan').css('visibility', 'visible');
      $('#closeProjectSpan').css('visibility', 'visible');
    };
    
    const checkInputs = function (localPrefix) {
      const rootElement = $('#feature-attributes');
      const inputs = rootElement.find('input');
      let filled = true;
      for (let i = 0; i < inputs.length; i++) {
        if (inputs[i].type === 'text' && !inputs[i].value) {
          filled = false;
        }
      }
      if (filled) {
        rootElement.find(`${localPrefix}form button.update`).prop("disabled", false);
      } else {
        rootElement.find(`${localPrefix}form button.update`).prop("disabled", true);
      }
    };
    
    const clearInformationContent = function () {
      $('#information-content').empty();
    };
    
    const setInformationContent = function () {
      $('#information-content').html(`
        <div class="form form-horizontal">
          <p id="information-content-text" class="validation-text"></p>
        </div>
      `);
    };
    
    const setInformationContentText = function (text) {
      $('#information-content-text').html(text);
    };
    
    const sendRoadAddressChangeButton = function (localPrefix) {
      return `<div class="${localPrefix}form form-controls">
        <button id="recalculate-button" class="recalculate btn btn-block btn-recalculate">Päivitä etäisyyslukemat</button>
        <button id="changes-button" class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>
        <button id="send-button" class="send btn btn-block btn-send">Hyväksy tieosoitemuutokset</button>
      </div>`;
    };
    
    const distanceValue = function () {
      return `<div id="distanceValue" hidden>
        <div class="${prefix}form-group" style="margin-top: 15px">
          <img src="images/calibration-point.svg" style="margin-right: 5px" class="calibration-point"/>
          <label class="control-label-small" style="display: inline">ETÄISYYSLUKEMA VALINNAN</label>
        </div>
        <div class="${prefix}form-group">
          <label class="control-label-small" style="float: left; margin-top: 10px">ALUSSA</label>
          ${addSmallInputNumber('beginDistance', '--', false, 5)}
          <label class="control-label-small" style="float: left; margin-top: 10px">LOPUSSA</label>
          ${addSmallInputNumber('endDistance', '--', false, 5)}
          <span id="manualCPWarning" class="manualCPWarningSpan">!</span>
        </div>
      </div>`;
    };

    const staticField = function (labelText, dataField) {
      return `<div class="${prefix}form-group">
        <p class="form-control-static asset-log-info">${labelText} : ${dataField}</p>
      </div>`;
    };
    
    const getCoordButton = function (index, coordinates) {
      return coordButton(index, coordinates);
    };
    
    const coordButton = function (index, coordinates) {
      const html = `<button id=${index} class="btn btn-primary projectErrorButton">Korjaa</button>`;
      return {index: index, html: html, coordinates: coordinates};
    };
    
    const getErrorCoordinates = function (error, links) {
      if (error.coordinates.length > 0) {
        return error.coordinates;
      }
      const linkCoords = _.find(links, link => link.linkId === error.linkIds[0]);
      if (!_.isUndefined(linkCoords)) {
        return linkCoords.points[0];
      }
      return false;
    };

    const getProjectErrors = function (projectErrors, links, projectCollection) {
      let buttonIndex = 0;
      let errorIndex = 0;
      let errorLines = '';
      projectCollection.clearCoordinates();
      projectErrors.sort(function (a, b) {
        return a.priority - b.priority; // Sort by priority ascending
      });
      _.each(projectErrors, function (error) {
        let button = '';
        const coordinates = getErrorCoordinates(error, links);
        if (coordinates) {
          button = getCoordButton(buttonIndex, error.coordinates);
          projectCollection.pushCoordinates(button);
          buttonIndex++;
        }
        errorLines += `<div class="form-project-errors-list${error.priority === 1 ? ' warning' : ''}">${
          addSmallLabel('VIRHE: ')} ${addSmallLabelLowercase((error.errorMessage ? error.errorMessage : 'N/A'))}</br>${
          addSmallLabel('INFO: ')} ${addSmallLabelLowercase((error.info ? error.info : 'N/A'))}</br>${
          button.html ? button.html : ''}${
          addLinkIdListButton(errorIndex, error.linkIds)}${
          '</br> <hr class="horizontal-line"/>'}${
        '</div>'}`;
        errorIndex++;
      });

      return errorLines;
    };

    const addLinkIdListButton = function (errorIndex, linkIds) {
      if (linkIds.length > 0)
        return '<button id= "' + errorIndex + '" class="btn btn-primary linkIdList">Linkkien id:t</button>';
      else
        return '';
    };

    return {
      devAddressEditTool: devAddressEditTool,
      newRoadAddressInfo: newRoadAddressInfo,
      replaceAddressInfo: replaceAddressInfo,
      administrativeClass: administrativeClassDropdown,
      addSmallLabel: addSmallLabel,
      addWideLabel: addWideLabel,
      addSmallInputNumber: addSmallInputNumber,
      nodeInputNumber: nodeInputNumber,
      addDiscontinuityDropdown: addDiscontinuityDropdown,
      changeDirection: changeDirection,
      selectedData: selectedData,
      actionButtons: actionButtons,
      actionSelectedField: actionSelectedField,
      toggleAdditionalControls: toggleAdditionalControls,
      checkInputs: checkInputs,
      clearInformationContent: clearInformationContent,
      setInformationContent: setInformationContent,
      setInformationContentText: setInformationContentText,
      sendRoadAddressChangeButton: sendRoadAddressChangeButton,
      distanceValue: distanceValue,
      title: title,
      titleWithEditingTool: titleWithEditingTool,
      captionTitle: captionTitle,
      projectButtons: projectButtons,
      projectButtonsDisabled: projectButtonsDisabled,
      staticField: staticField,
      getProjectErrors: getProjectErrors,
      setDisabledAndTitleAttributesById: setDisabledAndTitleAttributesById,
      disableFormInteractions: disableFormInteractions,
      enableFormInteractions: enableFormInteractions
    };
  };
}(this));
