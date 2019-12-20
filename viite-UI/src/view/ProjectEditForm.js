(function (root) {
  root.ProjectEditForm = function(map, projectCollection, selectedProjectLinkProperty, projectLinkLayer, projectChangeTable, backend) {
    var LinkStatus = LinkValues.LinkStatus;
    var CalibrationCode = LinkValues.CalibrationCode;
    var editableStatus = [LinkValues.ProjectStatus.Incomplete.value, LinkValues.ProjectStatus.ErrorInTR.value, LinkValues.ProjectStatus.Unknown.value];
    var ValidElys = _.map(LinkValues.ElyCodes, function(ely){
      return ely;
    });
    var selectedProjectLink = false;
    var editedNameByUser = false;
    var LinkSources = LinkValues.LinkGeomSource;
    var ProjectStatus = LinkValues.ProjectStatus;
    var formCommon = new FormCommon('');

    var endDistanceOriginalValue = '--';

    var showProjectChangeButton = function() {
      return '<div class="project-form form-controls">' +
        formCommon.projectButtons() + '</div>';
    };

    var transitionModifiers = function(targetStatus, currentStatus) {
      var mod;
      if (_.includes(targetStatus.transitionFrom, currentStatus))
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

      var roadLinkSources = _.chain(selected).map(function(s) {
        return s.roadLinkSource;
      }).uniq().map(function(a) {
        var linkGeom = _.find(LinkSources, function (source) {
          return source.value === parseInt(a);
        });
        if(_.isUndefined(linkGeom))
          return LinkSources.Unknown.descriptionFI;
        else
          return linkGeom.descriptionFI;
      }).uniq().join(", ").value();

      var selection = formCommon.selectedData(selected);
      return _.template('' +
        '<header>' +
        formCommon.title(project.name) +
        '</header>' +
        '<div class="wrapper read-only">'+
        '<div class="form form-horizontal form-dark">'+
        '<div class="edit-control-group project-choice-group">'+
        insertErrorMessage(errorMessage) +
        formCommon.staticField('Lisätty järjestelmään', project.createdBy + ' ' + project.startDate)+
        formCommon.staticField('Muokattu viimeksi', project.modifiedBy + ' ' + project.dateModified)+
        formCommon.staticField('Geometrian lähde', roadLinkSources)+
        showLinkId(selected) +
        '<div class="form-group editable form-editable-roadAddressProject"> '+

        selectionForm(project, selection, selected, road) +
        formCommon.changeDirection(selected, project) +
        formCommon.actionSelectedField()+
        '</div>'+
        '</div>' +
        '</div>'+
        '</div>'+
        '<footer>' + formCommon.actionButtons('project-', projectCollection.isDirty()) + '</footer>');
    };

    var showLinkId = function(selected){
      if (selected.length === 1){
        return '' +
          formCommon.staticField('Linkin ID', selected[0].linkId);
      } else {
        return '';}
    };

    var selectionForm = function(project, selection, selected, road) {
      var defaultOption = (selected[0].status === LinkStatus.NotHandled.value ? LinkStatus.NotHandled.description : LinkStatus.Undefined.description);
      return '<form id="roadAddressProjectForm" class="input-unit-combination form-group form-horizontal roadAddressProject">'+
        '<label>Toimenpiteet,' + selection  + '</label>' +
        '<div class="input-unit-combination">' +
        '<select class="action-select" id="dropDown_0" size="1">'+
        '<option id="drop_0_' + '" '+ defineOptionModifiers(defaultOption, selected) +'>Valitse</option>'+
        '<option id="drop_0_' + LinkStatus.Unchanged.description + '" value='+ LinkStatus.Unchanged.description+' ' + defineOptionModifiers(LinkStatus.Unchanged.description, selected) + '>Ennallaan</option>'+
        '<option id="drop_0_' + LinkStatus.Transfer.description + '" value='+ LinkStatus.Transfer.description + ' ' + defineOptionModifiers(LinkStatus.Transfer.description, selected) + '>Siirto</option>'+
        '<option id="drop_0_' + LinkStatus.New.description + '" value='+ LinkStatus.New.description + ' ' + defineOptionModifiers(LinkStatus.New.description, selected) +'>Uusi</option>'+
        '<option id="drop_0_' + LinkStatus.Terminated.description + '" value='+ LinkStatus.Terminated.description + ' ' + defineOptionModifiers(LinkStatus.Terminated.description, selected) + '>Lakkautus</option>'+
        '<option id="drop_0_' + LinkStatus.Numbering.description + '" value='+ LinkStatus.Numbering.description + ' ' + defineOptionModifiers(LinkStatus.Numbering.description, selected) + '>Numerointi</option>'+
        '<option id="drop_0_' + LinkStatus.Revert.description + '" value='+ LinkStatus.Revert.description + ' ' + defineOptionModifiers(LinkStatus.Revert.description, selected) + '>Palautus aihioksi tai tieosoitteettomaksi</option>' +
        '</select>'+
        '</div>'+
        formCommon.newRoadAddressInfo(project, selected, selectedProjectLink, road) +
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
        formCommon.titleWithEditingTool(project) +
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
      return _.includes(editableStatus, projectCollection.getCurrentProject().project.statusCode);
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

      var trackCodeDropdown = $('#trackCodeDropdown')[0];
      filled = filled && !_.isUndefined(trackCodeDropdown) && !_.isUndefined(trackCodeDropdown.value) && trackCodeDropdown.value !== '99';

      var roadTypeCodeDropdown = $('#roadTypeDropdown')[0];
      filled = filled && !_.isUndefined(roadTypeCodeDropdown) && !_.isUndefined(roadTypeCodeDropdown.value) && roadTypeCodeDropdown.value !== '0';

      if (filled) {
        rootElement.find('.project-form button.update').prop("disabled", false);
      } else {
        rootElement.find('.project-form button.update').prop("disabled", true);
      }

    };

    var changeDropDownValue = function (statusCode) {
      var dropdown_0_new = $("#dropDown_0 option[value=" + LinkStatus.New.description + "]");
      var rootElement = $('#feature-attributes');
      switch (statusCode) {
        case LinkStatus.Unchanged.value:
          dropdown_0_new.prop('disabled',true);
          $("#dropDown_0 option[value=" + LinkStatus.Unchanged.description + "]").attr('selected', 'selected').change();
          break;
        case LinkStatus.New.value:
          dropdown_0_new.attr('selected', 'selected').change();
          projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
          rootElement.find('.new-road-address').prop("hidden", false);
          if(selectedProjectLink[0].id !== 0)
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
          break;
        case LinkStatus.Transfer.value:
          dropdown_0_new.prop('disabled',true);
          $("#dropDown_0 option[value=" + LinkStatus.Transfer.description + "]").attr('selected', 'selected').change();
          break;
        case LinkStatus.Numbering.value:
          $("#dropDown_0 option[value=" + LinkStatus.Numbering.description + "]").attr('selected', 'selected').change();
          break;
      }
      $('#discontinuityDropdown').val(selectedProjectLink[selectedProjectLink.length - 1].discontinuity);
    };

    var removeNumberingFromDropdown = function () {
      $("#dropDown_0").children("#dropDown_0 option[value=" + LinkStatus.Numbering.description + "]").remove();
    };

    var fillDistanceValues = function (selectedLinks) {
      if (selectedLinks.length === 1 && selectedLinks[0].calibrationCode === CalibrationCode.AtBoth.value) {
        $('#beginDistance').val(selectedLinks[0].startAddressM);
        if (isProjectEditable()) {
          $('#endDistance').prop("readonly", false).val(selectedLinks[0].endAddressM);
        } else {
          $('#endDistance').val(selectedLinks[0].endAddressM);
        }
      } else {
        var orderedByStartM = _.sortBy(selectedLinks, function (l) {
          return l.startAddressM;
        });
        if (orderedByStartM[0].calibrationCode === CalibrationCode.AtBeginning.value) {
          $('#beginDistance').val(orderedByStartM[0].startAddressM);
        }
        if (orderedByStartM[orderedByStartM.length - 1].calibrationCode === CalibrationCode.AtEnd.value) {
          if (isProjectEditable()) {
            $('#endDistance').prop("readonly", false).val(orderedByStartM[orderedByStartM.length - 1].endAddressM);
          } else {
            $('#endDistance').val(orderedByStartM[orderedByStartM.length - 1].endAddressM);
          }
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
        if (projectCollection.getCurrentProject().project.statusCode === ProjectStatus.SendingToTR.value) {
          $(":input").prop('disabled',true);
          $(".project-form button.cancelLink").prop('disabled',false);
        }
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
        formCommon.replaceAddressInfo(backend, selectedProjectLink, currentProject.project.id);
        updateForm();
        _.defer(function() {
          $('#beginDistance').on("change", function(changedData) {
            eventbus.trigger('projectLink:editedBeginDistance', changedData.target.value);
          });
          $('#endDistance').on("change", function(changedData) {
            eventbus.trigger('projectLink:editedEndDistance', changedData.target.value);
          });
        });
      });

      function updateForm() {
        checkInputs('.project-');
        changeDropDownValue(selectedProjectLink[0].status);
        if (selectedProjectLink[0].status !== LinkStatus.Numbering.value && _.filter(projectCollection.getFormedParts(), function (formedLink) {
          return formedLink.roadNumber === selectedProjectLink[0].roadNumber && formedLink.roadPartNumber === selectedProjectLink[0].roadPartNumber;
        }).length !== 0) {
          removeNumberingFromDropdown();
        }
        disableFormInputs();
        var selectedDiscontinuity = _.maxBy(selectedProjectLink, function (projectLink) {
          return projectLink.endAddressM;
        }).discontinuity;
        $('#discontinuityDropdown').val(selectedDiscontinuity.toString());
      }

      eventbus.on('projectLink:errorClicked', function(selected, errorMessage) {
        selectedProjectLink = [selected[0]];
        var currentProject = projectCollection.getCurrentProject();
        formCommon.clearInformationContent();
        rootElement.html(selectedProjectLinkTemplate(currentProject.project, selectedProjectLink, errorMessage));
        formCommon.replaceAddressInfo(backend, selectedProjectLink);
        updateForm();
      });

      eventbus.on('roadAddress:projectFailed', function() {
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectLinksUpdateFailed',function(errorCode){
        applicationModel.removeSpinner();
        switch (errorCode) {
          case 400:
            return new ModalConfirm("Päivitys epäonnistui puutteelisten tietojen takia. Ota yhteyttä järjestelmätukeen.");
          case 401:
            return new ModalConfirm("Sinulla ei ole käyttöoikeutta muutoksen tekemiseen.");
          case 412:
            return new ModalConfirm("Täyttämättömien vaatimusten takia siirtoa ei saatu tehtyä. Ota yhteyttä järjestelmätukeen.");
          case 500:
            return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen virheen takia, ota yhteyttä järjestelmätukeen.");
          default:
            return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen tuntemattoman virheen takia, ota yhteyttä järjestelmätukeen.");
        }
      });

      eventbus.on('roadAddress:projectLinksUpdated', function(data) {
        eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        projectCollection.setDirty([]);
        selectedProjectLinkProperty.setCurrent([]);
        selectedProjectLinkProperty.setDirty(false);
        selectedProjectLink = false;
        selectedProjectLinkProperty.cleanIds();
        rootElement.html(emptyTemplate(projectCollection.getCurrentProject().project));
        formCommon.toggleAdditionalControls();
        applicationModel.removeSpinner();
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
        selectedProjectLinkProperty.close();
        projectCollection.clearRoadAddressProjects();
        projectCollection.reset();
        applicationModel.setOpenProject(false);
        eventbus.trigger('layer:enableButtons', true);
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

      eventbus.on('roadAddressProject:discardChanges', function () {
        cancelChanges();
      });

      var canChangeDirection = function () {
        if(_.isUndefined(_.find(selectedProjectLink, function (link) {return (link.status === LinkStatus.Terminated.value || link.status === LinkStatus.NotHandled.value);}))) {
          rootElement.find('.changeDirectionDiv').prop("hidden", false);
        } else {
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
        }
      };

      var saveChanges = function() {
        //TODO revert dirtyness if others than ACTION_TERMINATE is choosen, because now after Lakkautus, the link(s) stay always in black color
        var isValidEly = _.find(ValidElys, function(ely){
          return ely.value == $('#ely')[0].value;
        });
        if(!isValidEly){
          return new ModalConfirm("Tarkista antamasi ELY-koodi. Annettu arvo on virheellinen.");
        }

        var statusDropdown_0 =$('#dropDown_0').val();
        var statusDropdown_1 = $('#dropDown_1').val();

        var objectDropdown_0 = _.find(LinkStatus, function(obj){
          return obj.description === statusDropdown_0;
        });
        var objectDropdown_1 = _.find(LinkStatus, function(obj){
          return obj.description === statusDropdown_1;
        });

        if (objectDropdown_0.value === LinkStatus.Revert.value) {
          projectCollection.revertChangesRoadlink(selectedProjectLink);
        } else if (!_.isUndefined(objectDropdown_1)) {
          projectCollection.saveCutProjectLinks(projectCollection.getTmpDirty(), objectDropdown_0.value, objectDropdown_1.value);
        } else {
          projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), objectDropdown_0.value);
        }
      };

      var cancelChanges = function() {
        projectCollection.revertLinkStatus();
        projectCollection.setDirty([]);
        projectCollection.setTmpDirty([]);
        projectLinkLayer.clearHighlights();
        selectedProjectLinkProperty.cleanIds();
        selectedProjectLinkProperty.clean();
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

      rootElement.on('change', '#roadTypeDropdown', function(){
        setFormDirty();
      });

      rootElement.on('change', '.form-select-control', function () {
        setFormDirty();
      });

      rootElement.on('click', '.project-form button.update', function() {
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        saveChanges();
      });

      rootElement.on('change', '#roadAddressProjectForm #dropDown_0', function() {
        $('#tie').prop('disabled',false);
        $('#osa').prop('disabled',false);
        $('#trackCodeDropdown').prop('disabled',false);
        $('#discontinuityDropdown').prop('disabled',false);
        $('#roadTypeDropdown').prop('disabled',false);
        if(this.value == LinkStatus.Terminated.description) {
          rootElement.find('.new-road-address').prop("hidden", true);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          projectCollection.setDirty(_.map(selectedProjectLink, function (link) {
            return {
              'id': link.id,
              'linkId': link.linkId,
              'status': LinkStatus.Terminated.value,
              'roadLinkSource': link.roadLinkSource,
              'points': link.points,
              'linearLocationId': link.linearLocationId
            };
          }));
          projectCollection.setTmpDirty(projectCollection.getDirty());
          rootElement.find('.project-form button.update').prop("disabled", false);
        }
        else if (this.value == LinkStatus.New.description) {
          projectCollection.setDirty(_.map(selectedProjectLink, function (link) {
            return {
              'id': link.id,
              'linkId': link.linkId,
              'status': LinkStatus.New.value,
              'roadLinkSource': link.roadLinkSource,
              'points': link.points,
              'linearLocationId': link.linearLocationId
            };
          }));
          projectCollection.setTmpDirty(projectCollection.getDirty());
          rootElement.find('.new-road-address').prop("hidden", false);
          if(selectedProjectLink[0].id !== 0) {
            fillDistanceValues(selectedProjectLink);
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
            rootElement.find('#distanceValue').prop("hidden", false);
          }
        }
        else if (this.value == LinkStatus.Unchanged.description) {
          rootElement.find('.new-road-address').prop("hidden", false);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          $('#tie').prop('disabled',true);
          $('#osa').prop('disabled',true);
          $('#trackCodeDropdown').prop('disabled',true);
          $('#discontinuityDropdown').prop('disabled',false);
          $('#roadTypeDropdown').prop('disabled',false);
          projectCollection.setDirty(_.map(selectedProjectLink, function (link) {
            return {
              'id': link.id,
              'linkId': link.linkId,
              'status': LinkStatus.Unchanged.value,
              'roadLinkSource': link.roadLinkSource,
              'points': link.points,
              'linearLocationId': link.linearLocationId
            };
          }));
          projectCollection.setTmpDirty(projectCollection.getDirty());
        }
        else if (this.value == LinkStatus.Transfer.description) {
          projectCollection.setDirty(_.map(selectedProjectLink, function (link) {
            return {
              'id': link.id,
              'linkId': link.linkId,
              'status': LinkStatus.Transfer.value,
              'roadLinkSource': link.roadLinkSource,
              'points': link.points,
              'linearLocationId': link.linearLocationId
            };
          }));
          projectCollection.setTmpDirty(projectCollection.getDirty());
          rootElement.find('.new-road-address').prop("hidden", false);
          canChangeDirection();
        }
        else if (this.value == LinkStatus.Numbering.description) {
          new ModalConfirm("Numerointi koskee kokonaista tieosaa. Valintaasi on tarvittaessa laajennettu koko tieosalle.");
          $('#trackCodeDropdown').prop('disabled',true);
          $('#discontinuityDropdown').prop('disabled',false);
          $('#roadTypeDropdown').prop('disabled',true);
          projectCollection.setDirty(_.map(selectedProjectLink, function (link) {
            return {
              'id': link.id,
              'linkId': link.linkId,
              'status': LinkStatus.Numbering.value,
              'roadLinkSource': link.roadLinkSource,
              'points': link.points,
              'linearLocationId': link.linearLocationId
            };
          }));
          projectCollection.setTmpDirty(projectCollection.getDirty());
          rootElement.find('.new-road-address').prop("hidden", false);
          rootElement.find('.project-form button.update').prop("disabled", false);
          canChangeDirection();
        }
        else if (this.value == LinkStatus.Revert.description) {
          rootElement.find('.new-road-address').prop("hidden", true);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          rootElement.find('.project-form button.update').prop("disabled", false);
        }
      });

      rootElement.on('change', '#trackCodeDropdown', function () {
        checkInputs('.project-');
      });

      rootElement.on('change', '#roadTypeDropdown', function () {
        checkInputs('.project-');
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
        eventbus.trigger('layer:enableButtons', false);
        eventbus.trigger('form:showPropertyForm');
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

      rootElement.on('change input', '.form-control.small-input', function (event) {
        var dropdown_0 = $('#dropDown_0');
        var roadNameField =$('#roadName');
        checkInputs('.project-');
        setFormDirty();
        if (event.target.id === "tie" && (dropdown_0.val() === 'New' || dropdown_0.val() === 'Transfer' || dropdown_0.val() === 'Numbering')) {
          backend.getRoadName($(this).val(), projectCollection.getCurrentProject().project.id, function (data) {
            if (!_.isUndefined(data.roadName)) {
              editedNameByUser = false;
              roadNameField.val(data.roadName).change();
              if (data.isCurrent) {
                roadNameField.prop('disabled', true);
              } else {
                roadNameField.prop('disabled', false);
              }
              checkInputs('.project-');
            } else {
              if (roadNameField.prop('disabled') || !editedNameByUser) {
                $('#roadName').val('').change();
                editedNameByUser = false;
              }
              roadNameField.prop('disabled', false);
            }
          });
        }
      });

      rootElement.on('keyup, input', '#roadName', function () {
        checkInputs('.project-');
        editedNameByUser = $('#roadName').val !== '';
      });

      rootElement.on('click', '.projectErrorButton', function (event) {
        var error = projectCollection.getProjectErrors()[event.currentTarget.id];
        var roadPartErrors = [
          LinkValues.ProjectError.TerminationContinuity.value,
          LinkValues.ProjectError.DoubleEndOfRoad.value,
          LinkValues.ProjectError.RoadNotReserved.value
        ];
        if (_.includes(roadPartErrors, error.errorCode)) {
          var rootElement = $('#feature-attributes');
          rootElement.find('#editProjectSpan').click();
        } else {
          eventbus.trigger('projectCollection:clickCoordinates', event, map);
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
        }
      });
    };
    bindEvents();
  };
})(this);
