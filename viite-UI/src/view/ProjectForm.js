(function (root) {
  root.ProjectForm = function (map, projectCollection, selectedProjectLinkProperty, projectLinkLayer) {
    //TODO create uniq project model in ProjectCollection instead using N vars e.g.: project = {id, roads, parts, ely, startingLinkId, publishable, projectErrors}
    var currentProject = false;
    var currentPublishedNetworkDate;
    var formCommon = new FormCommon('');
    var ProjectStatus = LinkValues.ProjectStatus;
    var editableStatus = [ProjectStatus.Incomplete.value, ProjectStatus.ErrorInTR.value, ProjectStatus.Unknown.value];
    var staticField = function (labelText, dataField) {
      var field;
      field = '<div class="form-group">' +
        '<p class="form-control-static asset-log-info">' + labelText + ' : ' + dataField + '</p>' +
        '</div>';
      return field;
    };

    var largeInputField = function (dataField) {
      return '<div class="form-group">' +
        '<label class="control-label">LISÄTIEDOT</label>' +
        '<textarea class="form-control large-input roadAddressProject" id="lisatiedot" >' + (dataField === undefined || dataField === null ? "" : dataField ) + '</textarea>' +
        '</div>';
    };

    var inputFieldRequired = function (labelText, id, placeholder, value, maxLength) {
      var lengthLimit = '';
      if (maxLength)
        lengthLimit = 'maxlength="' + maxLength + '"';
      return '<div class="form-group input-required">' +
        '<label class="control-label required">' + labelText + '</label>' +
        '<input type="text" class="form-control" id = "' + id + '"' + lengthLimit + ' placeholder = "' + placeholder + '" value="' + value + '"/>' +
        '</div>';
    };

    var title = function (projectName) {
      const projectNameFixed = (projectName) ? projectName : "Uusi tieosoiteprojekti";
      return '<span class ="edit-mode-title">' + projectNameFixed + '</span>';
    };

    var actionButtons = function () {
      var html = '<div class="project-form form-controls" id="actionButtons">';
      if (currentProject.statusCode === ProjectStatus.Incomplete.value) {
        html += '<span id="deleteProjectSpan" class="deleteSpan">POISTA PROJEKTI <i id="deleteProject_' + currentProject.id + '" ' +
          'class="fas fa-trash-alt" value="' + currentProject.id + '"></i></span>';
      }
      html += '<button id="generalNext" class="save btn btn-save" style="width:auto;">Jatka toimenpiteisiin</button>' +
        '<button id="saveAndCancelDialogue" class="cancel btn btn-cancel">Poistu</button>' +
        '</div>';
      return html;
    };

    var newProjectTemplate = function () {
      return _.template('' +
        '<header>' +
        title() +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<div class="edit-control-group project-choice-group">' +
        staticField('Lisätty järjestelmään', '-') +
        staticField('Muokattu viimeksi', '-') +
        '<div class="form-group editable form-editable-roadAddressProject"> ' +
        '<form  id="roadAddressProject"  class="input-unit-combination form-group form-horizontal roadAddressProject">' +
        inputFieldRequired('*Nimi', 'nimi', '', '', 32) +
        inputFieldRequired('*Alkupvm', 'alkupvm', 'pp.kk.vvvv', '') +
        largeInputField() +
        '<div class="form-group">' +
        '<label class="control-label"></label>' +
        addSmallLabel('TIE') + addSmallLabel('AOSA') + addSmallLabel('LOSA') +
        '</div>' +
        '<div class="form-group">' +
        '<label class="control-label">Tieosat</label>' +
        addSmallInputNumber('tie', '', 5) + addSmallInputNumber('aosa', '', 3) + addSmallInputNumber('losa', '', 3) + addReserveButton() +
        '</div>' +
        '</form>' +
        '</div>' +
        '</div><div class = "form-result"><label >PROJEKTIIN VALITUT TIEOSAT:</label>' +
        '<div style="margin-left: 16px;">' +
        addSmallLabel('TIE') + addSmallLabel('OSA') + addSmallLabel('PITUUS') + addSmallLabel('JATKUU') + addSmallLabel('ELY') +
        '</div>' +
        '<div id ="reservedRoads">' +
        '</div></div>' +
        '</div></div>' +
        '<footer>' + actionButtons() + '</footer>');
    };

    var openProjectTemplate = function (project, publishedNetworkDate, reservedRoads, newReservedRoads) {
      return _.template('' +
        '<header>' +
        title(project.name) +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<div class="edit-control-group project-choice-group">' +
        staticField('VIITEn julkaisukelpoinen tieosoiteverkko', publishedNetworkDate ? publishedNetworkDate : '-') +
        staticField('Lisätty järjestelmään', project.createdBy + ' ' + project.startDate) +
        staticField('Muokattu viimeksi', project.modifiedBy + ' ' + project.dateModified) +
        '<div class="form-group editable form-editable-roadAddressProject"> ' +
        '<form id="roadAddressProject" class="input-unit-combination form-group form-horizontal roadAddressProject">' +
        inputFieldRequired('*Nimi', 'nimi', '', project.name, 32) +
        inputFieldRequired('*Alkupvm', 'alkupvm', 'pp.kk.vvvv', project.startDate) +
        largeInputField(project.additionalInfo) +
        '<div class="form-group">' +
        '<label class="control-label"></label>' +
        addSmallLabel('TIE') + addSmallLabel('AOSA') + addSmallLabel('LOSA') +
        '</div>' +
        '<div class="form-group">' +
        '<label class="control-label">Tieosat</label>' +
        addSmallInputNumber('tie', '', 5) + addSmallInputNumber('aosa', '', 3) + addSmallInputNumber('losa', '', 3) + addReserveButton() +
        '</div>' +
        '</form>' +
        '</div>' +
        '</div>' +
        '<div class = "form-result">' +
        '<label>PROJEKTIIN VARATUT TIEOSAT:</label>' +
        '<div style="margin-left: 16px;">' +
        addSmallLabel('TIE') + addSmallLabel('OSA') + addSmallLabel('PITUUS') + addSmallLabel('JATKUU') + addSmallLabel('ELY') +
        '</div>' +
        '<div id ="reservedRoads">' +
        reservedRoads +
        '</div></div></br></br>' +
        '<div class = "form-result">' +
        '<label>PROJEKTISSA MUODOSTETUT TIEOSAT:</label>' +
        '<div style="margin-left: 16px;">' +
        addSmallLabel('TIE') + addSmallLabel('OSA') + addSmallLabel('PITUUS') + addSmallLabel('JATKUU') + addSmallLabel('ELY') +
        '</div>' +
        '<div id ="newReservedRoads">' +
        newReservedRoads +
        '</div></div></div></div>' +
        '<footer>' + actionButtons() + '</footer>');
    };

    var selectedProjectLinkTemplate = function (project) {
      return _.template('' +
        '<header>' +
        title(project.name) +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<label class="highlighted">ALOITA VALITSEMALLA KOHDE KARTALTA.</label>' +
        '<div class="form-group" id="project-errors"></div>' +
        '</div></div></br></br>' +
        '<footer>' + showProjectChangeButton() + '</footer>');
    };

    var errorsList = function () {
      if (projectCollection.getProjectErrors().length > 0) {
        return '<label>TARKASTUSILMOITUKSET:</label>' +
          '<div id ="projectErrors">' +
          formCommon.getProjectErrors(projectCollection.getProjectErrors(), projectCollection.getAll(), projectCollection) +
          '</div>';
      }
      else
        return '';

    };

    var showProjectChangeButton = function () {
      return '<div class="project-form form-controls">' +
        '<button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
        '<button disabled id ="send-button" class="send btn btn-block btn-send">Lähetä muutosilmoitus Tierekisteriin</button></div>';
    };

    var addSmallLabel = function (label) {
      return '<label class="control-label-small">' + label + '</label>';
    };

    var addSmallLabelWithIds = function (label, id) {
      return '<label class="control-label-small" id=' + id + '>' + label + '</label>';
    };

    var addSmallInputNumber = function (id, value, maxLength) {
      //Validate only number characters on "onkeypress" including TAB and backspace
      var smallNumberInput = '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
        '" class="form-control small-input roadAddressProject" id="' + id + '" value="' + (_.isUndefined(value) ? '' : value ) + '"' +
        (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + ' onclick=""/>';
      return smallNumberInput;
    };

    var addDatePicker = function () {
      var $validFrom = $('#alkupvm');
      dateutil.addSingleDatePicker($validFrom);
    };

    var formIsInvalid = function (rootElement) {
      return !(rootElement.find('#nimi').val() && rootElement.find('#alkupvm').val() !== '');
    };

    var projDateEmpty = function (rootElement) {
      return !rootElement.find('#alkupvm').val();
    };

    var addReserveButton = function () {
      return '<button class="btn btn-reserve" disabled>Varaa</button>';
    };

    var bindEvents = function () {

      var rootElement = $('#feature-attributes');

      var removeReservedPart = function (roadNumber, roadPartNumber) {
        currentProject.isDirty = true;
        projectCollection.setReservedParts(_.filter(projectCollection.getReservedParts(), function (part) {
          return part.roadNumber.toString() !== roadNumber || part.roadPartNumber.toString() !== roadPartNumber;
        }));
        removeRenumberedPart(roadNumber, roadPartNumber);
        fillForm(projectCollection.getReservedParts(), projectCollection.getFormedParts());
      };

      var removeRenumberedPart = function (roadNumber, roadPartNumber) {
        /* All rows do not have roadAddresses record, so return value for this filter should handle that
         situation, so always return boolean, otherwise projectCollection.getFormedParts() will be cleared

         There is no way to stop or break a forEach() loop other than by throwing an exception. If you need such
         behavior, the forEach() method is the wrong tool
         developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Array/forEach
         */
        projectCollection.setFormedParts(_.filter(projectCollection.getFormedParts(), function (part) {
          var reNumberedPart = false;
          for (var i = 0; i < part.roadAddresses.length; ++i) {
            var ra = part.roadAddresses[i];
            reNumberedPart = (ra.roadAddressNumber.toString() === roadNumber.toString() &&
                ra.roadAddressPartNumber.toString() === roadPartNumber.toString()) && ra.isNumbering;
            if (reNumberedPart) {
              break;
            }
          }
          return !reNumberedPart;
        }));
      };

      var removeFormedPart = function (roadNumber, roadPartNumber) {
        currentProject.isDirty = true;
        _.each(projectCollection.getRoadAddressesFromFormedRoadPart(roadNumber, roadPartNumber), function (roadAddresses) {
          _.each(roadAddresses, function (ra) {
            removeFormedPart(ra.roadAddressNumber, ra.roadAddressPartNumber);
          });
        });
        projectCollection.setFormedParts(_.filter(projectCollection.getFormedParts(), function (part) {
          return part.roadNumber.toString() !== roadNumber || part.roadPartNumber.toString() !== roadPartNumber;
        }));
        fillForm(projectCollection.getReservedParts(), projectCollection.getFormedParts());
      };

      var updateReservedParts = function (currParts, newParts) {
        var reservedParts = $("#reservedRoads");
        var formedParts = $("#newReservedRoads");

        reservedParts.append(reservedParts.html(currParts));
        formedParts.append(formedParts.html(newParts));
      };

        var reservedHtmlList = function (list) {
            var text = '';
            var index = 0;
            _.each(list, function (line) {
                if (!_.isUndefined(line.currentLength)) {
                    text += '<div class="form-reserved-roads-list">' + projectCollection.getDeleteButton(index++, line.roadNumber, line.roadPartNumber, 'reservedList') +
                        addSmallLabel(line.roadNumber) +
                        addSmallLabelWithIds(line.roadPartNumber, 'reservedRoadPartNumber') +
                        addSmallLabelWithIds((line.currentLength), 'reservedRoadLength') +
                        addSmallLabelWithIds((line.currentDiscontinuity), 'reservedDiscontinuity') +
                        addSmallLabelWithIds((line.currentEly), 'reservedEly') +
                        '</div>';
                }
            });
            return text;
        };

      var formedHtmlList = function (list) {
        var text = '';
        var index = 0;
        _.each(list, function (line) {
          if (!_.isUndefined(line.newLength)) {
            text += '<div class="form-reserved-roads-list">' + projectCollection.getDeleteButton(index++, line.roadNumber, line.roadPartNumber, 'formedList') +
              addSmallLabel(line.roadNumber) +
              addSmallLabelWithIds(line.roadPartNumber, 'reservedRoadPartNumber') +
              addSmallLabelWithIds((line.newLength), 'reservedRoadLength') +
              addSmallLabelWithIds((line.newDiscontinuity), 'reservedDiscontinuity') +
              addSmallLabelWithIds((line.newEly), 'reservedEly') +
              '</div>';
          }
        });
        return text;
      };

      var toggleAdditionalControls = function () {
        rootElement.find('header').replaceWith('<header>' +
          formCommon.titleWithEditingTool(currentProject) +
          '</header>');
      };

      var createOrSaveProject = function () {
        applicationModel.addSpinner();
        var data = $('#roadAddressProject').get(0);
        if (_.isUndefined(currentProject) || currentProject.id === 0) {
          projectCollection.createProject(data, map.getView().getResolution());
        } else {
          projectCollection.saveProject(data, map.getView().getResolution());
        }
      };

      var deleteProject = function () {
        if (!_.isUndefined(currentProject) && currentProject.id !== 0) {
          projectCollection.deleteProject(currentProject.id);
        }
      };

      var saveChanges = function () {
        applicationModel.addSpinner();
        eventbus.once('roadAddress:projectSaved', function (result) {
          currentProject = result.project;
          currentPublishedNetworkDate = result.publishedNetworkDate;
          currentProject.isDirty = false;
          var text = '';
          var index = 0;
          projectCollection.setReservedParts(result.reservedInfo);
          _.each(result.reservedInfo, function (line) {
            var button = projectCollection.getDeleteButton(index++, line.roadNumber, line.roadPartNumber, 'reservedList');
            text += '<div class="form-reserved-roads-list">' + button +
              addSmallLabel(line.roadNumber) + addSmallLabel(line.roadPartNumber) + addSmallLabel(line.roadLength) + addSmallLabel(line.discontinuity) + addSmallLabel(line.ely) +
              '</div>';
          });
          rootElement.html(openProjectTemplate(currentProject, currentPublishedNetworkDate, text, ''));

          jQuery('.modal-overlay').remove();
          addDatePicker();
          if (!_.isUndefined(result.projectAddresses)) {
            eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId, result.project);
          }
          selectedProjectLinkProperty.setDirty(false);
          eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        });
        createOrSaveProject();
      };

      var nextStage = function () {
        applicationModel.addSpinner();
        currentProject.isDirty = false;
        jQuery('.modal-overlay').remove();
        eventbus.trigger('roadAddressProject:openProject', currentProject);
        rootElement.html(selectedProjectLinkTemplate(currentProject));
        _.defer(function () {
          applicationModel.selectLayer('roadAddressProject');
          toggleAdditionalControls();
        });
      };

      var createNewProject = function () {
        applicationModel.addSpinner();
        eventbus.once('roadAddress:projectSaved', function (result) {
          currentProject = result.project;
          currentProject.isDirty = false;
          jQuery('.modal-overlay').remove();
          if (!_.isUndefined(result.projectAddresses)) {
            eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId, result.project);
          }
          eventbus.trigger('roadAddressProject:openProject', result.project);
          rootElement.html(selectedProjectLinkTemplate(currentProject));
          _.defer(function () {
            applicationModel.selectLayer('roadAddressProject');
            toggleAdditionalControls();
            selectedProjectLinkProperty.setDirty(false);
            eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
          });
        });
        createOrSaveProject();
      };

      var fillForm = function (currParts, newParts) {
        updateReservedParts(reservedHtmlList(currParts), formedHtmlList(newParts));
        applicationModel.setProjectButton(true);
        applicationModel.setProjectFeature(currentProject.id);
        applicationModel.setOpenProject(true);
        rootElement.find('.btn-reserve').prop("disabled", false);
        rootElement.find('.btn-save').prop("disabled", false);
        rootElement.find('.btn-next').prop("disabled", false);
      };

      var disableFormInputs = function () {
        if (!isProjectEditable()) {
          $('#roadAddressProject :input').prop('disabled', true);
          $('.btn-reserve').prop('disabled', true);
          $('.btn-delete').prop('hidden', true);
        }
      };

      var disableAutoComplete = function () {
        $('[id=nimi]').attr('autocomplete', 'off');
        $('[id=alkupvm]').attr('autocomplete', 'off');
        $('[id=lisatiedot]').attr('autocomplete', 'off');
      };

      eventbus.on('roadAddress:newProject', function () {
        currentProject = {
          id: 0,
          isDirty: false
        };
        $("#roadAddressProject").html("");
        rootElement.html(newProjectTemplate());
        jQuery('.modal-overlay').remove();
        addDatePicker();
        applicationModel.setOpenProject(true);
        projectCollection.clearRoadAddressProjects();
        $('#generalNext').prop('disabled', true);
        disableAutoComplete();
      });

      eventbus.on('roadAddress:openProject', function (result) {
        currentProject = result.project;
        currentPublishedNetworkDate = result.publishedNetworkDate;
        projectCollection.setProjectErrors(result.projectErrors);
        currentProject.isDirty = false;
        projectCollection.clearRoadAddressProjects();
        disableAutoComplete();
        projectCollection.setCurrentProject(result);
        projectCollection.setReservedParts(result.reservedInfo);
        projectCollection.setFormedParts(result.formedInfo);
        var currentReserved = reservedHtmlList(projectCollection.getReservedParts());
        var newReserved = formedHtmlList(projectCollection.getFormedParts());
        rootElement.html(openProjectTemplate(currentProject, currentPublishedNetworkDate, currentReserved, newReserved));
        jQuery('#projectList').remove();
        if (!_.isUndefined(currentProject)) {
          eventbus.trigger('linkProperties:selectedProject', result.linkId, result.project);
          eventbus.trigger('roadAddressProject:deactivateAllSelections');
        }
        applicationModel.setProjectButton(true);
        applicationModel.setProjectFeature(currentProject.id);
        applicationModel.setOpenProject(true);
        disableFormInputs();
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectValidationFailed', function (result) {
        new ModalConfirm(result.toString());
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectValidationSucceed', function () {
        rootElement.find('#generalNext').prop("disabled", formIsInvalid(rootElement));
        $('#saveEdit:disabled').prop('disabled', formIsInvalid(rootElement));
        currentProject.isDirty = true;
        emptyFields(['tie', 'aosa', 'losa']);
      });

      eventbus.on('roadAddress:projectFailed', function () {
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddressProject:reOpenCurrent', function () {
        reOpenCurrent();
      });

      eventbus.on('roadAddressProject:writeProjectErrors', function () {
        $('#project-errors').html(errorsList());
        applicationModel.removeSpinner();
      });

      rootElement.on('click', '#editProjectSpan', currentProject, function () {
        applicationModel.setSelectedTool(LinkValues.Tool.Default.value);
        applicationModel.addSpinner();
        eventbus.trigger('projectChangeTable:hide');
        projectCollection.getProjectsWithLinksById(currentProject.id).then(function (result) {
          rootElement.empty();
          setTimeout(function () {
          }, 0);
          eventbus.trigger('roadAddress:openProject', result);
          if (applicationModel.isReadOnly()) {
            $('.edit-mode-btn:visible').click();
          }
          _.defer(function () {
            loadEditbuttons();
          });
        });
      });

      var loadEditbuttons = function () {
        $('#activeButtons').empty();
        var html = "";
        if (currentProject.statusCode === ProjectStatus.Incomplete.value) {
          html += '<span id="deleteProjectSpan" class="deleteSpan">POISTA PROJEKTI <i id="deleteProject_' + currentProject.id + '" ' +
            'class="fas fa-trash-alt" value="' + currentProject.id + '"></i></span>';
        }
        html += '<button id="saveEdit" class="save btn btn-save" disabled>Tallenna</button>' +
          '<button id="cancelEdit" class="cancel btn btn-cancel">Peruuta</button>';
        $('#actionButtons').html(html);
        eventbus.trigger("roadAddressProject:clearAndDisableInteractions");
      };

      var saveAndNext = function () {
        saveChanges();
        eventbus.once('roadAddress:projectSaved', function () {
          selectedProjectLinkProperty.setDirty(false);
          nextStage();
        });
      };

      var isProjectEditable = function () {
        return _.isUndefined(projectCollection.getCurrentProject()) ||
          _.includes(editableStatus, projectCollection.getCurrentProject().project.statusCode);
      };

      rootElement.on('click', '#generalNext', function () {
        if (currentProject.statusCode === ProjectStatus.ErrorInTR.value) {
          currentProject.statusCode = ProjectStatus.Incomplete.value;
          currentProject.statusDescription = ProjectStatus.Incomplete.description;
          saveAndNext();
        } else if (currentProject.isDirty ) {
          if (currentProject.id === 0) {
            createNewProject();
          } else {
            saveAndNext();
          }
        } else {
          nextStage();
        }
        if (!isProjectEditable()) {
          $('.btn-pencil-edit').prop('disabled', true);
        }
      });

      var textFieldChangeHandler = function (eventData) {
        if (currentProject) {
          currentProject.isDirty = true;
        }
        var textIsNonEmpty = $('#nimi').val() !== "" && $('#alkupvm').val() !== "";
        var nextAreDisabled = $('#generalNext').is(':disabled') || $('#saveEdit').is(':disabled');
        var reservedRemoved = !_.isUndefined(eventData) && eventData.removedReserved;

        if ((textIsNonEmpty || reservedRemoved) && nextAreDisabled) {
          $('#generalNext').prop('disabled', false);
          $('#saveEdit:disabled').prop('disabled', false);
          currentProject.isDirty = true;
        }
      };

      var reserveFieldChangeHandler = function(_eventData) {
          var textIsNonEmpty = $('#tie').val() !== "" && $('#aosa').val() !== ""  && $('#losa').val() !== "";
          var textIsAllNumbers = $.isNumeric($('#tie').val()) && $.isNumeric($('#aosa').val()) && $.isNumeric($('#losa').val());
          rootElement.find('#roadAddressProject button.btn-reserve').attr('disabled', projDateEmpty(rootElement) && textIsNonEmpty && textIsAllNumbers);
      };

      var emptyFields = function (fieldIds) {
        fieldIds.forEach(function (id) {
          $('#' + id).val('');
        });
      };

      rootElement.on('change', '#nimi', function () {
        textFieldChangeHandler();
      });
      rootElement.on('change', '#alkupvm', function () {
        textFieldChangeHandler();
      });
      rootElement.on('change', '#lisatiedot', function () {
        textFieldChangeHandler();
      });

      rootElement.on('change', '#tie', function () {
          reserveFieldChangeHandler();
      });
      rootElement.on('change', '#aosa', function () {
          reserveFieldChangeHandler();
      });
      rootElement.on('change', '#losa', function () {
          reserveFieldChangeHandler();
      });

      rootElement.on('click', '.btn-reserve', function () {
        var data;
        if ($('#roadAddressProject').get(0)) {
          data = $('#roadAddressProject').get(0);
        } else {
          data = $('#reservedRoads').get(0);
        }
        if (currentProject && currentProject.id) {
          data.projectId = currentProject.id;
        } else {
          data.projectId = 0;
        }
        projectCollection.checkIfReserved(data);
        return false;
      });

      rootElement.on('click', '.btn-delete.reservedList', function () {
        var id = this.id;
        var roadNumber = this.attributes.roadNumber.value;
        var roadPartNumber = this.attributes.roadPartNumber.value;

        if (isProjectEditable()) {
          if (currentProject && projectCollection.getReservedParts()[id]) {
            new GenericConfirmPopup('Haluatko varmasti poistaa tieosan varauksen ja \r\nsiihen mahdollisesti tehdyt tieosoitemuutokset?', {
              successCallback: function () {
                removeReservedPart(roadNumber, roadPartNumber);
                removeFormedPart(roadNumber, roadPartNumber);
                _.defer(function () {
                  textFieldChangeHandler({removedReserved: true});
                });
              }
            });
          } else {
            removeReservedPart(roadNumber, roadPartNumber);
            removeFormedPart(roadNumber, roadPartNumber);
          }
        }
      });

      rootElement.on('click', '.btn-delete.formedList', function () {
        var id = this.id;
        var roadNumber = this.attributes.roadNumber.value;
        var roadPartNumber = this.attributes.roadPartNumber.value;

        if (isProjectEditable()) {
          if (currentProject && projectCollection.getFormedParts()[id]) {
            new GenericConfirmPopup('Haluatko varmasti poistaa tieosan varauksen ja \r\nsiihen mahdollisesti tehdyt tieosoitemuutokset?', {
              successCallback: function () {
                removeFormedPart(roadNumber, roadPartNumber);
                _.defer(function () {
                  textFieldChangeHandler({removedReserved: true});
                });
              }
            });
          } else {
            removeFormedPart(roadNumber, roadPartNumber);
          }
        }
      });

      rootElement.on('change', '.form-group', function () {
        rootElement.find('.action-selected-field').prop("hidden", false);
      });


      var closeProjectMode = function (changeLayerMode, noSave) {
        eventbus.trigger('roadAddressProject:startAllInteractions');
        applicationModel.setOpenProject(false);
        eventbus.trigger('projectChangeTable:hide');
        rootElement.find('header').toggle();
        rootElement.find('.wrapper').toggle();
        rootElement.find('footer').toggle();
        projectCollection.clearRoadAddressProjects();
        projectCollection.clearProjectErrors();
        eventbus.trigger('layer:enableButtons', true);
        if (changeLayerMode) {
          applicationModel.selectLayer('linkProperty', true, noSave);
          eventbus.trigger('roadAddressProject:clearOnClose');
          projectLinkLayer.hide();
        }
        applicationModel.removeSpinner();
      };

      var displayDeleteConfirmMessage = function (popupMessage) {
        new GenericConfirmPopup(popupMessage, {
          successCallback: function () {
            deleteProject();
            closeProjectMode(true);
          },
          closeCallback: function () {
            closeProjectMode(true);
          }
        });
      };

      var cancelChanges = function () {
        projectCollection.revertLinkStatus();
        projectCollection.setDirty([]);
        projectCollection.setTmpDirty([]);
        projectLinkLayer.clearHighlights();
        $('.wrapper').remove();
        eventbus.trigger('roadAddress:projectLinksEdited');
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        eventbus.trigger('roadAddressProject:reOpenCurrent');
      };

      var reOpenCurrent = function () {
        rootElement.empty();
        selectedProjectLinkProperty.setDirty(false);
        nextStage();
        toggleAdditionalControls();
        eventbus.trigger('roadAddressProject:enableInteractions');
      };

      rootElement.on('click', '#saveEdit', function () {
        saveAndNext();
        eventbus.trigger('roadAddressProject:enableInteractions');
        eventbus.trigger("roadAddressProject:startAllInteractions");
      });

      rootElement.on('click', '#cancelEdit', function () {
        if (currentProject.isDirty) {
          new GenericConfirmPopup('Haluatko tallentaa tekemäsi muutokset?', {
            successCallback: function () {
              saveAndNext();
              eventbus.trigger('roadAddressProject:enableInteractions');
            },
            closeCallback: function () {
              cancelChanges();
            }
          });
        } else {
          cancelChanges();
        }
        eventbus.trigger('roadAddressProject:startAllInteractions');
      });

      rootElement.on('click', '#saveAndCancelDialogue', function (_eventData) {
        if (currentProject.isDirty) {
          new GenericConfirmPopup('Haluatko tallentaa tekemäsi muutokset?', {
            successCallback: function () {
                eventbus.once('roadAddress:projectSaved', function () {
                  _.defer(function () {
                    closeProjectMode(true);
                  });
                });
                createOrSaveProject();
            },
            closeCallback: function () {
              closeProjectMode(true);
            }
          });
        } else {
          closeProjectMode(true);
        }
      });

      rootElement.on('click', '#closeProjectSpan', function () {
        closeProjectMode(true);
      });

      rootElement.on('click', '#deleteProjectSpan', function(){
        displayDeleteConfirmMessage("Haluatko varmasti poistaa tämän projektin?");
      });

      rootElement.on('change', '.input-required', function () {
        rootElement.find('.project-form button.next').attr('disabled', formIsInvalid(rootElement));
        rootElement.find('.project-form button.save').attr('disabled', formIsInvalid(rootElement));
        rootElement.find('#roadAddressProject button.btn-reserve').attr('disabled', projDateEmpty(rootElement));
      });
    };
    bindEvents();
  };
}(this));
