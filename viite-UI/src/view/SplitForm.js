(function (root) {
  root.SplitForm = function (projectCollection, selectedProjectLinkProperty, projectLinkLayer, projectChangeTable, backend) {
    var LinkStatus = LinkValues.LinkStatus;
    var LinkGeomSource = LinkValues.LinkGeomSource;
    var SideCode = LinkValues.SideCode;
    var editableStatus = [LinkValues.ProjectStatus.Incomplete.value, LinkValues.ProjectStatus.ErrorInTR.value, LinkValues.ProjectStatus.Unknown.value];
    var isReSplitMode = false;
    var currentProject = false;
    var currentSplitData = false;
    var selectedProjectLink = false;
    var formCommon = new FormCommon('split-');

    var showProjectChangeButton = function () {
      return '<div class="split-form form-controls">' +
        formCommon.projectButtons() + '</div>';
    };

    var revertSplitButton = function () {
      return '<div class="form-group" style="margin-top:15px">' +
        '<button id="revertSplit" class="form-group revertSplit btn btn-primary">Palauta aihioksi</button>' +
        '</div>';
    };

    var selectedSplitData = function (selected, road) {
      var span = [];
      _.each(selected, function (sel) {
        if (sel) {
          var link = sel;
          var div = '<div class="project-edit-selections" style="display:inline-block;padding-left:8px;">' +
            '<div class="project-edit">' +
            ' TIE <span class="project-edit">' + road.roadNumber + '</span>' +
            ' OSA <span class="project-edit">' + road.roadPartNumber + '</span>' +
            ' AJR <span class="project-edit">' + road.trackCode + '</span>' +
            ' M:  <span class="project-edit">' + link.startAddressM + ' - ' + link.endAddressM + '</span>' +
            '</div>' +
            '</div>';
          span.push(div);
        }
      });
      return span;
    };

    var selectedProjectLinkTemplate = function (project, selected) {
      isReSplitMode = !_.isUndefined(selected[0].connectedLinkId);
      if (isReSplitMode)
        currentSplitData = {
          roadNumber: selected[0].roadNumber,
          roadPartNumber: selected[0].roadPartNumber,
          trackCode: selected[0].trackCode,
          a: selected[0],
          b: selected[1],
          c: selected[2]
        };
      else
        currentSplitData = selectedProjectLinkProperty.getPreSplitData();
      var selection = selectedSplitData(selected, currentSplitData);
      var roadLinkSources = _.chain(selected).map(function (s) {
        return s.roadLinkSource;
      }).uniq().map(function (a) {
        var linkGeom = _.find(LinkGeomSource, function (source) {
          return source.value === parseInt(a);
        });
        if (_.isUndefined(linkGeom))
          return LinkGeomSource.Unknown.descriptionFI;
        else
          return linkGeom.descriptionFI;
      }).uniq().join(", ").value();
      return _.template('' +
        '<header>' +
        formCommon.title(project.name) +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<div class="edit-control-group project-choice-group">' +
        formCommon.staticField('Lisätty järjestelmään', project.createdBy + ' ' + project.startDate) +
        formCommon.staticField('Muokattu viimeksi', project.modifiedBy + ' ' + project.dateModified) +
        formCommon.staticField('Geometrian Lähde', roadLinkSources) +
        '<div class="split-form-group editable form-editable-roadAddressProject"> ' +
        selectionFormCutted(project, selection, selected, currentSplitData) +
        (isReSplitMode ? '' : formCommon.changeDirection(selected, project)) +
        formCommon.actionSelectedField() +
        (isReSplitMode ? revertSplitButton() : '') +
        '</div>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '<footer>' + formCommon.actionButtons('split-', projectCollection.isDirty()) + '</footer>');
    };

    var getSplitPointBySideCode = function (link) {
      if (link.sideCode === SideCode.AgainstDigitizing.value) {
        return _.head(link.points);
      } else {
        return _.last(link.points);
      }
    };
    var selectionFormCutted = function (project, selection, selected, road) {
      var firstLink = _.head(_.sortBy(_.filter(selected, function (s) {
        return s.endMValue !== 0;
      }), 'startAddressM'));
      var splitPoint = (applicationModel.getSelectedTool() === "Cut") ? firstLink.splitPoint : getSplitPointBySideCode(firstLink);
      return '<form id="roadAddressProjectFormCut" class="input-unit-combination split-form-group form-horizontal roadAddressProject">' +
        '<input type="hidden" id="splitx" value="' + splitPoint.x + '"/>' +
        '<input type="hidden" id="splity" value="' + splitPoint.y + '"/>' +
        suravagePartForm(selection[0], selected, 0) +
        suravagePartForm(selection[1], selected, 1) +
        formCommon.newRoadAddressInfo(project, selected, selectedProjectLink, road) +
        terminatedPartForm(selection[2], selected[2]) +
        '</form>';
    };

    var suravagePartForm = function (selection, selected, index) {
      if (selected[index].endAddressM === selected[index].startAddressM) {
        return '';
      } else {
        return '<label>SUUNNITELMALINKKI</label><span class="marker">' + selected[index].marker + '</span>' +
          '<br><label>Toimenpiteet,' + selection + '</label>' +
          dropdownOption(index) + '<hr class="horizontal-line"/>';
      }
    };

    var terminatedPartForm = function (selection, selected) {
      if (selected.endAddressM === selected.startAddressM) {
        return '';
      } else {
        return '<label>NYKYLINKKI</label><span class="marker">' + selected.marker + '</span>' +
          '<br><label>Toimenpiteet,' + selection + '</label>' +
          dropdownOption(2);
      }
    };

    var dropdownOption = function (index) {
      return '<div class="input-unit-combination">' +
        '<select class="split-form-control" id="splitDropDown_' + index + '" hidden size="1">' +
        '<option disabled id="drop_' + index + '_' + LinkStatus.Unchanged.description + '" value="' + LinkStatus.Unchanged.description + '"  hidden>Ennallaan</option>' +
        '<option disabled id="drop_' + index + '_' + LinkStatus.Transfer.description + '" value="' + LinkStatus.Transfer.description + '" hidden>Siirto</option>' +
        '<option id="drop_' + index + '_' + LinkStatus.New.description + '" value="' + LinkStatus.New.description + '" hidden>Uusi</option>' +
        '<option disabled id="drop_' + index + '_' + LinkStatus.Terminated.description + '" value="' + LinkStatus.Terminated.description + '" hidden>Lakkautus</option>' +
        '</select>' +
        '</div>';
    };

    var emptyTemplate = function (project) {
      return _.template('' +
        '<header style ="display:-webkit-inline-box;">' +
        formCommon.titleWithEditingTool(project) +
        '</header>' +
        '<footer>' + showProjectChangeButton() + '</footer>');
    };

    var isProjectPublishable = function () {
      return projectCollection.getPublishableStatus();
    };

    var isProjectEditable = function () {
      return _.includes(editableStatus, projectCollection.getCurrentProject().project.statusCode);
    };

    var changeDropDownValue = function (statusCode, triggerChange) {
      var fireChange = _.isUndefined(triggerChange) ? true : triggerChange;
      var link = _.head(_.filter(selectedProjectLink, function (l) {
        return !_.isUndefined(l.status) && l.status === statusCode;
      }));
      if (statusCode === LinkStatus.Unchanged.value) {
        $("#splitDropDown_0 option[value=" + LinkStatus.Transfer.description + "]").prop('disabled', false).prop('hidden', false);
        if (fireChange)
          $("#splitDropDown_0 option[value=" + LinkStatus.Unchanged.description + "]").attr('selected', 'selected').change();
        else $("#splitDropDown_0 option[value=" + LinkStatus.Unchanged.description + "]").attr('selected', 'selected');
      } else if (statusCode === LinkStatus.Transfer.value) {
        $("#splitDropDown_0 option[value=" + LinkStatus.Unchanged.description + "]").prop('disabled', false).prop('hidden', false);
        if (fireChange)
          $("#splitDropDown_0 option[value=" + LinkStatus.Transfer.description + "]").attr('selected', 'selected').change();
        else $("#splitDropDown_0 option[value=" + LinkStatus.Transfer.description + "]").attr('selected', 'selected');
      } else if (statusCode === LinkStatus.New.value) {
        $("#splitDropDown_1 option[value=" + LinkStatus.New.description + "]").prop('disabled', false).prop('hidden', false);
        if (fireChange)
          $("#splitDropDown_1 option[value=" + LinkStatus.New.description + "]").attr('selected', 'selected').change();
        else $("#splitDropDown_1 option[value=" + LinkStatus.New.description + "]").attr('selected', 'selected');
      } else if (statusCode === LinkStatus.Terminated.value) {
        $("#splitDropDown_2 option[value=" + LinkStatus.Terminated.description + "]").prop('disabled', false).prop('hidden', false);
        if (fireChange)
          $("#splitDropDown_2 option[value=" + LinkStatus.Terminated.description + "]").attr('selected', 'selected').change();
        else $("#splitDropDown_2 option[value=" + LinkStatus.Terminated.description + "]").attr('selected', 'selected');
      }
      // Set discontinuity from A/B unless it is continuous (defaults to Continuous if both are Continuous)
      if (!_.isUndefined(link.discontinuity) && link.discontinuity !== 5 && (link.marker === 'A' || link.marker === 'B'))
        $('#discontinuityDropdown').val(link.discontinuity);
      if (!_.isUndefined(link.marker) && (link.marker === 'A' || link.marker === 'B'))
        $('#administrativeClassDropdown').val(link.administrativeClassId);
    };

    var disableFormInputs = function () {
      if (!isProjectEditable()) {
        $('#roadAddressProjectForm select').prop('disabled', true);
        $('#roadAddressProjectFormCut select').prop('disabled', true);
        $('.btn-pencil-edit').prop('disabled', true);
      }
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      eventbus.on('projectLink:split', function (selected) {
        selectedProjectLink = selected;
        currentProject = projectCollection.getCurrentProject();
        formCommon.clearInformationContent();
        rootElement.html(selectedProjectLinkTemplate(currentProject.project, selectedProjectLink));
        formCommon.replaceAddressInfo(backend, selectedProjectLink);
        formCommon.checkInputs('.split-');
        formCommon.toggleAdditionalControls();
        _.each(selectedProjectLink, function (link) {
          changeDropDownValue(link.status, false);
        });
        disableFormInputs();
        $('#feature-attributes').find('.changeDirectionDiv').prop("hidden", false);
        $('#feature-attributes').find('.new-road-address').prop("hidden", false);
      });

      rootElement.on('keyup, input', '#roadName', function () {
        formCommon.checkInputs('.split-');
      });

      eventbus.on('roadAddress:projectFailed', function () {
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectLinksUpdateFailed', function (errorCode) {
        applicationModel.removeSpinner();
        if (errorCode === 400) {
          return new ModalConfirm("Päivitys epäonnistui puutteelisten tietojen takia. Ota yhteyttä järjestelmätukeen.");
        } else if (errorCode === 401) {
          return new ModalConfirm("Sinulla ei ole käyttöoikeutta muutoksen tekemiseen.");
        } else if (errorCode === 412) {
          return new ModalConfirm("Täyttämättömien vaatimusten takia siirtoa ei saatu tehtyä. Ota yhteyttä järjestelmätukeen.");
        } else if (errorCode === 500) {
          return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen virheen takia, ota yhteyttä järjestelmätukeen.");
        } else {
          return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen tuntemattoman virheen takia, ota yhteyttä järjestelmätukeen.");
        }
      });

      eventbus.on('roadAddress:projectSentFailed', function (error) {
        new ModalConfirm(error);
      });

      eventbus.on('projectLink:projectLinksSplitSuccess', function () {
        eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        selectedProjectLinkProperty.cleanIds();
      });

      eventbus.on('roadAddress:changeDirectionFailed', function (error) {
        new ModalConfirm(error);
      });

      rootElement.on('click', '.revertSplit', function () {
        projectCollection.removeProjectLinkSplit(projectCollection.getCurrentProject().project, selectedProjectLink);
      });

      eventbus.on('roadAddress:projectLinksSaveFailed', function (result) {
        if (applicationModel.getSelectedTool() === "Cut") {
          new ModalConfirm(result.toString());
        }
      });

      var saveChanges = function () {
        currentProject = projectCollection.getCurrentProject();
        //TODO revert dirtyness if others than ACTION_TERMINATE is choosen, because now after Lakkautus, the link(s) stay always in black color
        var splitDropDown0 = $('#splitDropDown_0')[0].value;
        var splitDropDown1 = $('#splitDropDown_1')[0].value;

        switch (splitDropDown0) {
          case LinkStatus.Revert.description: {
            var separated = _.partition(selectedProjectLink, function (_link) {
              return isReSplitMode;
            });
            if (separated[0].length > 0) {
              projectCollection.revertChangesRoadlink(separated[0]);
            }
            if (separated[1].length > 0) {
              selectedProjectLinkProperty.revertSuravage();
              projectCollection.removeProjectLinkSplit(separated[1]);
            }
            break;
          }
          default: {
            projectCollection.saveCutProjectLinks(projectCollection.getTmpDirty().concat(selectedProjectLink), splitDropDown0, splitDropDown1);
          }
        }
        selectedProjectLinkProperty.setDirty(false);
        rootElement.html(emptyTemplate(currentProject.project));
        formCommon.toggleAdditionalControls();
      };

      var cancelChanges = function () {
        selectedProjectLinkProperty.setDirty(false);
        var hasSplit = _.some(selectedProjectLinkProperty.getCurrent(), function (links) {
          return Object.prototype.hasOwnProperty.call(links, 'connectedLinkId');
        });
        if (projectCollection.isDirty() || hasSplit) {
          projectCollection.revertLinkStatus();
          projectCollection.setDirty([]);
          projectCollection.setTmpDirty([]);
          projectLinkLayer.clearHighlights();
          $('.wrapper').remove();
          eventbus.trigger('roadAddress:projectLinksEdited');
          eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
          eventbus.trigger('roadAddressProject:reOpenCurrent');
        } else {
          eventbus.trigger('roadAddress:openProject', projectCollection.getCurrentProject());
          eventbus.trigger('roadLinks:refreshView');
        }
      };

      rootElement.on('click', '.split-form button.update', function () {
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        saveChanges();
      });

      rootElement.on('change', '#roadAddressProjectFormCut #splitDropDown_0', function () {
        selectedProjectLinkProperty.setDirty(true);
        eventbus.trigger('roadAddressProject:toggleEditingRoad', false);
        var disabled = false;
        if (this.value === LinkStatus.Unchanged.description) {
          $("#splitDropDown_0 option[value=" + LinkStatus.Transfer.description + "]").prop('disabled', false).prop('hidden', false);
          disabled = true;
          $('#tie').val(currentSplitData.roadNumber);
          $('#osa').val(currentSplitData.roadPartNumber);
          $('#trackCodeDropdown').val(currentSplitData.trackCode);
        } else if (this.value === LinkStatus.Transfer.description) {
          $("#splitDropDown_0 option[value=" + LinkStatus.Unchanged.description + "]").prop('disabled', false).prop('hidden', false);
          disabled = false;
        }
        $('#tie').prop('disabled', disabled);
        $('#osa').prop('disabled', disabled);
        $('#trackCodeDropdown').prop('disabled', disabled);
        $('#discontinuityDropdown').prop('disabled', false);
        $('#administrativeClassDropdown').prop('disabled', false);
      });

      rootElement.on('change', '.split-form-group', function () {
        rootElement.find('.action-selected-field').prop("hidden", false);
      });

      rootElement.on('click', '.split-form button.cancelLink', function () {
        cancelChanges();
      });

      rootElement.on('click', '.split-form button.send', function () {
        projectCollection.publishProject();
      });

      rootElement.on('click', '.split-form button.show-changes', function () {
        $(this).empty();
        projectChangeTable.show();
        var projectChangesButton = showProjectChangeButton();
        if (isProjectPublishable() && isProjectEditable()) {
          formCommon.setInformationContent();
          $('footer').html(formCommon.sendRoadAddressChangeButton('split-', projectCollection.getCurrentProject()));
        } else
          $('footer').html(projectChangesButton);
      });

      rootElement.on('keyup change input', '.split-form-control.small-input', function () {
        formCommon.checkInputs('.split-');
      });

    };
    bindEvents();
  };
}(this));
