(function (root) {
  root.FormCommon = function (prefix) {
    const Track = LinkValues.Track;
    const RoadNameSource = LinkValues.RoadNameSource;
    const editableStatus = LinkValues.ProjectStatus.Incomplete.value;
    const AdministrativeClass = LinkValues.AdministrativeClass;

    const title = function (titleName) {
      const fixedTitle = titleName || "Uusi tieosoiteprojekti";
      return `<span class ="edit-mode-title"> ${fixedTitle} </span>`;
    };

    const titleWithEditingTool = function (project) {
      return '<span class ="edit-mode-title">' + project.name + ' <i id="editProjectSpan" class=' +
        '"btn-pencil-edit fas fa-pencil-alt" value="' + project.id + '"></i></span>' +
        '<span id="closeProjectSpan" class="rightSideSpan">Sulje <i class="fas fa-window-close"></i></span>';
    };

    const captionTitle = function (titleName) {
      return `<span class="caption-title"> ${titleName} </span>`;
    };

    const addRoadNameField = function (name, isBlocked, maxLength) {
      const nameToDisplay = _.isUndefined(name) || _.isNull(name) || name === 'null' || name === '' ? "" : name;
      const disabled = nameToDisplay !== "" && isBlocked;
      return '<input type="text" class="form-control administrativeClassAndRoadName" style="float:none; display:inline-block" id = "roadName" value="' + nameToDisplay + '" ' + (disabled ? 'disabled' : '') + (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + '/>';
    };

    const projectButtons = function () {
      return '<button class="recalculate btn btn-block btn-recalculate">Päivitä etäisyyslukemat</button>' +
        '<button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
        '<button id ="send-button" class="send btn btn-block btn-send">Hyväksy tieosoitemuutokset</button>';
    };

    const projectButtonsDisabled = function () {
      return  '<button disabled id="recalculate-button" title="Kaikki linkit tulee olla käsiteltyjä" class="recalculate btn btn-block btn-recalculate">Päivitä etäisyyslukemat</button>' +
          '<button disabled id="changes-button" title="Projektin tulee läpäistä validoinnit" class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
          '<button disabled id="send-button" title="Hyväksy yhteenvedon jälkeen" id="send-button" class="send btn btn-block btn-send">Hyväksy tieosoitemuutokset</button>';
    };

    // set elements title and disabled attributes with the elements id
    const setDisabledAndTitleAttributesById = function (id, disabled, titleText) {
      $(`#${id}`).attr('disabled', disabled);
      $(`#${id}`).attr('title', titleText);
    };

    // Disable form interactions (action dropdown, save and cancel buttons) and set titles
    var disableFormInteractions = function () {
      setDisabledAndTitleAttributesById("dropDown_0", true, 'Sulje yhteenvetotaulukko muokataksesi projektia');
      setDisabledAndTitleAttributesById("saveButton", true, 'Sulje yhteenvetotaulukko muokataksesi projektia');
      setDisabledAndTitleAttributesById("cancelButton", true, 'Sulje yhteenvetotaulukko muokataksesi projektia');
      setDisabledAndTitleAttributesById("changeDirectionButton", true, 'Sulje yhteenvetotaulukko muokataksesi projektia');
    };

    // Enable form interactions (action dropdown, save and cancel buttons) and set titles to empty string
    var enableFormInteractions = function () {
      setDisabledAndTitleAttributesById("dropDown_0", false, '');
      setDisabledAndTitleAttributesById("saveButton", false, '');
      setDisabledAndTitleAttributesById("cancelButton", false, '');
      setDisabledAndTitleAttributesById("changeDirectionButton", false, '');
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
        trackCodeDropdown = (roadNumber >= 20001 && roadNumber <= 39999) ? '0' : '';
      } else {
        trackCodeDropdown = track;
      }
      return '<div class="' + prefix + 'form-group new-road-address" hidden>' +
        '<div><label></label></div><div><label style = "margin-top: 50px">TIEOSOITTEEN TIEDOT</label></div>' +
        addSmallLabel('TIE') + addSmallLabel('OSA') + addSmallLabel('AJR') + addSmallLabel('ELY') +
        addSmallLabel('JATKUU') +
        '</div>' +
        '<div class="' + prefix + 'form-group new-road-address" id="new-address-input1" hidden>' +
        addSmallInputNumber('tie', (roadNumber === 0 ? '' : roadNumber), !projectEditable, 5) +
        addSmallInputNumber('osa', (part === 0 ? '' : part), !projectEditable, 3) +
        addTrackCodeDropdown(trackCodeDropdown) +
        addSmallInputNumber('ely', link.elyCode, !projectEditable, 2) +
        addDiscontinuityDropdown() +
        addWideLabel('HALL. LUOKKA') +
        administrativeClassDropdown(administrativeClass) + '<br>' +
        addWideLabel('NIMI') +
        addRoadNameField(roadName, selected[0].roadNameBlocked, 50) +
        ((selected.length === 2 && selected[0].linkId === selected[1].linkId) ? '' : distanceValue()) +
        '</div>';
    };

    const replaceAddressInfo = function (backend, selectedProjectLink, currentProjectId) {
      const roadNameField = $('#roadName');
      if (selectedProjectLink[0].roadNumber === 0 && selectedProjectLink[0].roadPartNumber === 0 && selectedProjectLink[0].trackCode === 99) {
        backend.getNonOverridenVVHValuesForLink(selectedProjectLink[0].linkId, currentProjectId, function (response) {
          if (response.success) {
            $('#tie').val(response.roadNumber);
            $('#osa').val(response.roadPartNumber);
            if (response.roadName !== '') {
              roadNameField.val(response.roadName);
              roadNameField.prop('disabled', response.roadNameSource === RoadNameSource.RoadAddressSource.value);
              $('.project-form button.update').prop("disabled", false);
            }
            if (!_.isUndefined(response.roadNumber) && response.roadNumber >= 20001 && response.roadNumber <= 39999)
              $('#trackCodeDropdown').val("0");
          }
        });
      }
    };

    const administrativeClassLabel = function (administrativeClass) {
      const administrativeClassInfo = _.find(LinkValues.AdministrativeClass, function (obj) {
        return obj.value === administrativeClass;
      });
      return administrativeClassInfo.displayText;
    };
    const administrativeClassDropdown = function (administrativeClassDefaultValue) {
      return '<select class="' + prefix + 'form-control administrativeClassAndRoadName" id="administrativeClassDropdown" size = "1" style="width: 190px !important; display: inline">' +
        '<option value = "' + administrativeClassDefaultValue + '" selected hidden >' + administrativeClassLabel(administrativeClassDefaultValue) + '</option>' +
        '<option value = "1">1 Valtio</option>' +
        '<option value = "2">2 Kunta</option>' +
        '<option value = "3">3 Yksityinen</option>' +

        '</select>';
    };

    const addSmallLabel = function (label) {
      return '<label class="control-label-small">' + label + '</label>';
    };

    const addWideLabel = function (label) {
      return '<label class="control-label-wide">' + label + '</label>';
    };

    const addSmallLabelLowercase = function (label) {
      return '<label class="control-label-small" style="text-transform: none">' + label + '</label>';
    };


    const addSmallLabelTopped = function (label) {
      return '<label class="control-label-small" style="vertical-align: top;">' + label + '</label>';
    };

    const addSmallLabelWrapped = function (label) {
      return '<label class="control-label-small" style="word-wrap: break-word;max-width: 250px">' + label + '</label>';
    };

    const addSmallInputNumber = function (id, value, isDisabled, maxLength) {
      //Validate only number characters on "onkeypress" including TAB and backspace
      const disabled = isDisabled ? ' readonly="readonly" ' : '';
      return '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
        '" class="' + prefix + 'form-control small-input roadAddressProject" id="' + id + '" value="' + (_.isUndefined(value) ? '' : value) + '" ' +
        disabled + (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + ' onclick=""/>';
    };

    const nodeInputNumber = function (id, maxLength) {
      return '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode === 8 || event.keyCode === 9)' +
        '" class="form-control node-input" id = "' + id + '"' +
        (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '" ') + '/>';
    };

    const addDiscontinuityDropdown = function () {
      return '<select class="form-select-control" id="discontinuityDropdown" size="1">' +
        '<option value = "5" selected disabled hidden>5 Jatkuva</option>' +
        '<option value="1" >1 Tien loppu</option>' +
        '<option value="2" >2 Epäjatkuva</option>' +
        '<option value="3" >3 ELY:n raja</option>' +
        '<option value="4" >4 Lievä epäjatkuvuus</option>' +
        '<option value="5" >5 Jatkuva</option>' +
        '</select>';
    };

    const addTrackCodeDropdown = function (trackDefaultValue, properties) {
      const trackCodeDropdown = {
        value:  trackDefaultValue,
        toShow: trackDefaultValue
      };

      if (trackDefaultValue === '') {
        trackCodeDropdown.value = Track.Unknown.value;
        trackCodeDropdown.toShow = '--';
      }

      return `<select class="form-select-small-control" id="trackCodeDropdown" size="1" ${properties}>` +
        `<option value="${trackCodeDropdown.value}" selected hidden> ${trackCodeDropdown.toShow} </option>` +
        `<option value="0" >0</option>` +
        `<option value="1" >1</option>` +
        `<option value="2" >2</option>` +
        `</select>`;
    };

    const directionChangedInfo = function (selected, isPartialReversed) {
      if (selected[0].status === LinkValues.LinkStatus.New.value) return '';
      if (isPartialReversed) {
        return '<label class="split-form-group">Osittain käännetty</label>';
      } else if (selected[0].reversed) {
        return '<label class="split-form-group"><span class="dingbats">&#9745;</span> Käännetty</label>';
      } else {
        return '<label class="split-form-group"><span class="dingbats">&#9744;</span> Käännetty</label>';
      }
    };

    const changeDirection = function (selected, project) {
      const projectEditable = project.statusCode === editableStatus;
      if (!projectEditable) {
        return ''; // Don't show the button if project status is not incomplete
      }
      const reversedInGroup = _.uniq(_.map(selected, 'reversed'));
      const isPartialReversed = reversedInGroup.length > 1;
      return '<div hidden class="' + prefix + 'form-group changeDirectionDiv" style="margin-top:15px">' +
        '<button id="changeDirectionButton" class="' + prefix + 'form-group changeDirection btn btn-primary">Käännä tieosan kasvusuunta</button>' +
        directionChangedInfo(selected, isPartialReversed) +
        '</div>';
    };

    const selectedData = function (selected) {
      const span = [];
      if (selected[0]) {
        const link = selected[0];
        const startM = Math.min.apply(Math, _.map(selected, function (l) {
          return l.startAddressM;
        }));
        const endM = Math.max.apply(Math, _.map(selected, function (l) {
          return l.endAddressM;
        }));
        const div = '<div class="project-edit-selections" style="display:inline-block;padding-left:8px;">' +
          '<div class="project-edit">' +
          ' TIE <span class="project-edit">' + link.roadNumber + '</span>' +
          ' OSA <span class="project-edit">' + link.roadPartNumber + '</span>' +
          ' AJR <span class="project-edit">' + link.trackCode + '</span>' +
          ' M:  <span class="project-edit">' + startM + ' - ' + endM + '</span>' +
          (selected.length > 1 ? ' (' + selected.length + ' linkkiä)' : '') +
          '</div>' +
          '</div>';
        span.push(div);
      }
      return span;
    };

    const actionButtons = function (btnPrefix, notDisabled) {
      return '<div class="' + btnPrefix + 'form form-controls" id="actionButtons">' +
        '<button id="saveButton" class="update btn btn-save" ' + (notDisabled ? '' : 'disabled') + ' style="width:auto;">Tallenna</button>' +
        '<button id="cancelButton" class="cancelLink btn btn-cancel">Peruuta</button>' +
        '</div>';
    };

    const actionSelectedField = function () {
      return '<div class="' + prefix + 'form-group action-selected-field" hidden = "true">' +
        '<div class="asset-log-info">Tarkista tekemäsi muutokset.<br>Jos muutokset ok, tallenna.</div>' +
        '</div>';
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
        rootElement.find(localPrefix + 'form button.update').prop("disabled", false);
      } else {
        rootElement.find(localPrefix + 'form button.update').prop("disabled", true);
      }
    };

    const clearInformationContent = function () {
      $('#information-content').empty();
    };

    const setInformationContent = function () {
      $('#information-content').html('' +
        '<div class="form form-horizontal">' +
          '<p id="information-content-text" class="validation-text"></p>' +
        '</div>');
    };

    const setInformationContentText = function (text) {
      $('#information-content-text').html(text);
    };

    const sendRoadAddressChangeButton = function (localPrefix) {
      return '<div class="' + localPrefix + 'form form-controls">' +
          '<button id="recalculate-button" class="recalculate btn btn-block btn-recalculate">Päivitä etäisyyslukemat</button>' +
        '<button id="changes-button" class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
        '<button id ="send-button" class="send btn btn-block btn-send">Hyväksy tieosoitemuutokset</button></div>';
    };

    const distanceValue = function () {
      return '<div id="distanceValue" hidden>' +
        '<div class="' + prefix + 'form-group" style="margin-top: 15px">' +
        '<img src="images/calibration-point.svg" style="margin-right: 5px" class="calibration-point"/>' +
        '<label class="control-label-small" style="display: inline">ETÄISYYSLUKEMA VALINNAN</label>' +
        '</div>' +
        '<div class="' + prefix + 'form-group">' +
        '<label class="control-label-small" style="float: left; margin-top: 10px">ALUSSA</label>' +
        addSmallInputNumber('beginDistance', '--', false, 5) +
        '<label class="control-label-small" style="float: left;margin-top: 10px">LOPUSSA</label>' +
        addSmallInputNumber('endDistance', '--', false, 5) +
        '<span id="manualCPWarning" class="manualCPWarningSpan">!</span>' +
        '</div></div>';
    };

    const staticField = function (labelText, dataField) {
      return '<div class="' + prefix + 'form-group">' +
        '<p class="form-control-static asset-log-info">' + labelText + ' : ' + dataField + '</p>' +
        '</div>';
    };

    const getCoordButton = function (index, coordinates) {
      return coordButton(index, coordinates);
    };

    const coordButton = function (index, coordinates) {
      const html = '<button id=' + index + ' class="btn btn-primary projectErrorButton">Korjaa</button>';
      return {index: index, html: html, coordinates: coordinates};
    };

    const getErrorCoordinates = function (error, links) {
      if (error.coordinates.length > 0) {
        return error.coordinates;
      }
      const linkCoords = _.find(links, function (link) {
        return link.linkId === error.linkIds[0];
      });
      if (!_.isUndefined(linkCoords)) {
        return linkCoords.points[0];
      }
      return false;
    };

    const getProjectErrors = function (projectErrors, links, projectCollection) {
      let buttonIndex = 0;
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
        errorLines += '<div class="form-project-errors-list' + (error.priority === 1 ? ' warning' : '') + '">' +
          addSmallLabelTopped('LINKIDS: ') + ' ' + addSmallLabelWrapped(error.linkIds) + '</br>' +
          addSmallLabel('VIRHE: ') + ' ' + addSmallLabelLowercase((error.errorMessage ? error.errorMessage : 'N/A')) + '</br>' +
          addSmallLabel('INFO: ') + ' ' + addSmallLabelLowercase((error.info ? error.info : 'N/A')) + '</br>' +
          (button.html ? button.html : '') + '</br> <hr class="horizontal-line"/>' +
          '</div>';
      });
      return errorLines;
    };

    return {
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
