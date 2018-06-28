(function (root) {
  root.ProjectEditForm = function(map, projectCollection, selectedProjectLinkProperty, projectLinkLayer, projectChangeTable, backend) {
    var LinkStatus = LinkValues.LinkStatus;
    var CalibrationCode = LinkValues.CalibrationCode;
    var editableStatus = [LinkValues.ProjectStatus.Incomplete.value, LinkValues.ProjectStatus.ErroredInTR.value, LinkValues.ProjectStatus.Unknown.value];
    var selectedProjectLink = false;
    var formCommon = new FormCommon('');

    var endDistanceOriginalValue = '--';

    var showProjectChangeButton = function() {
      return '<div class="project-form form-controls">' +
        formCommon.projectButtons() + '</div>';
    };

    var transitionModifiers = function(targetStatus, currentStatus) {
      var mod;
      if (_.contains(targetStatus.transitionFrom, currentStatus))
        mod = '';
      else
        mod = 'disabled hidden';
      if (currentStatus == targetStatus.value)
        return mod + ' selected';
      else
        return mod;
    };

    var defineOptionModifiers = function(option, selection) {
      var isSplitMode = selection.length === 2 && selection[0].linkId === selection[1].linkId && applicationModel.getSelectedTool() === 'Cut';
      var linkStatus = selection[0].status;
      var targetLinkStatus = _.find(LinkStatus, function (ls) {
        return ls.description === option || (option === '' && ls.value === 99);
      });
      if (isSplitMode)
        console.log("NOT A SPLIT FORM!");
      else
        return transitionModifiers(targetLinkStatus, linkStatus);
    };

    var selectedProjectLinkTemplate = function(project, selected, errorMessage) {
      var road = {
        roadNumber: selected[0].roadNumber,
        roadPartNumber: selected[0].roadPartNumber,
        trackCode: selected[0].trackCode
      };
      var selection = formCommon.selectedData(selected);
      return _.template('' +
        '<header>' +
        formCommon.titleWithProjectName(project.name, projectCollection.getCurrentProject()) +
        '</header>' +
        '<div class="wrapper read-only">'+
        '<div class="form form-horizontal form-dark">'+
        '<div class="edit-control-group project-choice-group">'+
        insertErrorMessage(errorMessage) +
        formCommon.staticField('Lisätty järjestelmään', project.createdBy + ' ' + project.startDate)+
        formCommon.staticField('Muokattu viimeksi', project.modifiedBy + ' ' + project.dateModified)+
        '<div class="form-group editable form-editable-roadAddressProject"> '+

        selectionForm(selection, selected, road) +
        formCommon.changeDirection(selected) +
        formCommon.actionSelectedField()+
        '</div>'+
        '</div>' +
        '</div>'+
        '</div>'+
        '<footer>' + formCommon.actionButtons('project-', projectCollection.isDirty()) + '</footer>');
    };

    var selectionForm = function(selection, selected, road){
      var defaultOption = (selected[0].status === LinkStatus.NotHandled.value ? LinkStatus.NotHandled.description : LinkStatus.Undefined.description);
      return '<form id="roadAddressProjectForm" class="input-unit-combination form-group form-horizontal roadAddressProject">'+
        '<label>Toimenpiteet,' + selection  + '</label>' +
        '<div class="input-unit-combination">' +
        '<select class="action-select" id="dropdown_0" size="1">'+
        '<option id="drop_0_' + '" '+ defineOptionModifiers(defaultOption, selected) +'>Valitse</option>'+
        '<option id="drop_0_' + LinkStatus.Unchanged.description + '" value='+ LinkStatus.Unchanged.description+' ' + defineOptionModifiers(LinkStatus.Unchanged.description, selected) + '>Ennallaan</option>'+
        '<option id="drop_0_' + LinkStatus.Transfer.description + '" value='+ LinkStatus.Transfer.description + ' ' + defineOptionModifiers(LinkStatus.Transfer.description, selected) + '>Siirto</option>'+
        '<option id="drop_0_' + LinkStatus.New.description + '" value='+ LinkStatus.New.description + ' ' + defineOptionModifiers(LinkStatus.New.description, selected) +'>Uusi</option>'+
        '<option id="drop_0_' + LinkStatus.Terminated.description + '" value='+ LinkStatus.Terminated.description + ' ' + defineOptionModifiers(LinkStatus.Terminated.description, selected) + '>Lakkautus</option>'+
        '<option id="drop_0_' + LinkStatus.Numbering.description + '" value='+ LinkStatus.Numbering.description + ' ' + defineOptionModifiers(LinkStatus.Numbering.description, selected) + '>Numerointi</option>'+
        '<option id="drop_0_' + LinkStatus.Revert.description + '" value='+ LinkStatus.Revert.description + ' ' + defineOptionModifiers(LinkStatus.Revert.description, selected) + '>Palautus aihioksi tai tieosoitteettomaksi</option>' +
        '</select>'+
        '</div>'+
        formCommon.newRoadAddressInfo(selected, selectedProjectLink, road) +
        '</form>';
    };

    var insertErrorMessage = function (errorMessage) {
      if(!_.isUndefined(errorMessage) && errorMessage !== ""){
        return addSmallLabelLowercase( 'VIRHE: ' + errorMessage);
      }
      else return "";
    };

    var addSmallLabelLowercase = function(label){
        return '<label class="control-label-small" style="text-transform: none">'+label+'</label>';
    };

    var emptyTemplate = function(project) {
      return _.template('' +
        '<header>' +
        formCommon.titleWithProjectName(project.name, projectCollection.getCurrentProject()) +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<label class="highlighted">JATKA VALITSEMALLA KOHDE KARTALTA.</label>' +
        '<div class="form-group" id="project-errors"></div>' +
        '</div></div></br></br>' +
        '<footer>'+showProjectChangeButton()+'</footer>');
    };

    var isProjectPublishable = function(){
      return projectCollection.getPublishableStatus();
    };

    var isProjectEditable = function(){
      return _.contains(editableStatus, projectCollection.getCurrentProject().project.statusCode);
    };

    var checkInputs = function () {
      var rootElement = $('#feature-attributes');
      var inputs = rootElement.find('input');
      var filled = true;
      var pedestrianRoads = 70000;
      for (var i = 0; i < inputs.length; i++) {
        var isPedestrian = $('#tie')[0].value >= pedestrianRoads;
        if (inputs[i].type === 'text' && !inputs[i].value && !(isPedestrian && inputs[i].id === 'roadName')) {
          filled = false;
        }
      }
      if (filled) {
        rootElement.find('.project-form button.update').prop("disabled", false);
      } else {
        rootElement.find('.project-form button.update').prop("disabled", true);
      }
    };

    var changeDropDownValue = function (statusCode) {
      var rootElement = $('#feature-attributes');
      if (statusCode === LinkStatus.Unchanged.value) {
        $("#dropDown_0 option[value="+ LinkStatus.New.description +"]").prop('disabled',true);
        $("#dropDown_0 option[value="+ LinkStatus.Unchanged.description +"]").attr('selected', 'selected').change();
      }
      else if (statusCode === LinkStatus.New.value){
        $("#dropDown_0 option[value="+ LinkStatus.New.description +"]").attr('selected', 'selected').change();
        projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
        rootElement.find('.new-road-address').prop("hidden", false);
        if(selectedProjectLink[0].id !== 0)
          rootElement.find('.changeDirectionDiv').prop("hidden", false);
      }
      else if (statusCode == LinkStatus.Transfer.value) {
        $("#dropDown_0 option[value="+ LinkStatus.New.description +"]").prop('disabled',true);
        $("#dropDown_0 option[value="+ LinkStatus.Transfer.description +"]").attr('selected', 'selected').change();
      }
      else if (statusCode == LinkStatus.Numbering.value) {
        $("#dropDown_0 option[value="+ LinkStatus.Numbering.description +"]").attr('selected', 'selected').change();
      }
      $('#discontinuityDropdown').val(selectedProjectLink[selectedProjectLink.length - 1].discontinuity);
      $('#roadTypeDropDown').val(selectedProjectLink[0].roadTypeId);
    };

    var fillDistanceValues = function (selectedLinks) {
      if (selectedLinks.length === 1 && selectedLinks[0].calibrationCode === CalibrationCode.AtBoth.value) {
        $('#beginDistance').val(selectedLinks[0].startAddressM);
        $('#endDistance').val(selectedLinks[0].endAddressM);
      } else {
        var orderedByStartM = _.sortBy(selectedLinks, function (l) {
          return l.startAddressM;
        });
        if (orderedByStartM[0].calibrationCode === CalibrationCode.AtBeginning.value) {
          $('#beginDistance').val(orderedByStartM[0].startAddressM);
        }
        if (orderedByStartM[orderedByStartM.length - 1].calibrationCode === CalibrationCode.AtEnd.value) {
          $('#endDistance').val(orderedByStartM[orderedByStartM.length - 1].endAddressM);
          endDistanceOriginalValue = orderedByStartM[orderedByStartM.length - 1].endAddressM;
        }
      }
    };

    var disableFormInputs = function () {
      if (!isProjectEditable()) {
        $('#roadAddressProjectForm select').prop('disabled',true);
        $('#roadAddressProjectFormCut select').prop('disabled',true);
        $('.update').prop('disabled', true);
        $('.btn-edit-project').prop('disabled', true);
      }
    };

    var setFormDirty = function() {
      selectedProjectLinkProperty.setDirty(true);
      eventbus.trigger('roadAddressProject:toggleEditingRoad', false);
    };

    var bindEvents = function() {

      var rootElement = $('#feature-attributes');

      eventbus.on('projectLink:clicked', function(selected) {
        selectedProjectLink = selected;
        var currentProject = projectCollection.getCurrentProject();
        formCommon.clearInformationContent();
        rootElement.html(selectedProjectLinkTemplate(currentProject.project, selectedProjectLink));
        formCommon.replaceAddressInfo(backend, selectedProjectLink);
        checkInputs('.project-');
        changeDropDownValue(selectedProjectLink[0].status);
        disableFormInputs();
        var selectedDiscontinuity = _.max(selectedProjectLink, function(projectLink){
          return projectLink.endAddressM;
        }).discontinuity;
        $('#discontinuityDropdown').val(selectedDiscontinuity.toString());
      });

      eventbus.on('projectLink:errorClicked', function(selected, errorMessage) {
          selectedProjectLink = [selected[0]];
          var currentProject = projectCollection.getCurrentProject();
          formCommon.clearInformationContent();
          rootElement.html(selectedProjectLinkTemplate(currentProject.project, selectedProjectLink, errorMessage));
          formCommon.replaceAddressInfo(backend, selectedProjectLink);
          checkInputs('.project-');
          changeDropDownValue(selectedProjectLink[0].status);
          disableFormInputs();
          var selectedDiscontinuity = _.max(selectedProjectLink, function(projectLink){
              return projectLink.endAddressM;
          }).discontinuity;
          $('#discontinuityDropdown').val(selectedDiscontinuity.toString());
      });

      eventbus.on('roadAddress:projectFailed', function() {
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectLinksUpdateFailed',function(errorCode){
        applicationModel.removeSpinner();
        if (errorCode == 400){
          return new ModalConfirm("Päivitys epäonnistui puutteelisten tietojen takia. Ota yhteyttä järjestelmätukeen.");
        } else if (errorCode == 401){
          return new ModalConfirm("Sinulla ei ole käyttöoikeutta muutoksen tekemiseen.");
        } else if (errorCode == 412){
          return new ModalConfirm("Täyttämättömien vaatimusten takia siirtoa ei saatu tehtyä. Ota yhteyttä järjestelmätukeen.");
        } else if (errorCode == 500){
          return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen virheen takia, ota yhteyttä järjestelmätukeen.");
        } else {
          return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen tuntemattoman virheen takia, ota yhteyttä järjestelmätukeen.");
        }
      });

      eventbus.on('roadAddress:projectLinksUpdated',function(data){
        eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        projectCollection.setDirty([]);
        selectedProjectLink = false;
        selectedProjectLinkProperty.cleanIds();
        rootElement.html(emptyTemplate(projectCollection.getCurrentProject().project));
        formCommon.toggleAdditionalControls();
        if (typeof data !== 'undefined' && typeof data.publishable !== 'undefined' && data.publishable) {
          eventbus.trigger('roadAddressProject:projectLinkSaved', data.id, data.publishable);
        } else {
          eventbus.trigger('roadAddressProject:projectLinkSaved');
        }
      });

      eventbus.on('roadAddress:projectSentSuccess', function() {
        new ModalConfirm("Muutosilmoitus lähetetty Tierekisteriin.");
        //TODO: make more generic layer change/refresh
        applicationModel.selectLayer('linkProperty');

        rootElement.empty();
        formCommon.clearInformationContent();

        selectedProjectLinkProperty.close();
        projectCollection.clearRoadAddressProjects();
        projectCollection.reset();
        applicationModel.setOpenProject(false);

        eventbus.trigger('roadAddressProject:deselectFeaturesSelected');
        eventbus.trigger('roadLinks:refreshView');
      });

      eventbus.on('roadAddress:projectSentFailed', function(error) {
        new ModalConfirm(error);
      });

      eventbus.on('roadAddress:projectLinksCreateSuccess', function () {
        eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        selectedProjectLinkProperty.cleanIds();
        rootElement.find('.changeDirectionDiv').prop("hidden", false);
      });

      eventbus.on('roadAddress:changeDirectionFailed', function(error) {
        new ModalConfirm(error);
      });

      rootElement.on('click','.changeDirection', function () {
        if(!_.isUndefined(selectedProjectLinkProperty.get()[0]) && !_.isUndefined(selectedProjectLinkProperty.get()[0].connectedLinkId) && selectedProjectLinkProperty.get()[0].connectedLinkId !== 0) {
          projectCollection.changeNewProjectLinkCutDirection(projectCollection.getCurrentProject().project.id, selectedProjectLinkProperty.get());
        }
        else{
          projectCollection.changeNewProjectLinkDirection(projectCollection.getCurrentProject().project.id, selectedProjectLinkProperty.get());
        }
      });

      eventbus.on('roadAddress:projectLinksSaveFailed', function (result) {
        new ModalConfirm(result.toString());
      });

      eventbus.on('roadAddressProject:discardChanges',function(){
        cancelChanges();
      });

      var canChangeDirection = function () {
        if(_.isUndefined(_.find(selectedProjectLink, function (link) {return (link.status === LinkStatus.Terminated.value || link.status === LinkStatus.NotHandled.value);}))) {
          rootElement.find('.changeDirectionDiv').prop("hidden", false);
        } else {
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
        }
      };

      var saveChanges = function(){
        var successSavingChanges = true;
        var currentProject = projectCollection.getCurrentProject();
        //TODO revert dirtyness if others than ACTION_TERMINATE is choosen, because now after Lakkautus, the link(s) stay always in black color
        var statusDropdown_0 =$('#dropdown_0').val();
        var statusDropdown_1 = $('#dropdown_1').val();
        switch (statusDropdown_0){
          case LinkStatus.Unchanged.description : {
            if(!_.isUndefined(statusDropdown_1) && statusDropdown_1 == LinkStatus.New.description){
              successSavingChanges = projectCollection.saveCutProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Unchanged.value, LinkStatus.New.value);
            }
            else if(_.isUndefined(statusDropdown_1)){
              successSavingChanges = projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Unchanged.value);
            }
            break;
          }
          case LinkStatus.New.description : {
            if(!_.isUndefined(statusDropdown_1) && statusDropdown_1 == LinkStatus.Unchanged.description){
              successSavingChanges = projectCollection.saveCutProjectLinks(projectCollection.getTmpDirty(), LinkStatus.New.value, LinkStatus.Unchanged.value);
            }

            else if(!_.isUndefined(statusDropdown_1) && statusDropdown_1 == LinkStatus.Transfer.description){
              successSavingChanges = projectCollection.saveCutProjectLinks(projectCollection.getTmpDirty(), LinkStatus.New.value, LinkStatus.Transfer.value);
            }
            else if(_.isUndefined(statusDropdown_1)) {
              successSavingChanges = projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.New.value);
            }
            break;
          }
          case LinkStatus.Transfer.description : {
            if(!_.isUndefined(statusDropdown_1) && statusDropdown_1 == LinkStatus.New.description){
              successSavingChanges = projectCollection.saveCutProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Unchanged.value, LinkStatus.New.value);
            }
            else if(_.isUndefined(statusDropdown_1)){
              successSavingChanges = projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Transfer.value);
            }
            break;
          }
          case LinkStatus.Numbering.description : {
            successSavingChanges = projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Numbering.value); break;
          }
          case LinkStatus.Terminated.description: {
            successSavingChanges = projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Terminated.value); break;
          }
          case LinkStatus.Revert.description : {
            projectCollection.revertChangesRoadlink(selectedProjectLink); break;
          }
        }

        if(successSavingChanges){
          selectedProjectLinkProperty.setDirty(false);
          rootElement.html(emptyTemplate(currentProject.project));
          formCommon.toggleAdditionalControls();
        }
      };

      var cancelChanges = function() {
        projectCollection.revertLinkStatus();
        projectCollection.setDirty([]);
        projectCollection.setTmpDirty([]);
        projectLinkLayer.clearHighlights();
        selectedProjectLinkProperty.cleanIds();
        $('.wrapper').remove();
        eventbus.trigger('roadAddress:projectLinksEdited');
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        eventbus.trigger('roadAddressProject:reOpenCurrent');
      };

      rootElement.on('change', '#endDistance', function(eventData){
        setFormDirty();
        var changedValue = parseInt(eventData.target.value);
        if(!isNaN(changedValue) && !isNaN(parseInt(endDistanceOriginalValue)) && changedValue !== endDistanceOriginalValue)
          $('#manualCPWarning').css('display', 'inline-block');
        else $('#manualCPWarning').css('display', 'none');
      });

      rootElement.on('change', '#roadTypeDropDown', function(){
        setFormDirty();
      });

      rootElement.on('change', '.form-select-control', function () {
        setFormDirty();
      });

      rootElement.on('click', '.project-form button.update', function() {
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        saveChanges();
      });

      rootElement.on('change', '#roadAddressProjectForm #dropdown_0', function() {
        $('#tie').prop('disabled',false);
        $('#osa').prop('disabled',false);
        $('#trackCodeDropdown').prop('disabled',false);
        $('#discontinuityDropdown').prop('disabled',false);
        $('#roadTypeDropDown').prop('disabled',false);
        if(this.value == LinkStatus.Terminated.description) {
          rootElement.find('.new-road-address').prop("hidden", true);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          projectCollection.setDirty(projectCollection.getDirty().concat(_.map(selectedProjectLink, function (link) {
            return {'linkId': link.linkId, 'status': LinkStatus.Terminated.value, 'roadLinkSource': link.roadLinkSource, 'points': link.points, 'id': link.id};
          })));
          projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
          rootElement.find('.project-form button.update').prop("disabled", false);
        }
        else if(this.value == LinkStatus.New.description){
          projectCollection.setTmpDirty(_.filter(projectCollection.getTmpDirty(), function (l) { return l.status !== LinkStatus.Terminated.value;}).concat(selectedProjectLink));
          rootElement.find('.new-road-address').prop("hidden", false);
          if(selectedProjectLink[0].id !== 0) {
            fillDistanceValues(selectedProjectLink);
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
            rootElement.find('#distanceValue').prop("hidden", false);
          }
        }
        else if(this.value == LinkStatus.Unchanged.description){
          rootElement.find('.new-road-address').prop("hidden", false);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          $('#tie').prop('disabled',true);
          $('#osa').prop('disabled',true);
          $('#trackCodeDropdown').prop('disabled',true);
          $('#discontinuityDropdown').prop('disabled',false);
          $('#roadTypeDropDown').prop('disabled',false);
          projectCollection.setDirty(projectCollection.getDirty().concat(_.map(selectedProjectLink, function (link) {
            return {'linkId': link.linkId, 'status': LinkStatus.Unchanged.value, 'roadLinkSource': link.roadLinkSource, 'points': link.points, 'id': link.id};
          })));
          projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
        }
        else if(this.value == LinkStatus.Transfer.description) {
          projectCollection.setDirty(_.filter(projectCollection.getDirty(), function(dirty) {return dirty.status === LinkStatus.Transfer.value;}).concat(_.map(selectedProjectLink, function (link) {
            return {'linkId': link.linkId, 'status': LinkStatus.Transfer.value, 'roadLinkSource': link.roadLinkSource, 'points': link.points, 'id': link.id};
          })));
          projectCollection.setTmpDirty(projectCollection.getDirty());
          rootElement.find('.new-road-address').prop("hidden", false);
          canChangeDirection();
        }
        else if(this.value == LinkStatus.Numbering.description) {
          new ModalConfirm("Numerointi koskee kokonaista tieosaa. Valintaasi on tarvittaessa laajennettu koko tieosalle.");
          $('#trackCodeDropdown').prop('disabled',true);
          $('#discontinuityDropdown').prop('disabled',false);
          $('#roadTypeDropDown').prop('disabled',true);
          projectCollection.setDirty(projectCollection.getDirty().concat(_.map(selectedProjectLink, function (link) {
            return {'linkId': link.linkId, 'status': LinkStatus.Numbering.value, 'roadLinkSource': link.roadLinkSource, 'points': link.points, 'id': link.id};
          })));
          projectCollection.setTmpDirty(projectCollection.getDirty());
          rootElement.find('.new-road-address').prop("hidden", false);
          rootElement.find('.project-form button.update').prop("disabled", false);
          canChangeDirection();
        }
        else if(this.value == LinkStatus.Revert.description) {
          rootElement.find('.new-road-address').prop("hidden", true);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          rootElement.find('.project-form button.update').prop("disabled", false);
        }
      });

      rootElement.on('change', '.form-group', function() {
        rootElement.find('.action-selected-field').prop("hidden", false);
      });

      rootElement.on('click', ' .project-form button.cancelLink', function(){
        cancelChanges();
      });

      rootElement.on('click', '.project-form button.send', function(){
        new GenericConfirmPopup("Haluatko lähettää muutosilmoituksen Tierekisteriin?", {
          successCallback: function () {
            projectCollection.publishProject();
            closeProjectMode(true, true);
          },
          closeCallback: function () {
          }
        });

      });

      var closeProjectMode = function (changeLayerMode, noSave) {
        eventbus.trigger("roadAddressProject:startAllInteractions");
        eventbus.trigger('projectChangeTable:hide');
        applicationModel.setOpenProject(false);
        rootElement.find('header').toggle();
        rootElement.find('.wrapper').toggle();
        rootElement.find('footer').toggle();
        projectCollection.clearRoadAddressProjects();
        eventbus.trigger('layer:enableButtons', true);
        if (changeLayerMode) {
          eventbus.trigger('roadAddressProject:clearOnClose');
          applicationModel.selectLayer('linkProperty', true, noSave);
        }
      };

      rootElement.on('click', '.project-form button.show-changes', function(){
        $(this).empty();
        projectChangeTable.show();
        var projectChangesButton = showProjectChangeButton();
          if (isProjectPublishable() && isProjectEditable()) {
          formCommon.setInformationContent();
          $('footer').html(formCommon.sendRoadAddressChangeButton('project-', projectCollection.getCurrentProject()));
          } else {
          $('footer').html(projectChangesButton);
          }
      });

        rootElement.on('keyup', '.form-control.small-input', function (event) {
        checkInputs('.project-');
        setFormDirty();
            if (event.target.id === "tie" && ($('#dropdown_0').val() === 'New' || $('#dropdown_0').val() === 'Transfer' || $('#dropdown_0').val() === 'Numbering')) {
                backend.getRoadName($(this).val(), projectCollection.getCurrentProject().project.id, function (data) {
                    if (data !== null) {
                        $('#roadName').val(data.roadName).change();
                        if (data.isCurrent) {
                            $('#roadName').prop('disabled', true);
                        } else {
                            $('#roadName').prop('disabled', false);
                        }
                        checkInputs('.project-');
                    } else {
                        if ($('#roadName').prop('disabled')) {
                            $('#roadName').val('').change();
                        }
                        $('#roadName').prop('disabled', false);
                    }
                });
            }
        });

        rootElement.on('keyup', '#roadName', function () {
            checkInputs('.project-');
        });

      eventbus.on('projectLink:mapClicked', function () {
        rootElement.html(emptyTemplate(projectCollection.getCurrentProject().project));
         eventbus.trigger('roadAddressProject:writeProjectErrors') ;
      });

      rootElement.on('click', '.projectErrorButton', function (event) {
        eventbus.trigger('projectCollection:clickCoordinates', event, map);
          var error = projectCollection.getProjectErrors()[event.currentTarget.id];
          if (error.errorMessage !== "") {
              projectCollection.getProjectLinks().then(function (projectLinks) {
                  var projectLinkIds = projectLinks.map(function (link) {
                      return link.linkId;
                  });
                  if (_.every(error.linkIds, function (link) {
                          return projectLinkIds.indexOf(link) > -1;
                      })) {
                      selectedProjectLinkProperty.openWithErrorMessage(error.ids, error.errorMessage);
                  } else {
                      new ModalConfirm("Sinun täytyy varata tieosa projektille, jotta voit korjata sen.");
                  }
              });
          }
      });
    };
    bindEvents();
  };
})(this);
