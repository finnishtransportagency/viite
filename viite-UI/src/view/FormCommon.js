(function (root) {
  root.FormCommon = function(prefix) {
    var Track = LinkValues.Track;
    var RoadNameSource = LinkValues.RoadNameSource;
    var editableStatus = LinkValues.ProjectStatus.Incomplete.value;
    var RoadType = LinkValues.RoadType;

    var title = function (titleName) {
          if (!titleName)
              titleName = "Uusi tieosoiteprojekti";
          return '<span class ="edit-mode-title">' + titleName + '</span>';
    };

      var titleWithEditingTool = function (project) {
          return '<span class ="edit-mode-title">' + project.name + ' <i id="editProjectSpan" class="btn-edit-project fas fa-pencil-alt"' +
              'value="' + project.id + '"></i></span>' +
              '<span id="closeProjectSpan" class="rightSideSpan">Sulje <i class="fas fa-window-close"></i></span>';
    };

    var captionTitle = function (title) {
      return '<span class="caption-title">' + title + '</span>';
    };

    var addRoadNameField = function (name, isBlocked) {
      var nameToDisplay = _.isUndefined(name) || _.isNull(name) || name === 'null' || name === '' ? "" : name;
      var disabled = nameToDisplay !== "" && isBlocked;
      return '<input type="text" class="form-control" style="float:none; display:inline-block" id = "roadName" value="' + nameToDisplay + '" ' + (disabled ? 'disabled' : '') + '/>';
    };

    var projectButtons = function() {
      return '<button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
      '<button disabled id ="send-button" class="send btn btn-block btn-send">Lähetä muutosilmoitus Tierekisteriin</button>';
    };

    var newRoadAddressInfo = function(project, selected, links, road) {
      var roadNumber = road.roadNumber;
      var part = road.roadPartNumber;
      var track = road.trackCode;
      var roadName = selected[0].roadName;
      var link = _.head(_.filter(links, function (l) {
        return !_.isUndefined(l.status);
      }));
      var roadType = !_.isUndefined(link.roadTypeId) ? link.roadTypeId : '';
      var projectEditable = project.statusCode === editableStatus;
      return '<div class="'+prefix+'form-group new-road-address" hidden>' +
        '<div><label></label></div><div><label style = "margin-top: 50px">TIEOSOITTEEN TIEDOT</label></div>' +
        addSmallLabel('TIE') + addSmallLabel('OSA') + addSmallLabel('AJR')+ addSmallLabel('ELY')  +
        addSmallLabel('JATKUU') +
        '</div>' +
        '<div class="'+prefix+'form-group new-road-address" id="new-address-input1" hidden>'+
        addSmallInputNumber('tie', (roadNumber !== 0 ? roadNumber : ''), !projectEditable, 5) +
        addSmallInputNumber('osa', (part !== 0 ? part : ''), !projectEditable, 3) +
        addTrackCodeDropdown((track !== Track.Unknown.value ? track :
          (roadNumber >= 20001 && roadNumber <= 39999 ? '0' : ''))) +
        addSmallInputNumber('ely', link.elyCode, !projectEditable, 2) +
        addDiscontinuityDropdown(link) +
        addSmallLabel('TIETYYPPI') +
          roadTypeDropdown(roadType) + '<br>' +
          addSmallLabel('NIMI') +
          addRoadNameField(roadName, selected[0].roadNameBlocked) +
          ((selected.length === 2 && selected[0].linkId === selected[1].linkId) ? '' : distanceValue()) +
        '</div>';
    };

      var replaceAddressInfo = function(backend, selectedProjectLink, currentProjectId) {
          var roadNameField = $('#roadName');
          if (selectedProjectLink[0].roadNumber === 0 && selectedProjectLink[0].roadPartNumber === 0 && selectedProjectLink[0].trackCode === 99) {
              backend.getNonOverridenVVHValuesForLink(selectedProjectLink[0].linkId, currentProjectId, function (response) {
                  if (response.success) {
                      $('#tie').val(response.roadNumber);
                      $('#osa').val(response.roadPartNumber);
                      if(response.roadName !== ''){
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

    var roadTypeLabel = function(roadType){
      var roadTypeInfo = _.find(LinkValues.RoadTypeDisplayValues, function (obj) {
        return obj.value === roadType;
      });
      return roadTypeInfo;
    };
    var roadTypeDropdown = function(roadTypeDefaultValue) {
      var roadTypeDefaultValueToShow = '';
      if (roadTypeDefaultValue === '') {
        roadTypeDefaultValue = RoadType.Empty.value;
        roadTypeDefaultValueToShow = '--';
      } else {
        var roadTypeText = roadTypeLabel(roadTypeDefaultValue);
        roadTypeDefaultValueToShow = roadTypeText.description;

      }

      return '<select class="'+prefix+'form-control" id="roadTypeDropdown" size = "1" style="width: auto !important; display: inline">' +
        '<option value = "' + roadTypeDefaultValue+ '" selected hidden >' +roadTypeDefaultValueToShow+'</option>' +
        '<option value = "1">1 Maantie</option>'+
        '<option value = "2">2 Lauttaväylä maantiellä</option>'+
        '<option value = "3">3 Kunnan katuosuus</option>'+
        '<option value = "4">4 Maantien työmaa</option>'+
        '<option value = "5">5 Yksityistie</option>'+
        '<option value = "9">9 Omistaja selvittämättä</option>' +

        '</select>';
    };

    var addSmallLabel = function(label){
      return '<label class="control-label-small">'+label+'</label>';
    };

    var addSmallLabelLowercase = function(label){
      return '<label class="control-label-small" style="text-transform: none">'+label+'</label>';
    };


    var addSmallLabelTopped = function(label){
      return '<label class="control-label-small" style="vertical-align: top;">'+label+'</label>';
    };

    var addSmallLabelWrapped = function(label){
      return '<label class="control-label-small" style="word-wrap: break-word;max-width: 250px">'+label+'</label>';
    };

    var addSmallInputNumber = function(id, value, isDisabled, maxLength) {
      //Validate only number characters on "onkeypress" including TAB and backspace
      var disabled = isDisabled ? ' readonly="readonly" ': '';
      return '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
        '" class="' + prefix + 'form-control small-input roadAddressProject" id="' + id + '" value="' + (_.isUndefined(value)? '' : value ) + '" ' +
        disabled + (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + ' onclick=""/>';
    };

    var addSmallInputNumberDisabled = function(id, value) {
      return '<input type="text" class="form-control small-input roadAddressProject" id="' + id + '" value="' + (_.isUndefined(value)? '' : value ) + '" readonly="readonly"/>';
    };

    var addDiscontinuityDropdown = function(link){
      return '<select class="form-select-control" id="discontinuityDropdown" size="1">' +
        '<option value = "5" selected disabled hidden>5 Jatkuva</option>' +
        '<option value="1" >1 Tien loppu</option>' +
        '<option value="2" >2 Epäjatkuva</option>' +
        '<option value="3" >3 ELY:n raja</option>' +
        '<option value="4" >4 Lievä epäjatkuvuus</option>' +
        '<option value="5" >5 Jatkuva</option>' +
        '<option value="6" >5 Jatkuva (Rinnakkainen linkki)</option>' +
        '</select>';
    };

    var addTrackCodeDropdown = function (trackDefaultValue, properties){
      var trackDefaultValueToShow = '';
        if (trackDefaultValue === '') {
        trackDefaultValue = Track.Unknown.value;
        trackDefaultValueToShow = '--';
        } else {
        trackDefaultValueToShow = trackDefaultValue;
      }

      return '<select class="form-select-small-control" id="trackCodeDropdown" size="1" '+properties+'>' +
        '<option value = "'+trackDefaultValue+'" selected hidden>'+trackDefaultValueToShow+'</option>' +
        '<option value="0" >0</option>' +
        '<option value="1" >1</option>' +
        '<option value="2" >2</option>' +
        '</select>';

    };

    var directionChangedInfo = function (selected, isPartialReversed) {
      if (selected[0].status === LinkValues.LinkStatus.New.value) return '';
      if (isPartialReversed) {
        return '<label class="split-form-group">Osittain käännetty</label>';
      } else if (selected[0].reversed) {
        return '<label class="split-form-group">&#9745; Käännetty</label>';
      } else {
        return '<label class="split-form-group">&#9744; Käännetty</label>';
      }
    };

    var changeDirection = function (selected, project) {
      var projectEditable = project.statusCode === editableStatus;
      if (!projectEditable) {
        return ''; // Don't show the button if project status is not incomplete
      }
      var reversedInGroup = _.uniq(_.map(selected, 'reversed'));
      var isPartialReversed = reversedInGroup.length > 1;
      return '<div hidden class="' + prefix + 'form-group changeDirectionDiv" style="margin-top:15px">' +
        '<button class="' + prefix + 'form-group changeDirection btn btn-primary">Käännä tieosan kasvusuunta</button>' +
        directionChangedInfo(selected, isPartialReversed) +
        '</div>';
    };

    var selectedData = function (selected) {
      var span = [];
      if (selected[0]) {
        var link = selected[0];
        var startM = Math.min.apply(Math, _.map(selected, function(l) { return l.startAddressM; }));
        var endM = Math.max.apply(Math, _.map(selected, function(l) { return l.endAddressM; }));
        var div = '<div class="project-edit-selections" style="display:inline-block;padding-left:8px;">' +
          '<div class="project-edit">' +
          ' TIE ' + '<span class="project-edit">' + link.roadNumber + '</span>' +
          ' OSA ' + '<span class="project-edit">' + link.roadPartNumber + '</span>' +
          ' AJR ' + '<span class="project-edit">' + link.trackCode + '</span>' +
          ' M:  ' + '<span class="project-edit">' + startM + ' - ' + endM + '</span>' +
          (selected.length > 1 ? ' (' + selected.length + ' linkkiä)' : '')+
          '</div>' +
          '</div>';
        span.push(div);
      }
      return span;
    };

    var actionButtons = function(btnPrefix, notDisabled) {
      return '<div class="'+btnPrefix+'form form-controls" id="actionButtons">' +
        '<button class="update btn btn-save" ' + (notDisabled ? '' : 'disabled') + ' style="width:auto;">Tallenna</button>' +
        '<button class="cancelLink btn btn-cancel">Peruuta</button>' +
        '</div>';
    };

    var actionSelectedField = function() {
      var field;
      field = '<div class="'+prefix+'form-group action-selected-field" hidden = "true">' +
        '<div class="asset-log-info">' + 'Tarkista tekemäsi muutokset.' + '<br>' + 'Jos muutokset ok, tallenna.' + '</div>' +
        '</div>';
      return field;
    };

    var toggleAdditionalControls = function(){
        $('#editProjectSpan').css('visibility', 'visible');
      $('#closeProjectSpan').css('visibility', 'visible');
    };

    var hideEditAndCloseControls = function(){
        $('#editProjectSpan').css('visibility', 'hidden');
      $('#closeProjectSpan').css('visibility', 'hidden');
    };

    var checkInputs = function (localPrefix) {
      var rootElement = $('#feature-attributes');
      var inputs = rootElement.find('input');
      var filled = true;
      for (var i = 0; i < inputs.length; i++) {
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

    var clearInformationContent = function() {
      $('#information-content').empty();
    };

    var setInformationContent = function() {
      $('#information-content').html('' +
        '<div class="form form-horizontal">' +
        '<p>' + 'Validointi ok. Voit tehdä tieosoitteenmuutosilmoituksen' + '<br>' +
        'tai jatkaa muokkauksia.' + '</p>' +
        '</div>');
    };

    var sendRoadAddressChangeButton = function (localPrefix) {
      return '<div class="' + localPrefix + 'form form-controls">' +
        '<button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
        '<button id ="send-button" class="send btn btn-block btn-send"' + '>Lähetä muutosilmoitus Tierekisteriin</button></div>';
    };

    var distanceValue = function() {
      return '<div id="distanceValue" hidden>' +
        '<div class="'+prefix+'form-group" style="margin-top: 15px">' +
        '<img src="images/calibration-point.svg" style="margin-right: 5px" class="calibration-point"/>' +
        '<label class="control-label-small" style="display: inline">ETÄISYYSLUKEMA VALINNAN</label>' +
        '</div>' +
        '<div class="' + prefix + 'form-group">' +
        '<label class="control-label-small" style="float: left; margin-top: 10px">ALUSSA</label>' +
        addSmallInputNumber('beginDistance', '--', true, 5) +
        '<label class="control-label-small" style="float: left;margin-top: 10px">LOPUSSA</label>' +
        addSmallInputNumber('endDistance', '--', true, 5) +
        '<span id="manualCPWarning" class="manualCPWarningSpan">!</span>' +
        '</div></div>';
    };

    var staticField = function(labelText, dataField) {
      var field;
      field = '<div class="'+prefix+'form-group">' +
        '<p class="form-control-static asset-log-info">' + labelText + ' : ' + dataField + '</p>' +
        '</div>';
      return field;
    };

    var getCoordButton = function (index, coordinates) {
      return coordButton(index, coordinates);
    };

    var coordButton = function(index, coordinates){
        var html = '<button id=' + index + ' class="btn btn-primary projectErrorButton">Korjaa</button>';
      return {index:index, html:html, coordinates:coordinates};
    };

    var getErrorCoordinates = function(error, links){
      if (error.coordinates.length  > 0){
        return error.coordinates;
      }
      var linkCoords = _.find(links, function (link) {
        return link.linkId == error.linkIds[0];
      });
      if (!_.isUndefined(linkCoords)){
        return linkCoords.points[0];
      }
      return false;
    };

    var getProjectErrors = function (projectErrors, links, projectCollection) {
      var buttonIndex = 0;
      var errorLines = '';
      projectCollection.clearCoordinates();
      projectErrors.sort(function(a, b) {
        return a.priority - b.priority; // Sort by priority ascending
      });
      _.each(projectErrors, function (error) {
        var button = '';
        var coordinates = getErrorCoordinates(error, links);
        if (coordinates) {
          button = getCoordButton(buttonIndex, error.coordinates);
          projectCollection.pushCoordinates(button);
          buttonIndex++;
        }
        errorLines += '<div class="form-project-errors-list' + (error.priority === 1 ? ' warning' : '') + '">' +
          addSmallLabelTopped('LINKIDS: ') + ' ' + addSmallLabelWrapped(error.linkIds) + '</br>' +
          addSmallLabel('VIRHE: ') + ' ' + addSmallLabelLowercase((error.errorMessage ? error.errorMessage: 'N/A')) + '</br>' +
          addSmallLabel('INFO: ') + ' ' + addSmallLabelLowercase((error.info ? error.info: 'N/A')) + '</br>' +
          (button.html ? button.html : '') + '</br>' + ' ' + '<hr class="horizontal-line"/>' +
          '</div>';
      });
      return errorLines;
    };

    return {
      newRoadAddressInfo: newRoadAddressInfo,
      replaceAddressInfo: replaceAddressInfo,
      roadTypeDropdown: roadTypeDropdown,
      addSmallLabel: addSmallLabel,
      addSmallInputNumber: addSmallInputNumber,
      addSmallInputNumberDisabled: addSmallInputNumberDisabled,
      addDiscontinuityDropdown: addDiscontinuityDropdown,
      changeDirection: changeDirection,
      selectedData: selectedData,
      actionButtons: actionButtons,
      actionSelectedField: actionSelectedField,
      toggleAdditionalControls: toggleAdditionalControls,
      checkInputs: checkInputs,
      clearInformationContent: clearInformationContent,
      setInformationContent: setInformationContent,
      sendRoadAddressChangeButton: sendRoadAddressChangeButton,
      distanceValue: distanceValue,
      title: title,
      titleWithEditingTool: titleWithEditingTool,
      captionTitle: captionTitle,
      projectButtons: projectButtons,
      staticField: staticField,
      getProjectErrors:getProjectErrors
    };
  };
})(this);
