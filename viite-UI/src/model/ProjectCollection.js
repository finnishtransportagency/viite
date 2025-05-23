(function (root) {
  root.ProjectCollection = function (backend, startupParameters) {
    var me = this;
    // eslint-disable-next-line no-unused-vars
    var roadAddressProjects = [];
    var projectErrors = [];
    var reservedParts = [];
    var formedParts = [];
    var coordinateButtons = [];
    var projectInfo;
    var currentProject;
    var fetchedProjectLinks = [];
    var dirtyProjectLinkIds = [];
    var dirtyProjectLinks = [];
    var publishableProject = false;
    var RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    var ProjectStatus = ViiteEnumerations.ProjectStatus;
    var Track = ViiteEnumerations.Track;
    var BAD_REQUEST_400 = 400;
    var PRECONDITION_FAILED_412 = 412;
    var INTERNAL_SERVER_ERROR_500 = 500;
    var ALLOWED_ADDR_M_VALUE_PERCENTAGE = 0.2;
    var editedEndDistance = false;
    var editedBeginDistance = false;

    var resetEditedDistance = function () {
      editedEndDistance = false;
      editedBeginDistance = false;
    };

    var projectLinks = function () {
      return _.flatten(fetchedProjectLinks);
    };

    this.getProjectLinks = function () {
      return backend.getProjectLinksById(currentProject.project.id);
    };

    this.getAll = function () {
      return _.map(projectLinks(), function (projectLink) {
        return projectLink.getData();
      });
    };

    this.reset = function () {
      fetchedProjectLinks = [];
    };

    this.getMultiProjectLinks = function (id) {
      var chain = _.find(fetchedProjectLinks, function (linkChain) {
        var pureChain = _.map(linkChain, function (l) {
          return l.getData();
        });
        return _.some(pureChain, {"id": id}) || _.some(pureChain, {"linkId": id});
      });
      return _.map(chain, function (link) {
        if (link.getData().id > 0) {
          return link.getData().id;
        } else {
          return link.getData().linkId;
        }
      });
    };

    this.getProjectLink = function (ids) {
      return _.filter(projectLinks(), function (projectLink) {
        if (projectLink.getData().id > 0) {
          return _.includes(ids, projectLink.getData().id);
        } else {
          return _.includes(ids, projectLink.getData().linkId);
        }
      });
    };

    this.fetch = function (boundingBox, zoom, projectId, isPublishable) {
      var id = projectId;
      if (typeof id === 'undefined' && typeof projectInfo !== 'undefined')
        id = projectInfo.id;
      if (id) {
        backend.abortGettingRoadLinks();
      }
      backend.getProjectLinks({boundingBox: boundingBox, zoom: zoom, projectId: id}, function (fetchedLinks) {
        fetchedProjectLinks = _.map(fetchedLinks, function (projectLinkGroup) {
          return _.map(projectLinkGroup, function (projectLink) {
            return new ProjectLinkModel(projectLink);
          });
        });
        publishableProject = isPublishable;

        eventbus.trigger('roadAddressProject:fetched');
        applicationModel.removeSpinner();
      });
    };

    this.getProjects = function (onlyActive) {
      return backend.getRoadAddressProjects(onlyActive, function (projects) {
        roadAddressProjects = projects;
        eventbus.trigger('roadAddressProjects:fetched', projects);
      });
    };

    this.getProjectStates = function (projectIDs) {
      if (projectIDs.length > 0)
        return backend.getRoadAddressProjectStates(projectIDs, function (projects) {
          eventbus.trigger('roadAddressProjectStates:fetched', projects);
        });
      else
        return null;
    };

    this.getProjectsWithLinksById = function (projectId) {
      return backend.getProjectsWithLinksById(projectId, function (result) {
        roadAddressProjects = result.project;
        currentProject = result;
        projectInfo = {
          id: result.project.id,
          publishable: result.publishable
        };
        me.setAndWriteProjectErrorsToUser(result.projectErrors);
        me.setReservedParts(result.reservedInfo);
        me.setFormedParts(result.formedInfo);
        publishableProject = result.publishable;
        eventbus.trigger('roadAddressProject:projectFetched', projectInfo);
        eventbus.trigger('roadAddressProject:setRecalculatedAfterChangesFlag', false);
      });
    };

    this.revertRoadAddressChangeType = function () {
      resetEditedDistance();
      var fetchedLinks = this.getAll();
      dirtyProjectLinkIds.forEach(function (dirtyLink) {
        _.filter(fetchedLinks, {linkId: dirtyLink.id}).forEach(function (fetchedLink) {
          fetchedLink.status = dirtyLink.status;
        });
      });
    };

    this.clearRoadAddressProjects = function () {
      roadAddressProjects = [];
      fetchedProjectLinks = [];
      reservedParts = [];
      formedParts = [];
      dirtyProjectLinkIds = [];
      dirtyProjectLinks = [];
      currentProject = undefined;
      projectInfo = undefined;
      backend.abortLoadingProject();
    };

    this.saveProject = function (data, resolution) {
      var projectId = 0;
      if (projectInfo !== undefined) {
        projectId = projectInfo.id;
      } else if (currentProject !== undefined && currentProject.project.id !== undefined) {
        projectId = currentProject.project.id;
      }
      var dataJson = {
        id: projectId,
        projectEly: currentProject.project.ely,
        status: currentProject.project.statusCode,
        name: data[0].value,
        startDate: data[1].value,
        additionalInfo: data[2].value,
        reservedPartList: _.map(_.filter(me.getReservedParts(), function (part) {
          return !_.isUndefined(part.currentLength, part.currentEly);
        }), function (part) {
          return {
            discontinuity: (part.currentDiscontinuity),
            ely: (part.currentEly),
            roadLength: (part.currentLength),
            roadNumber: part.roadNumber,
            roadPartId: 0,
            roadPartNumber: part.roadPartNumber,
            startingLinkId: part.startingLinkId
          };
        }),
        formedPartList: _.map(_.filter(me.getFormedParts(), function (part) {
          return !_.isUndefined(part.newLength, part.newEly);
        }), function (part) {
          return {
            discontinuity: (part.newDiscontinuity),
            ely: (part.newEly),
            roadLength: (part.newLength),
            roadNumber: part.roadNumber,
            roadPartId: 0,
            roadPartNumber: part.roadPartNumber,
            startingLinkId: part.startingLinkId
          };
        }),
        resolution: resolution
      };

      backend.saveRoadAddressProject(dataJson, function (result) {
        if (result.success) {
          projectInfo = {
            id: result.project.id,
            additionalInfo: result.project.additionalInfo,
            status: result.project.status,
            startDate: result.project.startDate,
            publishable: false
          };
          currentProject = result;
          me.setAndWriteProjectErrorsToUser(result.projectErrors);
          me.setReservedParts(result.reservedInfo);
          me.setFormedParts(result.formedInfo);
          eventbus.trigger('roadAddress:projectSaved', result);
        } else {
          eventbus.trigger('roadAddress:projectValidationFailed', result.errorMessage);
        }
      }, function () {
        eventbus.trigger('roadAddress:projectFailed');
      });
    };

    this.revertChangesRoadlink = function (links) {
      if (!_.isEmpty(links)) {
        applicationModel.addSpinner();
        var coordinates = applicationModel.getUserGeoLocation();
        var data = {
          'projectId': currentProject.project.id,
          'roadNumber': links[0].roadNumber,
          'roadPartNumber': links[0].roadPartNumber,
          'links': _.map(links, function (link) {
            return {'id': link.id, 'linkId': link.linkId, 'status': link.status};
          }),
          'coordinates': coordinates
        };
        backend.revertChangesRoadlink(data, function (response) {
          if (response.success) {
            dirtyProjectLinkIds = [];
            publishableProject = response.publishable;
            me.setAndWriteProjectErrorsToUser(response.projectErrors);
            me.setFormedParts(response.formedInfo);
            eventbus.trigger('projectLink:revertedChanges', response);
          } else {
            if (response.status === INTERNAL_SERVER_ERROR_500 || response.status === BAD_REQUEST_400) {
              eventbus.trigger('roadAddress:projectLinksUpdateFailed', response.status);
            }
            new ModalConfirm(response.errorMessage);
            applicationModel.removeSpinner();
          }
        });
      }
    };

    var createOrUpdate = function (dataJson) {
      if ((!_.isEmpty(dataJson.linkIds) || !_.isEmpty(dataJson.ids)) && typeof dataJson.projectId !== 'undefined' && dataJson.projectId !== 0) {
        if (dataJson.roadNumber !== 0 && dataJson.roadPartNumber !== 0) {
          applicationModel.addSpinner();
          resetEditedDistance();
          var ids = dataJson.ids;
          if (dataJson.roadAddressChangeType === RoadAddressChangeType.New.value && ids.length === 0) {
            backend.createProjectLinks(dataJson, function (successObject) {
              if (successObject.success) {
                publishableProject = successObject.publishable;
                me.setAndWriteProjectErrorsToUser(successObject.projectErrors);
                me.setFormedParts(successObject.formedInfo);
                eventbus.trigger('projectLink:projectLinksCreateSuccess');
                eventbus.trigger('roadAddress:projectLinksUpdated', successObject);
                if (successObject.errorMessage) {
                  new ModalConfirm(successObject.errorMessage);
                }
              } else {
                new ModalConfirm(successObject.errorMessage);
                applicationModel.removeSpinner();
              }
            });
          } else {
            backend.updateProjectLinks(dataJson, function (successObject) {
              if (successObject.success) {
                publishableProject = successObject.publishable;
                me.setAndWriteProjectErrorsToUser(successObject.projectErrors);
                me.setFormedParts(successObject.formedInfo);
                eventbus.trigger('roadAddress:projectLinksUpdated', successObject);
              } else {
                new ModalConfirm(successObject.errorMessage);
                applicationModel.removeSpinner();
              }
            });
          }
        } else {
          eventbus.trigger('roadAddress:projectValidationFailed', "Virheellinen tieosanumero");
        }
      } else {
        eventbus.trigger('roadAddress:projectLinksUpdateFailed', PRECONDITION_FAILED_412);
      }
    };

    this.saveProjectLinks = function (changedLinks, statusCode, touchedEndDistance) {
      var validUserGivenAddrMValues = function (linkId, userEndAddr) {
        if (!_.isUndefined(userEndAddr) && userEndAddr !== null) {
          var roadPartIds = me.getMultiProjectLinks(linkId);
          var roadPartLinks = me.getProjectLink(_.map(roadPartIds, function (road) {
            return road;
          }));
          var startAddrFromChangedLinks = _.minBy(_.map(roadPartLinks, function (link) {
            return link.getData().addrMRange.start;
          }));
          var userDiffFromChangedLinks = userEndAddr - startAddrFromChangedLinks;
          var roadPartGeometries = _.map(roadPartLinks, function (roadPart) {
            return roadPart.getData().points;
          });
          var roadPartLength = _.reduce((roadPartGeometries), function (length, geom) {
            return GeometryUtils.geometryLength(geom) + length;
          }, 0.0);
          return (userDiffFromChangedLinks >= (roadPartLength * (1 - ALLOWED_ADDR_M_VALUE_PERCENTAGE))) && (userDiffFromChangedLinks <= (roadPartLength * (1 + ALLOWED_ADDR_M_VALUE_PERCENTAGE)));
        } else {
          return true;
        }
      };
      var newAndOtherLinks = _.partition(changedLinks, function (l) {
        return l.id === 0;
      });
      var newLinks = newAndOtherLinks[0];
      var otherLinks = newAndOtherLinks[1];

      var linkIds = _.uniq(_.map(newLinks, function (t) {
        if (t.linkId)
          return t.linkId;
        else
          return 0;
      }));

      var ids = _.uniq(_.map(otherLinks, function (t) {
        if (t.id)
          return t.id;
        else
          return 0;
      }));

      var projectId = projectInfo.id;
      var coordinates = applicationModel.getUserGeoLocation();
      var roadAddressProjectForm = $('#roadAddressProjectForm');
      var endDistance = $('#endDistance')[0];
      const hasDevRights = _.includes(startupParameters.roles, 'dev');

      const getValueWithId = function(id) {
        const element = roadAddressProjectForm.find(id)[0];
        return element && element.value ? Number(element.value) : null;
      };

      const startAddrMValue = getValueWithId('#addrStart');
      const endAddrMValue = getValueWithId('#addrEnd');
      const origStartAddrMValue = getValueWithId('#origAddrStart');
      const origEndAddrMValue = getValueWithId('#origAddrEnd');
      const startCp = getValueWithId('#startCPDropdown');
      const endCp = getValueWithId('#endCPDropdown');
      const sideCode = getValueWithId('#sideCodeDropdown');
      const generateNewRoadwayNumber = roadAddressProjectForm.find('#newRoadwayNumber')[0]
          ? roadAddressProjectForm.find('#newRoadwayNumber')[0].checked
          : null;

      let devToolData = null;
      if (hasDevRights) {
        devToolData = {
          startAddrMValue: startAddrMValue,
          endAddrMValue: endAddrMValue,
          originalStartAddrMValue: origStartAddrMValue,
          originalEndAddrMValue: origEndAddrMValue,
          startCp: startCp,
          endCp: endCp,
          generateNewRoadwayNumber: generateNewRoadwayNumber,
          editedSideCode: sideCode
        };
      }

      var reversed = _.chain(changedLinks).map(function (c) {
        return c.reversed;
      }).reduceRight(function (a, b) {
        return a || b;
      }).value();
      let userDefinedEndAddressM = null;
      if (endDistance && touchedEndDistance) userDefinedEndAddressM = (isNaN(Number(endDistance.value)) ? null : Number(endDistance.value));
      var dataJson = {
        ids: ids,
        linkIds: linkIds,
        roadAddressChangeType: statusCode,
        projectId: projectId,
        roadNumber: Number(roadAddressProjectForm.find('#tie')[0].value),
        roadPartNumber: Number(roadAddressProjectForm.find('#osa')[0].value),
        trackCode: Number(roadAddressProjectForm.find('#trackCodeDropdown')[0].value),
        discontinuity: Number(roadAddressProjectForm.find('#discontinuityDropdown')[0].value),
        roadEly: Number(roadAddressProjectForm.find('#ely')[0].value),
        roadLinkSource: Number(_.head(changedLinks).roadLinkSource),
        administrativeClass: Number(roadAddressProjectForm.find('#administrativeClassDropdown')[0].value),
        userDefinedEndAddressM: userDefinedEndAddressM,
        coordinates: coordinates,
        roadName: roadAddressProjectForm.find('#roadName')[0].value,
        reversed: reversed,
        devToolData: devToolData
      };
      if (dataJson.trackCode === Track.Unknown.value) {
        new ModalConfirm("Tarkista ajoratakoodi");
        applicationModel.removeSpinner();
      }

      var changedLink = _.chain(changedLinks).uniq().sortBy(function (cl) {
        return cl.endAddressM;
      }).last().value();
      var isNewRoad = changedLink.status === RoadAddressChangeType.New.value;

      var validUserEndAddress = !validUserGivenAddrMValues(_.head(dataJson.ids || dataJson.linkIds), dataJson.userDefinedEndAddressM);
      if (isNewRoad && (editedEndDistance || editedBeginDistance) && validUserEndAddress) {
        new GenericConfirmPopup("Antamasi pituus eroaa yli 20% prosenttia geometrian pituudesta, haluatko varmasti tallentaa tämän pituuden?", {
          successCallback: function () {
            createOrUpdate(dataJson);
          },
          closeCallback: function () {
            applicationModel.removeSpinner();
          }
        });
      } else {
        createOrUpdate(dataJson);
      }
    };

    this.createProject = function (data, resolution) {
      var roadPartList = _.map(reservedParts, function (part) {
        return {
          roadNumber: part.roadNumber,
          roadPartNumber: part.roadPartNumber,
          ely: (part.newEly ? part.newEly : part.currentEly)
        };
      });

      var dataJson = {
        id: 0,
        status: 1,
        name: data[0].value,
        startDate: data[1].value,
        additionalInfo: data[2].value,
        reservedPartList: roadPartList,
        resolution: resolution
      };

      backend.createRoadAddressProject(dataJson, function (result) {
        if (result.success) {
          projectInfo = {
            id: result.project.id,
            additionalInfo: result.project.additionalInfo,
            status: result.project.status,
            startDate: result.project.startDate,
            publishable: false
          };
          eventbus.trigger('roadAddress:projectSaved', result);
        } else {
          eventbus.trigger('roadAddress:projectValidationFailed', result.errorMessage);
        }
      }, function () {
        eventbus.trigger('roadAddress:projectFailed');
      });
    };

    this.deleteProject = function (projectId) {
      backend.deleteRoadAddressProject(projectId, function (result) {
        if (result.success) {
          currentProject = undefined;
        } else {
          eventbus.trigger('roadAddress:projectDeleteFailed', result.errorMessage);
        }
      }, function () {
        eventbus.trigger('roadAddress:projectFailed');
      });
    };

    this.changeNewProjectLinkDirection = function (projectId, selectedLinks) {
      applicationModel.addSpinner();
      var links = _.filter(selectedLinks, function (link) {
        return link.status !== RoadAddressChangeType.Terminated.value;
      });
      var coordinates = applicationModel.getUserGeoLocation();
      var dataJson = {
        projectId: projectId,
        roadNumber: selectedLinks[0].roadNumber,
        roadPartNumber: selectedLinks[0].roadPartNumber,
        links: links,
        coordinates: coordinates
      };
      resetEditedDistance();
      backend.directionChangeNewRoadlink(dataJson, function (successObject) {
        if (successObject.success) {
          me.setAndWriteProjectErrorsToUser(successObject.projectErrors);
          eventbus.trigger('changeProjectDirection:clicked');
        } else {
          eventbus.trigger('roadAddress:changeDirectionFailed', successObject.errorMessage);
          applicationModel.removeSpinner();
        }
      });
    };

    this.publishProject = function () {
      backend.sendProjectChangesToViite(
        projectInfo.id,
        function (result) {
          if (result.sendSuccess) { eventbus.trigger('roadAddress:projectSentSuccess');
          } else                  { eventbus.trigger('roadAddress:projectSentFailed', result.errorMessage);
          }
        },
        function (result) {
          eventbus.trigger('roadAddress:projectSentFailed', result.status);
        }
      );
    };

    var addSmallLabelWithIds = function (label, id) {
      return '<label class="control-label-small" id=' + id + '>' + label + '</label>';
    };

    var updateReservedRoads = function (newInfo) {
      var reservedRoads = $("#reservedRoads");
      reservedRoads.append(reservedRoads.html(newInfo));
    };

    var parseRoadPartInfoToResultRow = function () {
      var listContent = '';
      var index = 0;
      _.each(me.getReservedParts(), function (row) {
          var button = deleteButton(index++, row.roadNumber, row.roadPartNumber, 'reservedList');
          listContent += '<div class="form-reserved-roads-list">' + button +
            addSmallLabelWithIds(row.roadNumber, 'reservedRoadNumber') +
            addSmallLabelWithIds(row.roadPartNumber, 'reservedRoadPartNumber') +
            addSmallLabelWithIds((row.newLength ? row.newLength : row.currentLength), 'reservedRoadLength') +
            addSmallLabelWithIds((row.newDiscontinuity ? row.newDiscontinuity : row.currentDiscontinuity), 'reservedDiscontinuity') +
            addSmallLabelWithIds((row.newEly ? row.newEly : row.currentEly), 'reservedEly') + '</div>';
        }
      );
      return listContent;
    };

    this.getDeleteButton = function (index, roadNumber, roadPartNumber, selector) {
      return deleteButton(index, roadNumber, roadPartNumber, selector);
    };

    var deleteButton = function (index, roadNumber, roadPartNumber, selector) {
      var disabledInput = !_.isUndefined(currentProject) &&
          (currentProject.project.statusCode === ProjectStatus.InUpdateQueue.value ||
              currentProject.project.statusCode === ProjectStatus.UpdatingToRoadNetwork.value);
      return '<i roadNumber="' + roadNumber + '" roadPartNumber="' + roadPartNumber + '" id="' + index + '" class="delete btn-delete ' + selector + ' fas fa-trash-alt fa-lg" ' + (disabledInput ? 'disabled' : '') + '></i>';
    };


    var addToReservedPartList = function (queryResult) {
      var qRoadParts = [];
      _.each(queryResult.reservedInfo, function (row) {
        qRoadParts.push(row);
      });

      var sameElements = arrayIntersection(qRoadParts, reservedParts, function (arrayarow, arraybrow) {
        return arrayarow.roadNumber === arraybrow.roadNumber && arrayarow.roadPartNumber === arraybrow.roadPartNumber;
      });
      _.each(sameElements, function (row) {
        _.remove(qRoadParts, row);
      });
      _.each(qRoadParts, function (row) {
        reservedParts.push(row);
      });
    };

    this.setDirty = function (editedRoadLinks) {
      dirtyProjectLinkIds = editedRoadLinks;
      eventbus.trigger('roadAddress:projectLinksEdited');
    };

    this.getDirty = function () {
      return dirtyProjectLinkIds;
    };

    this.getReservedParts = function () {
      return reservedParts;
    };

    this.getFormedParts = function () {
      return formedParts;
    };

    this.getRoadAddressesFromFormedRoadPart = function (roadNumber, roadPartNumber) {
      return _.map(_.filter(formedParts, function (part) {
        return part.roadNumber.toString() === roadNumber && part.roadPartNumber.toString() === roadPartNumber;
      }), "roadAddresses");
    };

    this.setReservedParts = function (list) {
      reservedParts = list;
    };

    this.setFormedParts = function (list) {
      formedParts = list;
    };

    this.setAndWriteProjectErrorsToUser = function (errors) {
      me.setProjectErrors(errors);
      eventbus.trigger('roadAddressProject:writeProjectErrors');
    };

    this.setProjectErrors = function (errors) {
      projectErrors = errors;
    };

    this.clearProjectErrors = function () {
      projectErrors = [];
    };

    this.getProjectErrors = function () {
      return projectErrors;
    };

    this.pushCoordinates = function (button) {
      coordinateButtons.push(button);
    };

    this.clearCoordinates = function (_button) {
      coordinateButtons = [];
    };

    this.setTmpDirty = function (editRoadLinks) {
      dirtyProjectLinks = editRoadLinks;
    };

    this.getTmpDirty = function () {
      return dirtyProjectLinks;
    };

    this.isDirty = function () {
      return dirtyProjectLinks.length > 0;
    };

    function arrayIntersection(a, b, areEqualFunction) {
      return _.filter(a, function (aElem) {
        return _.some(b, function (bElem) {
          return areEqualFunction(aElem, bElem);
        });
      });
    }

    eventbus.on('roadAddressProject:startProject', this.getProjectsWithLinksById);

    eventbus.on('roadPartsValidation:checkRoadParts', function (validationResult) {
      if (validationResult.success === "ok") {
        addToReservedPartList(validationResult);
        updateReservedRoads(parseRoadPartInfoToResultRow());
        eventbus.trigger('roadAddress:projectValidationSucceed');
      } else {
        eventbus.trigger('roadAddress:projectValidationFailed', validationResult.success);
      }
    });

    eventbus.on('clearproject', function () {
      this.clearRoadAddressProjects();
    });

    eventbus.on('projectCollection:clickCoordinates', function (event, map) {
      var currentCoordinates = map.getView().getCenter();
      var errorIndex = event.currentTarget.id;
      var errorCoordinates = _.find(coordinateButtons, function (b) {
        return b.index === parseInt(errorIndex);
      }).coordinates;
      var index = _.findIndex(errorCoordinates, function (coordinates) {
        return coordinates.x === currentCoordinates[0] && coordinates.y === currentCoordinates[1];
      });
      if (index >= 0 && index + 1 < errorCoordinates.length) {
        map.getView().setCenter([errorCoordinates[index + 1].x, errorCoordinates[index + 1].y]);
        map.getView().setZoom(errorCoordinates[index + 1].zoom);
      } else {
        map.getView().setCenter([errorCoordinates[0].x, errorCoordinates[0].y]);
        map.getView().setZoom(errorCoordinates[0].zoom);
      }
    });

    eventbus.on('projectLink:editedBeginDistance', function () {
      editedBeginDistance = true;
    });
    eventbus.on('projectLink:editedEndDistance', function () {
      editedEndDistance = true;
    });


    this.getCurrentProject = function () {
      return currentProject;
    };

    this.setCurrentProject = function (project) {
      currentProject = project;
    };

    this.getPublishableStatus = function () {
      return publishableProject;
    };

    this.checkIfReserved = function (data) {
      return backend.checkIfRoadpartReserved(data[3].value === '' ? 0 : parseInt(data[3].value), data[4].value === '' ? 0 : parseInt(data[4].value), data[5].value === '' ? 0 : parseInt(data[5].value), data[1].value, data.projectId);

    };

    var ProjectLinkModel = function (data) {

      var getData = function () {
        return data;
      };

      return {
        getData: getData
      };
    };

    this.reOpenProjectById = function (projectId) {
      backend.reOpenProject(projectId, function (successObject) {
        eventbus.trigger("roadAddressProject:reOpenedProject", successObject);
      }, function (errorObject) {
        if (errorObject.message) {
          new ModalConfirm(errorObject.message.toString());
        } else {
          new ModalConfirm(errorObject.statusText.toString());
        }
        applicationModel.removeSpinner();
        console.log("Error at deleting rotatingId: " + errorObject);
      });
    };
  };
}(this));
