/**
 * ProjectCollection - Manages road address projects and project links
 * 
 * Handles project-related operations including:
 * - Project link management and retrieval
 * - Road part reservation and formation
 * - Project validation and publishing
 * - Backend integration for project operations
 * - Dirty state tracking and change management
 */
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/eventbus.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { GeometryUtils } from '@utils/GeometryUtils.js';
import { getUserGeoLocation } from '@model/ApplicationModel.js';

export function ProjectCollection(backend, startupParameters) {
    const me = this;
    // eslint-disable-next-line no-unused-vars
    let roadAddressProjects = [];
    let projectErrors = [];
    let reservedParts = [];
    let formedParts = [];
    let coordinateButtons = [];
    let projectInfo;
    let currentProject;
    let fetchedProjectLinks = [];
    let dirtyProjectLinkIds = [];
    let dirtyProjectLinks = [];
    let publishableProject = false;
    const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    const ProjectStatus = ViiteEnumerations.ProjectStatus;
    const Track = ViiteEnumerations.Track;
    const BAD_REQUEST_400 = 400;
    const PRECONDITION_FAILED_412 = 412;
    const INTERNAL_SERVER_ERROR_500 = 500;
    const ALLOWED_ADDR_M_VALUE_PERCENTAGE = 0.2;
    let editedEndDistance = false;
    let editedBeginDistance = false;

    const resetEditedDistance = function () {
      editedEndDistance = false;
      editedBeginDistance = false;
    };

    const normalizeProjectErrors = function (payload) {
      if (_.isArray(payload)) {
        return payload;
      }

      if (_.isObject(payload)) {
        const extractedErrors = _.find([
          payload.projectErrors,
          payload.validationErrors,
          _.get(payload, 'project.projectErrors'),
          _.get(payload, 'project.validationErrors')
        ], _.isArray);

        return _.isArray(extractedErrors) ? extractedErrors : [];
      }

      return [];
    };

    const projectLinks = function () {
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
      const chain = _.find(fetchedProjectLinks, function (linkChain) {
        const pureChain = _.map(linkChain, function (l) {
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
      let id = projectId;
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
        Spinner.hide();
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
        me.setAndWriteProjectErrorsToUser(result);
        me.setReservedParts(result.reservedInfo);
        me.setFormedParts(result.formedInfo);
        publishableProject = result.publishable;
        eventbus.trigger('roadAddressProject:projectFetched', projectInfo);
        eventbus.trigger('roadAddressProject:setRecalculatedAfterChangesFlag', false);
      });
    };

    this.revertRoadAddressChangeType = function () {
      resetEditedDistance();
      const fetchedLinks = this.getAll();
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
      let projectId = 0;
      if (projectInfo !== undefined) {
        projectId = projectInfo.id;
      } else if (currentProject !== undefined && currentProject.project.id !== undefined) {
        projectId = currentProject.project.id;
      }
      const dataJson = {
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
            evk: (part.currentEvk),
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
            evk: (part.newEvk),
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
          me.setAndWriteProjectErrorsToUser(result);
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
        Spinner.show();
        const coordinates = getUserGeoLocation();
        const data = {
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
            me.setAndWriteProjectErrorsToUser(response);
            me.setFormedParts(response.formedInfo);
            eventbus.trigger('projectLink:revertedChanges', response);
          } else {
            if (response.status === INTERNAL_SERVER_ERROR_500 || response.status === BAD_REQUEST_400) {
              eventbus.trigger('roadAddress:projectLinksUpdateFailed', response.status);
            }
            new ConfirmPopup(response.errorMessage, { type: "alert" });
            Spinner.hide();
          }
        });
      }
    };

    const createOrUpdate = function (dataJson) {
      if ((!_.isEmpty(dataJson.linkIds) || !_.isEmpty(dataJson.ids)) && typeof dataJson.projectId !== 'undefined' && dataJson.projectId !== 0) {
        if (dataJson.roadNumber !== 0 && dataJson.roadPartNumber !== 0) {
          Spinner.show();
          resetEditedDistance();
          const ids = dataJson.ids;
          if (dataJson.roadAddressChangeType === RoadAddressChangeType.New.value && ids.length === 0) {
            backend.createProjectLinks(dataJson, function (successObject) {
              if (successObject.success) {
                publishableProject = successObject.publishable;
                me.setAndWriteProjectErrorsToUser(successObject);
                me.setFormedParts(successObject.formedInfo);
                eventbus.trigger('projectLink:projectLinksCreateSuccess');
                eventbus.trigger('roadAddress:projectLinksUpdated', successObject);
                if (successObject.errorMessage) {
                  new ConfirmPopup(successObject.errorMessage, { type: "alert" });
                }
              } else {
                new ConfirmPopup(successObject.errorMessage, { type: "alert" });
                Spinner.hide();
              }
            });
          } else {
            backend.updateProjectLinks(dataJson, function (successObject) {
              if (successObject.success) {
                publishableProject = successObject.publishable;
                me.setAndWriteProjectErrorsToUser(successObject);
                me.setFormedParts(successObject.formedInfo);
                eventbus.trigger('roadAddressProject:projectLinkSaved', dataJson.projectId, successObject.publishable);
                eventbus.trigger('roadAddress:projectLinksUpdated', successObject);
              } else {
                new ConfirmPopup(successObject.errorMessage, { type: "alert" });
                Spinner.hide();
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
      const validUserGivenAddrMValues = function (linkId, userEndAddr) {
        if (!_.isUndefined(userEndAddr) && userEndAddr !== null) {
          const roadPartIds = me.getMultiProjectLinks(linkId);
          const roadPartLinks = me.getProjectLink(_.map(roadPartIds, function (road) {
            return road;
          }));
          const startAddrFromChangedLinks = _.minBy(_.map(roadPartLinks, function (link) {
            return link.getData().addrMRange.start;
          }));
          const userDiffFromChangedLinks = userEndAddr - startAddrFromChangedLinks;
          const roadPartGeometries = _.map(roadPartLinks, function (roadPart) {
            return roadPart.getData().points;
          });
          const roadPartLength = _.reduce((roadPartGeometries), function (length, geom) {
            return GeometryUtils.geometryLength(geom) + length;
          }, 0.0);
          return (userDiffFromChangedLinks >= (roadPartLength * (1 - ALLOWED_ADDR_M_VALUE_PERCENTAGE))) && (userDiffFromChangedLinks <= (roadPartLength * (1 + ALLOWED_ADDR_M_VALUE_PERCENTAGE)));
        } else {
          return true;
        }
      };
      const newAndOtherLinks = _.partition(changedLinks, function (l) {
        return l.id === 0;
      });
      const newLinks = newAndOtherLinks[0];
      const otherLinks = newAndOtherLinks[1];

      const linkIds = _.uniq(_.map(newLinks, function (t) {
        if (t.linkId)
          return t.linkId;
        else
          return 0;
      }));

      const ids = _.uniq(_.map(otherLinks, function (t) {
        if (t.id)
          return t.id;
        else
          return 0;
      }));

      const projectId = projectInfo.id;
      const coordinates = getUserGeoLocation();
      const roadAddressProjectForm = $('#roadAddressProjectForm');
      const endDistance = $('#endDistance')[0];
      const hasDevRights = _.includes(startupParameters.roles, 'dev');

      const getValueWithId = function (id) {
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

      const reversed = _.chain(changedLinks).map(function (c) {
        return c.reversed;
      }).reduceRight(function (a, b) {
        return a || b;
      }).value();
      let userDefinedEndAddressM = null;
      if (endDistance && touchedEndDistance) userDefinedEndAddressM = (isNaN(Number(endDistance.value)) ? null : Number(endDistance.value));
      const dataJson = {
        ids: ids,
        linkIds: linkIds,
        roadAddressChangeType: statusCode,
        projectId: projectId,
        roadNumber: Number(roadAddressProjectForm.find('#tie')[0].value),
        roadPartNumber: Number(roadAddressProjectForm.find('#osa')[0].value),
        trackCode: Number(roadAddressProjectForm.find('#trackCodeDropdown')[0].value),
        discontinuity: Number(roadAddressProjectForm.find('#discontinuityDropdown')[0].value),
        roadEly: Number(0), // TODO: remove this when backend supports it
        roadEvk: Number(roadAddressProjectForm.find('#elinvoimakeskus')[0].value),
        roadLinkSource: Number(_.head(changedLinks).roadLinkSource),
        administrativeClass: Number(roadAddressProjectForm.find('#administrativeClassDropdown')[0].value),
        userDefinedEndAddressM: userDefinedEndAddressM,
        coordinates: coordinates,
        roadName: roadAddressProjectForm.find('#roadName')[0].value,
        reversed: reversed,
        devToolData: devToolData
      };
      if (dataJson.trackCode === Track.Unknown.value) {
        new ConfirmPopup("Tarkista ajoratakoodi", { type: "alert" });
        Spinner.hide();
      }

      const changedLink = _.chain(changedLinks).uniq().sortBy(function (cl) {
        return cl.endAddressM;
      }).last().value();
      const isNewRoad = changedLink.status === RoadAddressChangeType.New.value;

      const validUserEndAddress = !validUserGivenAddrMValues(_.head(dataJson.ids || dataJson.linkIds), dataJson.userDefinedEndAddressM);
      if (isNewRoad && (editedEndDistance || editedBeginDistance) && validUserEndAddress) {
        new ConfirmPopup("Antamasi pituus eroaa yli 20% prosenttia geometrian pituudesta, haluatko varmasti tallentaa tämän pituuden?", {
          successCallback: function () {
            createOrUpdate(dataJson);
          },
          closeCallback: function () {
            Spinner.hide();
          }
        });
      } else {
        createOrUpdate(dataJson);
      }
    };

    this.createProject = function (data, resolution) {
      const roadPartList = _.map(reservedParts, function (part) {
        return {
          roadNumber: part.roadNumber,
          roadPartNumber: part.roadPartNumber,
          evk: (part.newEvk ? part.newEvk : part.currentEvk)
        };
      });

      const dataJson = {
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
          currentProject = result;
          me.setAndWriteProjectErrorsToUser(result);
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
      Spinner.show();
      const links = _.filter(selectedLinks, function (link) {
        return link.status !== RoadAddressChangeType.Terminated.value;
      });
      const coordinates = getUserGeoLocation();
      const dataJson = {
        projectId: projectId,
        roadNumber: selectedLinks[0].roadNumber,
        roadPartNumber: selectedLinks[0].roadPartNumber,
        links: links,
        coordinates: coordinates
      };
      resetEditedDistance();
      backend.directionChangeNewRoadlink(dataJson, function (successObject) {
        if (successObject.success) {
          me.setAndWriteProjectErrorsToUser(successObject);
          eventbus.trigger('changeProjectDirection:clicked');
        } else {
          eventbus.trigger('roadAddress:changeDirectionFailed', successObject.errorMessage);
          Spinner.hide();
        }
      });
    };

    this.publishProject = function () {
      backend.sendProjectChangesToViite(
        projectInfo.id,
        function (result) {
          if (result.sendSuccess) {
            eventbus.trigger('roadAddress:projectSentSuccess');
          } else {
            eventbus.trigger('roadAddress:projectSentFailed', result.errorMessage);
          }
        },
        function (result) {
          eventbus.trigger('roadAddress:projectSentFailed', result.status);
        }
      );
    };

    this.getDeleteButton = function (index, roadNumber, roadPartNumber, selector) {
      return deleteButton(index, roadNumber, roadPartNumber, selector);
    };

    const deleteButton = function (index, roadNumber, roadPartNumber, selector) {
      const disabledInput = !_.isUndefined(currentProject) &&
        (currentProject.project.statusCode === ProjectStatus.InUpdateQueue.value ||
          currentProject.project.statusCode === ProjectStatus.UpdatingToRoadNetwork.value);
      return '<i roadNumber="' + roadNumber + '" roadPartNumber="' + roadPartNumber + '" id="' + index + '" class="delete mt-1 btn-delete ' + selector + ' fas fa-trash-alt fa-lg" style="position: absolute; left: 365px;" ' + (disabledInput ? 'disabled' : '') + '></i>';
    };


    const addToReservedPartList = function (queryResult) {
      const qRoadParts = [];
      _.each(queryResult.reservedInfo, function (row) {
        qRoadParts.push(row);
      });

      const sameElements = arrayIntersection(qRoadParts, reservedParts, function (arrayarow, arraybrow) {
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
      projectErrors = normalizeProjectErrors(errors);
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
      const reservationValidationSucceeded = validationResult.success === true || validationResult.success === 'ok';
      if (reservationValidationSucceeded) {
        addToReservedPartList(validationResult);
        eventbus.trigger('roadAddress:projectValidationSucceed');
      } else {
        eventbus.trigger('roadAddress:projectValidationFailed', validationResult.error || validationResult.success);
      }
    });

    eventbus.on('clearproject', function () {
      this.clearRoadAddressProjects();
    });

    eventbus.on('projectCollection:clickCoordinates', function (event, map) {
      const currentCoordinates = map.getView().getCenter();
      const errorIndex = event.currentTarget.id;
      const errorCoordinates = _.find(coordinateButtons, function (b) {
        return b.index === parseInt(errorIndex, 10);
      }).coordinates;
      const index = _.findIndex(errorCoordinates, function (coordinates) {
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
      return backend.checkIfRoadpartReserved(data[3].value === '' ? 0 : parseInt(data[3].value, 10), data[4].value === '' ? 0 : parseInt(data[4].value, 10), data[5].value === '' ? 0 : parseInt(data[5].value, 10), data[1].value, data.projectId);

    };

    const ProjectLinkModel = function (data) {

      const getData = function () {
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
          new ConfirmPopup(errorObject.message.toString(), { type: "alert" });
        } else {
          new ConfirmPopup(errorObject.statusText.toString(), { type: "alert" });
        }
        Spinner.hide();
        console.error("Error at deleting rotatingId: " + errorObject);
      });
    };

    this.removeReservedPart = function (roadNumber, roadPartNumber) {
      if (currentProject) {
        currentProject.isDirty = true;
      }
      this.setReservedParts(_.filter(this.getReservedParts(), function (part) {
        return part.roadNumber.toString() !== roadNumber.toString() || part.roadPartNumber.toString() !== roadPartNumber.toString();
      }));
      removeRenumberedPart(roadNumber, roadPartNumber);
    };

    this.removeFormedPart = function (roadNumber, roadPartNumber) {
      if (currentProject) {
        currentProject.isDirty = true;
      }
      _.each(this.getRoadAddressesFromFormedRoadPart(roadNumber, roadPartNumber), function (roadAddresses) {
        _.each(roadAddresses, function (ra) {
          this.removeFormedPart(ra.roadAddressNumber, ra.roadAddressPartNumber);
        }.bind(this));
      }.bind(this));
      this.setFormedParts(_.filter(this.getFormedParts(), function (part) {
        return part.roadNumber.toString() !== roadNumber.toString() || part.roadPartNumber.toString() !== roadPartNumber.toString();
      }));
    };

    const removeRenumberedPart = function (roadNumber, roadPartNumber) {
      me.setFormedParts(_.filter(me.getFormedParts(), function (part) {
        let reNumberedPart = false;
        if (part.roadAddresses && part.roadAddresses.length > 0) {
          for (let i = 0; i < part.roadAddresses.length; ++i) {
            const ra = part.roadAddresses[i];
            reNumberedPart = (ra.roadAddressNumber.toString() === roadNumber.toString() &&
                ra.roadAddressPartNumber.toString() === roadPartNumber.toString()) && ra.isNumbering;
            if (reNumberedPart) {
              break;
            }
          }
        }
        return !reNumberedPart;
      }));
    };
}
