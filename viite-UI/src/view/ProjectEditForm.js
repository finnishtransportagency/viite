(function (root) {
  root.ProjectEditForm = function (map, projectCollection, selectedProjectLinkProperty, projectLinkLayer, projectChangeTable, backend) {
    var LinkStatus = ViiteEnumerations.LinkStatus;
    var CalibrationCode = ViiteEnumerations.CalibrationCode;
    var editableStatus = [ViiteEnumerations.ProjectStatus.Incomplete.value, ViiteEnumerations.ProjectStatus.Unknown.value];
    var ValidElys = _.map(ViiteEnumerations.ElyCodes, function (ely) {
      return ely;
    });
    var selectedProjectLink = false;
    var editedNameByUser = false;
    var LinkSources = ViiteEnumerations.LinkGeomSource;
    var ProjectStatus = ViiteEnumerations.ProjectStatus;
    var formCommon = new FormCommon('');

    var endDistanceOriginalValue = '--';


    var showProjectButtonsDisabled = function () {
      return '<div class="project-form form-controls">' +
          formCommon.projectButtonsDisabled() + '</div>';
    };

    var transitionModifiers = function (targetStatus, currentStatus) {
      var mod;
      if (_.includes(targetStatus.transitionFrom, currentStatus))
        mod = '';
      else
        mod = 'disabled hidden';
      if (currentStatus === targetStatus.value)
        return mod + ' selected';
      else
        return mod;
    };

    var defineOptionModifiers = function (option, selection) {
      var linkStatus = selection[0].status;
      var targetLinkStatus = _.find(LinkStatus, function (ls) {
        return ls.description === option || (option === '' && ls.value === 99);
      });
      return transitionModifiers(targetLinkStatus, linkStatus);
    };

    var selectedProjectLinkTemplate = function (project, selected, errorMessage) {
      var road = {
        roadNumber: selected[0].roadNumber,
        roadPartNumber: selected[0].roadPartNumber,
        trackCode: selected[0].trackCode
      };

      var roadLinkSources = _.chain(selected).map(function (s) {
        return s.roadLinkSource;
      }).uniq().map(function (a) {
        var linkGeom = _.find(LinkSources, function (source) {
          return source.value === parseInt(a);
        });
        if (_.isUndefined(linkGeom))
          return LinkSources.Unknown.descriptionFI;
        else
          return linkGeom.descriptionFI;
      }).uniq().join(", ").value();

      var selection = formCommon.selectedData(selected);
      return _.template('' +
        '<header>' +
        formCommon.title(project.name) +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<div class="edit-control-group project-choice-group">' +
        insertErrorMessage(errorMessage) +
        formCommon.staticField('Lisätty järjestelmään', project.createdBy + ' ' + project.startDate) +
        formCommon.staticField('Muokattu viimeksi', project.modifiedBy + ' ' + project.dateModified) +
        formCommon.staticField('Geometrian lähde', roadLinkSources) +
        showLinkId(selected) +
        showLinkLength(selected) +
        '<div class="form-group editable form-editable-roadAddressProject"> ' +

        selectionForm(project, selection, selected, road) +
        formCommon.changeDirection(selected, project) +
        formCommon.actionSelectedField() +
        '</div>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '<footer>' + formCommon.actionButtons('project-', projectCollection.isDirty()) + '</footer>');
    };

    var showLinkId = function (selected) {
      if (selected.length === 1) {
        return String(formCommon.staticField('Linkin ID', selected[0].linkId));
      } else {
        return '';
      }
    };

    var showLinkLength = function (selected) {
      if (selected.length === 1) {
        return String(formCommon.staticField('Geometrian pituus', Math.round(selected[0].endMValue - selected[0].startMValue)));
      } else {
        var combinedLength = 0;
        _.map(selected, function(roadLink){
          combinedLength += Math.round(roadLink.endMValue - roadLink.startMValue);
        });
        return '<div class="form-group-metadata">' +
            '<p class="form-control-static asset-log-info-metadata">Geometrioiden yhteenlaskettu pituus: ' + combinedLength + '</p>' +
            '</div>';
      }
    };

    var selectionForm = function (project, selection, selected, road) {
      var defaultOption = (selected[0].status === LinkStatus.NotHandled.value ? LinkStatus.NotHandled.description : LinkStatus.Undefined.description);
      return '<form id="roadAddressProjectForm" class="input-unit-combination form-group form-horizontal roadAddressProject">' +
        '<label>Toimenpiteet,' + selection + '</label>' +
        '<div class="input-unit-combination">' +
        '<select class="action-select" id="dropDown_0" size="1">' +
        '<option id="drop_0_" ' + defineOptionModifiers(defaultOption, selected) + '>Valitse</option>' +
        '<option id="drop_0_' + LinkStatus.Unchanged.description + '" value=' + LinkStatus.Unchanged.description + ' ' + defineOptionModifiers(LinkStatus.Unchanged.description, selected) + '>' + LinkStatus.Unchanged.displayText + '</option>' +
        '<option id="drop_0_' + LinkStatus.Transfer.description + '" value=' + LinkStatus.Transfer.description + ' ' + defineOptionModifiers(LinkStatus.Transfer.description, selected) + '>' + LinkStatus.Transfer.displayText + '</option>' +
        '<option id="drop_0_' + LinkStatus.New.description + '" value=' + LinkStatus.New.description + ' ' + defineOptionModifiers(LinkStatus.New.description, selected) + '>' + LinkStatus.New.displayText + '</option>' +
        '<option id="drop_0_' + LinkStatus.Terminated.description + '" value=' + LinkStatus.Terminated.description + ' ' + defineOptionModifiers(LinkStatus.Terminated.description, selected) + '>' + LinkStatus.Terminated.displayText + '</option>' +
        '<option id="drop_0_' + LinkStatus.Numbering.description + '" value=' + LinkStatus.Numbering.description + ' ' + defineOptionModifiers(LinkStatus.Numbering.description, selected) + '>' + LinkStatus.Numbering.displayText + '</option>' +
        '<option id="drop_0_' + LinkStatus.Revert.description + '" value=' + LinkStatus.Revert.description + ' ' + defineOptionModifiers(LinkStatus.Revert.description, selected) + '>' + LinkStatus.Revert.displayText +'</option>' +
        '</select>' +
        '</div>' +
        formCommon.newRoadAddressInfo(project, selected, selectedProjectLink, road) +
        '</form>';
    };

    var insertErrorMessage = function (errorMessage) {
      if (!_.isUndefined(errorMessage) && errorMessage !== "") {
        return addSmallLabelLowercase('VIRHE: ' + errorMessage);
      } else return "";
    };

    var addSmallLabelLowercase = function (label) {
      return '<label class="control-label-small" style="text-transform: none">' + label + '</label>';
    };

    var emptyTemplateDisabledButtons = function (project) {
      return _.template('' +
          '<header>' +
          formCommon.titleWithEditingTool(project) +
          '</header>' +
          '<div class="wrapper read-only">' +
          '<div class="form form-horizontal form-dark">' +
          '<label class="highlighted">JATKA VALITSEMALLA KOHDE KARTALTA.</label>' +
          '<div class="form-group" id="project-errors"></div>' +
          '</div></div></br></br>' +
          '<footer>' + showProjectButtonsDisabled() + '</footer>');
    };

    var isProjectPublishable = function () {
      return projectCollection.getPublishableStatus();
    };

    var isProjectEditable = function () {
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

      var administrativeClassCodeDropdown = $('#administrativeClassDropdown')[0];
      filled = filled && !_.isUndefined(administrativeClassCodeDropdown) && !_.isUndefined(administrativeClassCodeDropdown.value) && administrativeClassCodeDropdown.value !== '0'  && administrativeClassCodeDropdown.value !== '99';

      if (filled && !projectChangeTable.isChangeTableOpen()) {
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
          dropdown_0_new.prop('disabled', true);
          $("#dropDown_0 option[value=" + LinkStatus.Unchanged.description + "]").attr('selected', 'selected').change();
          break;
        case LinkStatus.New.value:
          dropdown_0_new.attr('selected', 'selected').change();
          projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
          rootElement.find('.new-road-address').prop("hidden", false);
          if (selectedProjectLink[0].id !== 0)
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
          break;
        case LinkStatus.Transfer.value:
          dropdown_0_new.prop('disabled', true);
          $("#dropDown_0 option[value=" + LinkStatus.Transfer.description + "]").attr('selected', 'selected').change();
          rootElement.find('.changeDirectionDiv').prop("hidden", true); // TODO remove this line when Velho integration can handle road reversing
          break;
        case LinkStatus.Numbering.value:
          $("#dropDown_0 option[value=" + LinkStatus.Numbering.description + "]").attr('selected', 'selected').change();
          break;
        default:
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
        $('#roadAddressProjectForm select').prop('disabled', true);
        $('#roadAddressProjectFormCut select').prop('disabled', true);
        $('.update').prop('disabled', true);
        $('.btn-pencil-edit').prop('disabled', true);
        if (projectCollection.getCurrentProject().project.statusCode === ProjectStatus.InUpdateQueue.value ||
            projectCollection.getCurrentProject().project.statusCode === ProjectStatus.UpdatingToRoadNetwork.value) {
          $(":input").prop('disabled', true);
          $(".project-form button.cancelLink").prop('disabled', false);
        }
      }
    };

    var setFormDirty = function () {
      selectedProjectLinkProperty.setDirty(true);
      eventbus.trigger('roadAddressProject:toggleEditingRoad', false);
    };

    var bindEvents = function () {

      var rootElement = $('#feature-attributes');

      eventbus.on('projectLink:clicked', function (selected) {
        selectedProjectLink = selected;
        var currentProject = projectCollection.getCurrentProject();
        formCommon.clearInformationContent();
        rootElement.html(selectedProjectLinkTemplate(currentProject.project, selectedProjectLink));
        formCommon.replaceAddressInfo(backend, selectedProjectLink, currentProject.project.id);
        updateForm();
        // disable form interactions (action dropdown, save and cancel buttons) if change table is open
        if (projectChangeTable.isChangeTableOpen()) {
          formCommon.disableFormInteractions();
        }
        _.defer(function () {
          $('#beginDistance').on("change", function (changedData) {
            eventbus.trigger('projectLink:editedBeginDistance', changedData.target.value);
          });
          $('#endDistance').on("change", function (changedData) {
            eventbus.trigger('projectLink:editedEndDistance', changedData.target.value);
          });
        });
      });

      function updateForm() {
        checkInputs();
        changeDropDownValue(selectedProjectLink[0].status);
        /* Disable numbering if the road part has any other status set. */
        if (selectedProjectLink[0].status !== LinkStatus.Numbering.value &&
            _.filter(projectCollection.getAll(), function (pl) {
                return pl.roadAddressRoadNumber === selectedProjectLink[0].roadNumber &&
                    pl.roadAddressRoadPart === selectedProjectLink[0].roadPartNumber &&
                    (pl.status !== LinkStatus.NotHandled.value && pl.status !== LinkStatus.Numbering.value);
            }).length !== 0) {
              removeNumberingFromDropdown();
        };
        disableFormInputs();
        const projectLinkMaxByEndAddressM = _.maxBy(selectedProjectLink, function (projectLink) {
              return projectLink.endAddressM;
          });
          // If there are non-calculated new links, display the lowest value of discontinuity in selection (i.e. the most significant).
        var selectedDiscontinuity;
        if (projectLinkMaxByEndAddressM.endAddressM === 0) {
            selectedDiscontinuity = _.minBy(selectedProjectLink, function (projectLink) {
                return projectLink.discontinuity;
            }).discontinuity;
        } else
            selectedDiscontinuity = projectLinkMaxByEndAddressM.discontinuity;
        $('#discontinuityDropdown').val(selectedDiscontinuity.toString());
      }

      eventbus.on('projectLink:errorClicked', function (selected, errorMessage) {
        selectedProjectLink = [selected[0]];
        var currentProject = projectCollection.getCurrentProject();
        formCommon.clearInformationContent();
        rootElement.html(selectedProjectLinkTemplate(currentProject.project, selectedProjectLink, errorMessage));
        formCommon.replaceAddressInfo(backend, selectedProjectLink);
        updateForm();
      });

      eventbus.on('roadAddress:projectFailed', function () {
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectLinksUpdateFailed', function (errorCode) {
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

      eventbus.on('roadAddress:projectLinksUpdated', function (response) {
        //eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        projectCollection.setDirty([]);
        selectedProjectLinkProperty.setCurrent([]);
        selectedProjectLinkProperty.setDirty(false);
        selectedProjectLink = false;
        selectedProjectLinkProperty.cleanIds();
        var projectErrors = response.projectErrors;
        // show disabled buttons
        rootElement.html(emptyTemplateDisabledButtons(projectCollection.getCurrentProject().project));
        if (Object.keys(projectErrors).length === 0) {
          // if no (high priority) validation errors are present, then show recalculate button and remove title
          formCommon.setDisabledAndTitleAttributesById("recalculate-button", false, "");
          formCommon.setInformationContent();
          formCommon.setInformationContentText("Päivitä etäisyyslukemat jatkaaksesi projektia.");
        } else {
          projectCollection.setAndWriteProjectErrorsToUser(projectErrors);
        }
        formCommon.toggleAdditionalControls();
        // changes made to project links, set recalculated flag to false
        eventbus.trigger('roadAddressProject:setRecalculatedAfterChangesFlag', false);
        applicationModel.removeSpinner();
        if (typeof response !== 'undefined' && typeof response.publishable !== 'undefined' && response.publishable) {
          eventbus.trigger('roadAddressProject:projectLinkSaved', response.id, response.publishable);
        } else {
          eventbus.trigger('roadAddressProject:projectLinkSaved');
        }
      });

      eventbus.on('roadAddress:projectSentSuccess', function () {
        new ModalConfirm("Muutoksia viedään tieosoiteverkolle.");
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

      eventbus.on('roadAddress:projectSentFailed', function (error) {
        new ModalConfirm(error);
      });

      eventbus.on('roadAddress:projectLinksCreateSuccess', function () {
        eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        selectedProjectLinkProperty.cleanIds();
        rootElement.find('.changeDirectionDiv').prop("hidden", false);
      });

      eventbus.on('roadAddress:changeDirectionFailed', function (error) {
        new ModalConfirm(error);
      });

      rootElement.on('click', '.changeDirection', function () {
        projectCollection.changeNewProjectLinkDirection(projectCollection.getCurrentProject().project.id, selectedProjectLinkProperty.get());
      });

      eventbus.on('roadAddress:projectLinksSaveFailed', function (result) {
        new ModalConfirm(result.toString());
      });

      eventbus.on('roadAddressProject:discardChanges', function () {
        cancelChanges();
      });

      var canChangeDirection = function () {
        if (_.isUndefined(_.find(selectedProjectLink, function (link) {
          return (link.status === LinkStatus.Terminated.value || link.status === LinkStatus.NotHandled.value);
        }))) {
          rootElement.find('.changeDirectionDiv').prop("hidden", false);
        } else {
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
        }
      };

      var saveChanges = function () {
        //TODO revert dirtyness if others than ACTION_TERMINATE is choosen, because now after Lakkautus, the link(s) stay always in black color
        var isValidEly = _.find(ValidElys, function (ely) {
          return ely.value === parseInt($('#ely')[0].value);
        });
        if (!isValidEly) {
          return new ModalConfirm("Tarkista antamasi ELY-koodi. Annettu arvo on virheellinen.");
        }

        var statusDropdown_0 = $('#dropDown_0').val();

        var objectDropdown_0 = _.find(LinkStatus, function (obj) {
          return obj.description === statusDropdown_0;
        });

        if (objectDropdown_0.value === LinkStatus.Revert.value) {
          projectCollection.revertChangesRoadlink(selectedProjectLink);
        }
        else {
          projectCollection.saveProjectLinks(
              _.isEmpty(projectCollection.getTmpDirty()) ? selectedProjectLink : projectCollection.getTmpDirty() // Case Terminated: getTmpDirty() is empty.
              , objectDropdown_0.value, _.isEmpty(projectCollection.getTmpDirty()) ? false : isEndDistanceTouched());
        }
        return true;
      };

        var isEndDistanceTouched = function () {
            const endDistance = $('#endDistance')[0];
            var changedValue;

            if (endDistance)
                changedValue = Number(endDistance.value);

            const orderedByStartM = _.sortBy(selectedProjectLink, function (l) {
                return -l.startAddressM;
            });

            // EndDistance is correct and changed.
            return !isNaN(changedValue) &&
                    typeof changedValue === 'number' &&
                    changedValue !== orderedByStartM[0].endAddressM;
        };

      var cancelChanges = function () {
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

      rootElement.on('change', '#endDistance', function (eventData) {
        setFormDirty();
        var changedValue = parseInt(eventData.target.value);
        if (!isNaN(changedValue) && !isNaN(parseInt(endDistanceOriginalValue)) && changedValue !== endDistanceOriginalValue)
          $('#manualCPWarning').css('display', 'inline-block');
        else $('#manualCPWarning').css('display', 'none');
      });

      rootElement.on('change', '#administrativeClassDropdown', function () {
        setFormDirty();
      });

        rootElement.on('change', '.form-select-control', function () {
            setFormDirty();
        });

      rootElement.on('click', '.project-form button.update', function () {
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        saveChanges();
      });

      rootElement.on('change', '#roadAddressProjectForm #dropDown_0', function () {
        $('#tie').prop('disabled', false);
        $('#osa').prop('disabled', false);
        $('#trackCodeDropdown').prop('disabled', false);
        $('#discontinuityDropdown').prop('disabled', false);
        $('#administrativeClassDropdown').prop('disabled', false);
        if (this.value === LinkStatus.Terminated.description) {
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
        } else if (this.value === LinkStatus.New.description) {
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
          if (selectedProjectLink[0].id !== -1) {
            fillDistanceValues(selectedProjectLink);
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
            rootElement.find('#distanceValue').prop("hidden", false);
          }
        } else if (this.value === LinkStatus.Unchanged.description) {
          rootElement.find('.new-road-address').prop("hidden", false);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          $('#tie').prop('disabled', true);
          $('#osa').prop('disabled', true);
          $('#trackCodeDropdown').prop('disabled', true);
          $('#discontinuityDropdown').prop('disabled', false);
          $('#administrativeClassDropdown').prop('disabled', false);
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
        } else if (this.value === LinkStatus.Transfer.description) {
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
        } else if (this.value === LinkStatus.Numbering.description) {
          new ModalConfirm("Numerointi koskee kokonaista tieosaa. Valintaasi on tarvittaessa laajennettu koko tieosalle.");
          $('#trackCodeDropdown').prop('disabled', true);
          $('#discontinuityDropdown').prop('disabled', false);
          $('#administrativeClassDropdown').prop('disabled', true);
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
        } else if (this.value === LinkStatus.Revert.description) {
          rootElement.find('.new-road-address').prop("hidden", true);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          rootElement.find('.project-form button.update').prop("disabled", false);
        }
      });

      rootElement.on('change', '#trackCodeDropdown', function () {
        checkInputs();
      });

      rootElement.on('change', '#administrativeClassDropdown', function () {
        checkInputs();
      });


      rootElement.on('change', '.form-group', function () {
        rootElement.find('.action-selected-field').prop("hidden", false);
      });

      rootElement.on('click', ' .project-form button.cancelLink', function () {
        cancelChanges();
      });

      rootElement.on('click', '.project-form button.send', function () {
        new GenericConfirmPopup("Haluatko hyväksyä projektin muutokset osaksi tieosoiteverkkoa?", {
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

      rootElement.on('click', '.project-form button.show-changes', function () {
        projectChangeTable.show();
        if (isProjectPublishable() && isProjectEditable()) {
          formCommon.setInformationContent();
          formCommon.setInformationContentText("Validointi ok. Voit tehdä tieosoitteen muutosilmoituksen tai jatkaa muokkauksia.");
        }
      });

      // when recalculate button is clicked
      rootElement.on('click', '.project-form button.recalculate', function () {
        // clear the information text
        $('#information-content').empty();
        // get current project
        var currentProject = projectCollection.getCurrentProject();
        // add spinner
        applicationModel.addSpinner();

        $('.validation-warning').remove();
        // fire backend call to recalculate and validate the current project with the project id
        backend.recalculateAndValidateProject(currentProject.project.id, function (response) {
          // if recalculation and validation did not throw exceptions in the backend
          if (response.success) {

              const trackGeometryLengthDeviationErrorCode = 38;
              if (response.validationErrors.filter((error) => error.errorCode === trackGeometryLengthDeviationErrorCode).length > 0) {
                  const trackGeometryLengthDeviationError = response.validationErrors.filter((error) => error.errorCode === trackGeometryLengthDeviationErrorCode)[0];
                      // "Ajoratojen geometriapituuksissa yli 20% poikkeama."
                  new GenericConfirmPopup(trackGeometryLengthDeviationError.errorMessage, {
                      type: "alert"
                  });
                  $('.form,.form-horizontal,.form-dark').append('<label class="validation-warning">' +trackGeometryLengthDeviationError.errorMessage + "<br> LinkId: " + trackGeometryLengthDeviationError.info + '</label>');
                  response.validationErrors = response.validationErrors.filter((error) => error.errorCode !== trackGeometryLengthDeviationErrorCode);
              }

            // set project errors that were returned by the backend validations and write them to user (removes the spinner also)
            projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);

            if (Object.keys(response.validationErrors).length === 0) {
              // if no validation errors are present, show changes button and remove title
              formCommon.setDisabledAndTitleAttributesById("changes-button", false, "");
            }
            // fetch the recalculated project links and redraw map
            projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, currentProject.project.id, projectCollection.getPublishableStatus());
            // disable recalculate button after recalculation is done
            formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "Etäisyyslukemat on päivitetty");
            // project was recalculated, set recalculated flag to true
            eventbus.trigger('roadAddressProject:setRecalculatedAfterChangesFlag', true);
          }
          // if something went wrong during recalculation or validation, show error to user
          else if (response.prototype.hasOwnProperty('validationErrors') && !_.isEmpty(response.validationErrors)) {
              // set project errors that were returned by the backend validations and write them to user (removes the spinner also)
              projectCollection.setAndWriteProjectErrorsToUser(response.validationErrors);
          } else {
            new ModalConfirm(response.errorMessage);
            applicationModel.removeSpinner();
          }
        });
      });

      rootElement.on('change input', '.form-control.small-input', function (event) {
        var dropdown_0 = $('#dropDown_0');
        var roadNameField = $('#roadName');
        checkInputs();
        setFormDirty();
        if (event.target.id === "tie" && (dropdown_0.val() === 'New' || dropdown_0.val() === 'Transfer' || dropdown_0.val() === 'Numbering')) {
          backend.getRoadName($(this).val(), projectCollection.getCurrentProject().project.id, function (data) {
            if (data.roadName) {
              editedNameByUser = false;
              roadNameField.val(data.roadName).change();
              if (data.isCurrent) {
                roadNameField.prop('disabled', true);
              } else {
                roadNameField.prop('disabled', false);
              }
              checkInputs();
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
        checkInputs();
        editedNameByUser = $('#roadName').val !== '';
      });

      // show project errors' link id list in a popup window
      rootElement.on('click', '.linkIdList', function (event) {
        const error = projectCollection.getProjectErrors()[event.currentTarget.id];
        if (error.linkIds.length > 0) {
          const linkIdsText = error.linkIds.join(', ');
          GenericConfirmPopup(linkIdsText, {type: "alert"});
        }
      });

      rootElement.on('click', '.projectErrorButton', function (event) {
        var error = projectCollection.getProjectErrors()[event.currentTarget.id];
        var roadPartErrors = [
          ViiteEnumerations.ProjectError.TerminationContinuity.value,
          ViiteEnumerations.ProjectError.DoubleEndOfRoad.value,
          ViiteEnumerations.ProjectError.RoadNotReserved.value
        ];
        if (_.includes(roadPartErrors, error.errorCode)) {
          var attributeElement = $('#feature-attributes');
          attributeElement.find('#editProjectSpan').click();
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
}(this));
