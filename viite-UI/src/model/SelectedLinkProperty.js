(function(root) {
  root.SelectedLinkProperty = function(backend, roadCollection) {
    var current = [];
    var dirty = false;
    var targets = [];
    var sources = [];
    var featuresToKeep = [];
    var previousAdjacents = [];
    var floatingRoadMarker = [];
    var anomalousMarkers = [];
    var BAD_REQUEST = 400;
    var PRECONDITION_FAILED_412 = 412;
    var INTERNAL_SERVER_ERROR_500 = 500;
    var RoadLinkType = LinkValues.RoadLinkType;
    var Anomaly = LinkValues.Anomaly;
    var LinkSource = LinkValues.LinkGeomSource;
    var SelectionType = LinkValues.SelectionType;

    var markers = ["A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
      "AA", "AB", "AC", "AD", "AE", "AF", "AG", "AH", "AI", "AJ", "AK", "AL", "AM", "AN", "AO", "AP", "AQ", "AR", "AS", "AT", "AU", "AV", "AW", "AX", "AY", "AZ",
      "BA", "BB", "BC", "BD", "BE", "BF", "BG", "BH", "BI", "BJ", "BK", "BL", "BM", "BN", "BO", "BP", "BQ", "BR", "BS", "BT", "BU", "BV", "BW", "BX", "BY", "BZ",
      "CA", "CB", "CC", "CD", "CE", "CF", "CG", "CH", "CI", "CJ", "CK", "CL", "CM", "CN", "CO", "CP", "CQ", "CR", "CS", "CT", "CU", "CV", "CW", "CX", "CY", "CZ"];

    var close = function() {
      if (!_.isEmpty(current) && !isDirty()) {
        _.each(current, function(selected) {
          selected.unselect();
        });
        applicationModel.setActiveButtons(false);
        eventbus.trigger('layer:enableButtons', true);
        eventbus.trigger('linkProperties:unselected');
        previousAdjacents = [];
        setCurrent([]);
        sources = [];
        targets = [];
        dirty = false;
        featuresToKeep = [];
        applicationModel.setActiveButtons(false);
      }
    };

    /*var isSingleLinkSelection = function() {
      return current.length === 1;
    };*/

    /*var isDifferingSelection = function(singleLinkSelect) {
      return (!_.isUndefined(singleLinkSelect) &&
      (singleLinkSelect !== isSingleLinkSelection()));
    };*/

    var setCurrent = function(data){
      current = data;
    };

    var canOpenByLinearLocationId = function (linearLocationId) {
      return !_.isUndefined(linearLocationId) && linearLocationId > LinkValues.UnknownRoadId;
    };

    var extractDataForDisplay = function (selectedData) {

      var extractUniqueValues = function (dataToExtract, property) {
        return _.chain(dataToExtract)
          .pluck(property)
          .uniq()
          .value()
          .join(', ');
      };

      var isMultiSelect = selectedData.length > 1;
      var selectedLinkIds = {selectedLinks: _.pluck(selectedData, 'linkId')};
      var selectedIds = {selectedIds: _.pluck(selectedData, 'id')};
          var properties = _.merge(_.cloneDeep(_.first(selectedData)), selectedLinkIds, selectedIds);
      var roadLinkSource = {
        roadLinkSource: _.chain(selectedData).map(function (s) {
          return s.roadLinkSource;
        }).uniq().map(function (a) {
          var linkGeom = _.find(LinkSource, function (source) {
              return source.value === parseInt(a);
          });
          if(_.isUndefined(linkGeom))
            return LinkSource.Unknown.descriptionFI;
          else return linkGeom.descriptionFI;
        }).uniq().join(", ").value()
      };
      if (isMultiSelect) {
        var endRoadOnSelection = _.chain(selectedData)
            .sortBy(function (sd) {
                return sd.endAddressM;
            }).last().value();
        var startRoadOnSelection = _.chain(selectedData)
            .sortBy(function (sd) {
                return sd.endAddressM;
            }).first().value();
        var ambiguousFields = ['maxAddressNumberLeft', 'maxAddressNumberRight', 'minAddressNumberLeft', 'minAddressNumberRight',
          'municipalityCode', 'verticalLevel', 'roadNameFi', 'roadNameSe', 'roadNameSm', 'modifiedAt', 'modifiedBy',
          'endDate', 'discontinuity', 'startAddressM', 'endAddressM'];
        properties = _.omit(properties, ambiguousFields);
        var latestModified = dateutil.extractLatestModifications(selectedData);
        var municipalityCodes = {municipalityCode: extractUniqueValues(selectedData, 'municipalityCode')};
        var verticalLevels = {verticalLevel: extractUniqueValues(selectedData, 'verticalLevel')};
        var roadPartNumbers = {roadPartNumber: extractUniqueValues(selectedData, 'roadPartNumber')};
        var elyCodes = {elyCode: extractUniqueValues(selectedData, 'elyCode')};

        // TODO Check that merge was done correctly
        var discontinuity = {discontinuity: parseInt(extractUniqueValues([endRoadOnSelection], 'discontinuity'))};
        //var startAddressM = {startAddressM: parseInt(extractUniqueValues([startRoadOnSelection], 'startAddressM'))};
        //var endAddressM =   {endAddressM:  parseInt(extractUniqueValues([endRoadOnSelection], 'endAddressM'))};
        var startAddressM = {startAddressM: _.min(_.chain(selectedData).pluck('startAddressM').uniq().value())};
        var endAddressM = {endAddressM: _.max(_.chain(selectedData).pluck('endAddressM').uniq().value())};

        var roadNames = {
          roadNameFi: extractUniqueValues(selectedData, 'roadNameFi'),
          roadNameSe: extractUniqueValues(selectedData, 'roadNameSe'),
          roadNameSm: extractUniqueValues(selectedData, 'roadNameSm')
        };
        properties = _.merge(properties, latestModified, municipalityCodes, verticalLevels, roadPartNumbers, roadNames, elyCodes, startAddressM, endAddressM, discontinuity);
      }
      properties = _.merge(properties, roadLinkSource);
      return properties;
    };

    var isOnLinearLocation = function(data) {
      return !_.isUndefined(data) && !_.isUndefined(data.linearLocationId) && data.linearLocationId !== 0;
    };

    var openSingleClick = function (data) {
      if (isOnLinearLocation(data)) {
        setCurrent(roadCollection.getGroupByLinearLocationId(data.linearLocationId));
      } else {
        setCurrent(roadCollection.getGroupByLinkId(data.linkId));
      }
    };

    var openDoubleClick = function (data) {
      if (isOnLinearLocation(data)) {
        setCurrent(roadCollection.getByLinearLocationId(data.linearLocationId));
      } else {
        setCurrent(roadCollection.getByLinkId(data.linkId));
      }
    };

    var open = function(data, isSingleClick, visibleFeatures) {
        if (isSingleClick) {
          openSingleClick(data);
        } else {
          openDoubleClick(data);
        }
        /*var currentFloatings = getCurrentFloatings();
        if (!_.isEmpty(currentFloatings)) {
          setSources(currentFloatings);
        }*/
        _.forEach(current, function (selected) {
          selected.select();
        });
        processOl3Features(visibleFeatures);
        eventbus.trigger('linkProperties:selected', extractDataForDisplay(get()));
    };

    var openFloating = function (data, isSingleClick, visibleFeatures) {
            open(data, isSingleClick, visibleFeatures);
            getGroupAdjacents(data.linkId);
            /*var data4Display = _.map(get(), function (feature) {
                return extractDataForDisplay([feature]);
            });

            if (!applicationModel.isReadOnly() && get()[0] && get()[0].roadLinkType === RoadLinkType.FloatingRoadLinkType.value) {
                addToFeaturesToKeep(data4Display);
            }
            if (!_.isEmpty(getFeaturesToKeep()) && !isLinkIdInFeaturesToKeep(data.linkId)) {
                addToFeaturesToKeep(data4Display);
            }*/
            processOl3Features(visibleFeatures);
            eventbus.trigger('adjacents:startedFloatingTransfer');
            eventbus.trigger('linkProperties:deactivateInteractions');
    };

    var findUnknown = function(linkId, id) {
      var permanent = !_.isUndefined(linkId) ? _.uniq(roadCollection.getByLinkId([linkId]), _.isEqual) : (!_.isUndefined(id) ? _.uniq(roadCollection.get([id]), _.isEqual) : [] );
      var temporary = !_.isUndefined(linkId) ? _.uniq(roadCollection.getTmpByLinkId([linkId]), _.isEqual) : (!_.isUndefined(id) ? _.uniq(roadCollection.getTmpById([id]), _.isEqual) : [] );
      var permUnknown = _.reject(permanent, function(road) {
        return road.getData().roadLinkType !== RoadLinkType.UnknownRoadLinkType.value && road.getData().anomaly !== Anomaly.None.value;
      });
      var tmpUnknown = _.reject(temporary, function(road) {
        return road.getData().roadLinkType !== RoadLinkType.UnknownRoadLinkType.value && road.getData().anomaly !== Anomaly.None.value;
      });
      return _.flatten(permUnknown.concat(tmpUnknown));
    };

    var openUnknown = function(linkId, id, visibleFeatures) {
      var canIOpen = !_.isUndefined(linkId) ? true : !isSelectedById(id);
      if (canIOpen) {
        if(featuresToKeep.length === 0){
          close();
        } else {
          if (!_.isEmpty(current) && !isDirty()) {
            _.forEach(current, function(selected) { selected.unselect(); });
          }
        }
        if(!_.isUndefined(linkId)){
          setCurrent(_.uniq(roadCollection.getByLinkId([linkId]), _.isEqual));
        } else {
          setCurrent(_.uniq(roadCollection.getById([id]), _.isEqual));
        }
        if(current[0].getData().anomaly === Anomaly.GeometryChanged.value) {
          setCurrent(_.filter(roadCollection.getTmpRoadLinkGroups(), function(linkGroup){
              return linkGroup.getData().linkId === linkId;
          }));
        }

        eventbus.trigger('linkProperties:activateAllSelections');

        _.forEach(current, function (selected) {
          selected.select();
        });

        var currentFloatings = _.filter(current, function(curr){
          return curr.getData().floating === SelectionType.Floating.value;
        });
        if(!_.isEmpty(currentFloatings)){
          setSources(currentFloatings);
        }

        var data4Display = _.map(get(), function(feature){
          return extractDataForDisplay([feature]);
        });

        if(!applicationModel.isReadOnly() && get()[0].anomaly === Anomaly.NoAddressGiven.value){
          addToFeaturesToKeep(data4Display);
        }
        if(!_.isEmpty(getFeaturesToKeep()) && !isLinkIdInFeaturesToKeep(data.linkId)){
          addToFeaturesToKeep(data4Display);
        }
        var contains = _.find(getFeaturesToKeep(), function(fk){
          return fk.linkId === data.linkId;
        });

        if(!_.isEmpty(getFeaturesToKeep()) && _.isUndefined(contains)){
          if(_.isArray(extractDataForDisplay(get()))){
            addToFeaturesToKeep(data4Display);
          } else {
            addToFeaturesToKeep(data4Display);
          }
        }
        processOl3Features(visibleFeatures);
        eventbus.trigger('adjacents:startedFloatingTransfer');
        eventbus.trigger('linkProperties:selected', data4Display);
        _.defer(function(){
          eventbus.trigger('linkProperties:deactivateAllSelections');
        });
      }
    };

    var processOl3Features = function (visibleFeatures) {
      var selectedOL3Features = _.filter(visibleFeatures, function (vf) {
        return (_.some(get().concat(featuresToKeep), function (s) {
          if (s.id !== LinkValues.UnknownRoadId && s.id !== LinkValues.NewRoadId) {
            return s.id === vf.linkData.id && s.mmlId === vf.linkData.mmlId;
          } else {
            return s.linkId === vf.linkData.linkId && s.mmlId === vf.linkData.mmlId && s.floating === vf.linkData.floating && s.anomaly === vf.linkData.anomaly;
          }
        }));
      });
      eventbus.trigger('linkProperties:ol3Selected', selectedOL3Features);
    };

      var getGroupAdjacents = function (linkId) {
          if (!_.isUndefined(current)) {
              if (current.length === 1)
                  return current;
              var orderedCurrent = _.sortBy(current, function (curr) {
                  return curr.getData().endAddressM;
              });
              var selectedFeature = _.find(orderedCurrent, function (oc) {
                  return oc.getData().linkId === linkId;
              });
              var adjacentsArray = [selectedFeature];

              var findAdjacents = function (list, elem) {
                  if (list.length > 0) {
                      var filteredList = _.filter(list, function (l) {
                          return l.getData().id !== elem.getData().id && !_.contains(adjacentsArray, l);
                      });
                      var existingAdjacents = _.filter(filteredList, function (le) {
                          return !_.isUndefined(GeometryUtils.connectingEndPoint(le.getData().points, elem.getData().points));
                      });
                      //if in case we found more than one adjacent, we should process each possible adjacent
                      _.each(existingAdjacents, function (adj) {
                          adjacentsArray.push(adj);
                      });
                      _.each(existingAdjacents, function (adj) {
                          findAdjacents(_.filter(list, function (l) {
                              return l.getData().id !== elem.getData().id && !_.contains(adjacentsArray, l);
                          }), adj);
                      });
                  }
              };

              findAdjacents(current, selectedFeature);
              setCurrent(_.sortBy(adjacentsArray, function (curr) {
                  return curr.getData().endAddressM;
              }));
          }
          applicationModel.setContinueButton(true);
      };

    var getLinkAdjacents = function(link) {
      var linkIds = {};
      var chainLinks = [];
      _.each(current, function (link) {
        if (!_.isUndefined(link))
          chainLinks.push(link.getData().linkId);
      });
      _.each(targets, function (link) {
        chainLinks.push(link.linkId);
      });
      var data = {
        "selectedLinks": _.uniq(chainLinks), "linkId": parseInt(link.linkId), "roadNumber": parseInt(link.roadNumber),
        "roadPartNumber": parseInt(link.roadPartNumber), "trackCode": parseInt(link.trackCode)
      };

      if (!applicationModel.isReadOnly() && !applicationModel.selectionTypeIs(SelectionType.All)){
        applicationModel.addSpinner();
        backend.getTargetAdjacent(data, function (adjacents) {
          applicationModel.removeSpinner();
          if (!_.isEmpty(adjacents)){
            linkIds = _.map(adjacents, function (roads) {
                return roads.linkId;
            });
          }
          applicationModel.setCurrentAction(applicationModel.actionCalculating);
          if (!applicationModel.isReadOnly()) {
            var selectedLinks = _.reject(get().concat(getFeaturesToKeep()), function(feature){
                return (feature.segmentId === "" || (_.contains(linkIds, feature.linkId) && (feature.anomaly === Anomaly.GeometryChanged.value || feature.anomaly === Anomaly.None.value)));
            });
            var filteredDuplicatedAdjacents = _.reject(adjacents, function(adj){
                var foundDuplicatedLink = _.find(previousAdjacents, function(prev){
                   return prev.linkId === adj.linkId;
                });
                return _.some(foundDuplicatedLink);
            });
            var filteredPreviousAdjacents = filteredDuplicatedAdjacents.concat(previousAdjacents);

            var filteredAdjacents = _.reject(filteredPreviousAdjacents, function(adj){
                var foundDuplicatedLink = _.find(selectedLinks, function(prev){
                      return prev.linkId === adj.linkId;
                });
                return _.some(foundDuplicatedLink);
            });
            previousAdjacents = filteredAdjacents;

            var markedRoads = {
              "adjacents": _.map(filteredAdjacents, function (a, index) {
                return _.merge({}, a, {"marker": markers[index]});
              }), "links": link
            };
            if(applicationModel.selectionTypeIs(SelectionType.Floating)) {
              eventbus.trigger("adjacents:floatingAdded", markedRoads.adjacents);
              if(_.isEmpty(markedRoads.adjacents)){
                applicationModel.setContinueButton(true);
              }
            }
            else {
              eventbus.trigger("adjacents:added", markedRoads.links, markedRoads.adjacents);
            }
            if(!applicationModel.selectionTypeIs(SelectionType.Unknown)){
              eventbus.trigger('adjacents:startedFloatingTransfer');
            }
          }
        });
      }
      return linkIds;
    };

    var getLinkFloatingAdjacents = function(selectedLink) {
      var linkIds = {};
      var chainLinkIds = [];
      var chainIds = [];
      _.each(current, function (link) {
        if (!_.isUndefined(link))
          chainLinkIds.push(link.getData().linkId);
          chainIds.push(link.getData().id);
      });
      _.each(targets, function (link) {
        chainLinkIds.push(link.linkId);
        chainLinkIds.push(link.id);
      });
      var data = {
        "selectedLinks": _.uniq(chainLinkIds), "selectedIds": _.uniq(chainIds), "linkId": parseInt(selectedLink.linkId), "id": parseInt(selectedLink.id), "roadNumber": parseInt(selectedLink.roadNumber),
        "roadPartNumber": parseInt(selectedLink.roadPartNumber), "trackCode": parseInt(selectedLink.trackCode)
      };

      if (!applicationModel.isReadOnly() && !applicationModel.selectionTypeIs(SelectionType.All)){
        applicationModel.addSpinner();
        backend.getFloatingAdjacent(data, function (adjacents) {
          applicationModel.removeSpinner();
          if (!_.isEmpty(adjacents)){
            linkIds = adjacents;
          }
          applicationModel.setCurrentAction(applicationModel.actionCalculating);
          if (!applicationModel.isReadOnly()) {
            var rejectedRoads = _.reject(get().concat(getFeaturesToKeep()), function(link){
              return link.roadwayId === "" || link.anomaly === Anomaly.GeometryChanged.value;
            });
            var selectedLinkIds = _.map(rejectedRoads, function (roads) {
              return roads.linkId;
            });
            var filteredPreviousAdjacents = _.filter(adjacents, function(adj){
              return !_.contains(_.pluck(previousAdjacents, 'linkId'), adj.linkId);
            }).concat(previousAdjacents);
            var filteredAdjacents = _.filter(filteredPreviousAdjacents, function(prvAdj){
              return !_.contains(selectedLinkIds, prvAdj.linkId);
            });
            previousAdjacents = filteredAdjacents;
            var markedRoads = {
              "adjacents": _.map(applicationModel.selectionTypeIs(SelectionType.Floating) ? _.reject(filteredAdjacents, function(t){
                return t.floating !== SelectionType.Floating.value;
              }) :filteredAdjacents, function (a, index) {
                return _.merge({}, a, {"marker": markers[index]});
              }), "links": selectedLink
            };
            if(applicationModel.selectionTypeIs(SelectionType.Floating)) {
              eventbus.trigger("adjacents:floatingAdded", markedRoads.adjacents);
              if(_.isEmpty(markedRoads.adjacents)){
                applicationModel.setContinueButton(true);
              }
            }
            else {
              eventbus.trigger("adjacents:added", markedRoads.links, markedRoads.adjacents);
            }
            if(!applicationModel.selectionTypeIs(SelectionType.Unknown)){
              eventbus.trigger('adjacents:startedFloatingTransfer');
            }
          }
        });
      }
      return linkIds;
    };

    var getFromMultipleAdjacents = function(data, newSources) {
      backend.getAdjacentsFromMultipleSources(data, function(adjacents) {
        var calculatedRoads;
        var sourcesIds = _.map(sources, function(s){
          return s.getData().id;
        });
        var unselectedAdjacents = _.filter(adjacents, function(adj){
          return !_.contains(sourcesIds, adj.id);
        });
        calculatedRoads = {
          "adjacents": _.map(unselectedAdjacents, function(a, index) {
            return _.merge({}, a, {"marker": markers[index]});
          }),
          "links": newSources
        };
        if (_.isEmpty(unselectedAdjacents) || applicationModel.isReadOnly()) {
          sources = sources.concat(roadCollection.toRoadLinkModel(calculatedRoads.links));
        }
        eventbus.trigger("adjacents:floatingAdded", calculatedRoads.adjacents);
      });
    };

    var processReturnedRoadLinkData = function(additionalSourceIdentifier, floatingsToAdd, sources, response) {
      var fetchedFeature = roadCollection.toRoadLinkModel([response])[0];

      if (!_.isUndefined(fetchedFeature)) {
        sources.push(fetchedFeature);
        addToFeaturesToKeep(fetchedFeature.getData());
      }
      var chainLinks = [];
      var chainIds = [];
      _.each(sources, function(link) {
        if (!_.isUndefined(link)){
          chainLinks.push(link.getData().linkId);
          chainIds.push(link.getData().id);
        }
      });
      _.each(targets, function(link) {
        if (!_.isUndefined(link)) {
          chainLinks.push(link.getData().linkId);
          chainIds.push(link.getData().id);
        }
      });
      var sourceData = _.map(sources, function(s){
        return s.getData();
      });

      var newSources = _.isArray(sourceData) ? sourceData : [sourceData];
      var isAddedToNewSources = _.chain(newSources).map(function (ns) {
        return ns.id;
      }).contains(fetchedFeature.getData().id).value();

      if (!_.isUndefined(additionalSourceIdentifier) && !_.isUndefined(fetchedFeature) && !isAddedToNewSources)
        newSources.push(fetchedFeature.getData());
      newSources = _.filter(newSources, function (link) {
        return link.endDate === "";
      });
      var data = _.map(newSources, function (ns) {
        return {"selectedLinks": _.uniq(chainLinks), "selectedIds": _.uniq(chainIds), "linkId": parseInt(ns.linkId), "id": parseInt(ns.id), "roadNumber": parseInt(ns.roadNumber),
          "roadPartNumber": parseInt(ns.roadPartNumber), "trackCode": parseInt(ns.trackCode)};
      });
      getFromMultipleAdjacents(data, newSources);
    };

    var fetchRoadLinkDataByLinkId = function(existingSources, additionalSourceLinkId, sources) {
      backend.getRoadAddressByLinkId(parseInt(additionalSourceLinkId), function (response) {
        processReturnedRoadLinkData(additionalSourceLinkId, existingSources, sources, response);
      });
    };

    var fetchRoadLinkDataById = function(floatingsToAdd, additionalSourceId, sources) {
     backend.getRoadAddressById(parseInt(additionalSourceId), function(response) {
       processReturnedRoadLinkData(additionalSourceId, floatingsToAdd, sources, response);
     });
    };

    eventbus.on("adjacents:additionalSourceSelected", function(floatingsToAdd, additionalSourceLinkId, additionalSourceId) {
      sources = current;
      if(!_.isUndefined(additionalSourceId) && additionalSourceId !== LinkValues.UnknownRoadId && additionalSourceId !== LinkValues.NewRoadId)
        fetchRoadLinkDataById(floatingsToAdd, additionalSourceId, sources);
      else
        fetchRoadLinkDataByLinkId(floatingsToAdd, additionalSourceLinkId, sources);
    });

    eventbus.on('linkProperties:closed', function(){
      eventbus.trigger('layer:enableButtons', true);
      applicationModel.setSelectionType(SelectionType.All);
      clearFeaturesToKeep();
    });

    eventbus.on('roadAddress:openProject', function(result) {
      close(result);
    });

    var isDirty = function() {
      return dirty;
    };

    /*var isSelectedByLinearLocationId = function (linearLocationId) {
      return _.some(current, function (selected) {
        return selected.getData().linearLocationId === linearLocationId;
      });
    };*/

    /*var isSelectedByLinkId = function(linkId) {
      return _.some(current, function(selected) {
        return selected.getData().linkId === linkId; });
    };*/

    var transferringCalculation = function(){
      var targetsData = _.map(targets,function (t){
        if (_.isUndefined(t.linkId)) {
          return t.getData();
        } else return t;
      });

      var targetDataIds = _.uniq(_.filter(_.map(targetsData.concat(getFeaturesToKeep()), function(feature){
        if(feature.floating !== SelectionType.Floating.value && feature.anomaly === Anomaly.NoAddressGiven.value){
          return feature.linkId.toString();
        }
      }), function (target){
        return !_.isUndefined(target);
      }));

      var sourceDataIds = _.map(getSources(), function (source) {
        return source.linkId.toString();
      });

      var data = {"sourceLinkIds": _.uniq(sourceDataIds), "targetLinkIds":_.uniq(targetDataIds)};

      if(!_.isEmpty(data.sourceLinkIds) && !_.isEmpty(data.targetLinkIds)){
        backend.getTransferResult(data, function(result) {
          if(!_.isEmpty(result) && !applicationModel.isReadOnly()) {
            eventbus.trigger("adjacents:roadTransfer", result, sourceDataIds.concat(targetDataIds), targetDataIds);
            roadCollection.setNewTmpRoadAddresses(result);
            eventbus.trigger('linkProperties:cleanFloatingsAfterDefloat');
          }
        });
      } else {
        eventbus.trigger('linkProperties:transferFailed', PRECONDITION_FAILED_412);
      }
    };

    var saveTransfer = function() {
      eventbus.trigger('linkProperties:saving');
      var targetsData = _.map(targets,function (t){
        if(_.isUndefined(t.linkId)){
          return t.getData();
        }else return t;
      });

      var targetDataIds = _.uniq(_.filter(_.map(targetsData.concat(getFeaturesToKeep()), function(feature){
        if(feature.roadLinkType !== RoadLinkType.FloatingRoadLinkType.value && feature.anomaly === Anomaly.NoAddressGiven.value){
          return feature.linkId;
        }
      }), function (target){
        return !_.isUndefined(target);
      }));
      var sourceDataIds = _.chain(getSources()).map(function (source) {
        return source.linkId;
      }).uniq().value();

      var data = {'sourceIds': sourceDataIds, 'targetIds': targetDataIds};

      if(!_.isEmpty(data.sourceIds) && !_.isEmpty(data.targetIds)){
        backend.createRoadAddress(data, function(errorObject) {
          if (errorObject.status === INTERNAL_SERVER_ERROR_500 || errorObject.status === BAD_REQUEST) {
            eventbus.trigger('linkProperties:transferFailed', errorObject.status);
          }
        });
      } else {
        eventbus.trigger('linkProperties:transferFailed', PRECONDITION_FAILED_412);
      }
    };

    var addTargets = function(target, adjacents){
      backend.getRoadAddressByLinkId(parseInt(target), function (response) {
          var fetchedFeature = roadCollection.toRoadLinkModel([response])[0];

          if (!_.contains(targets, target))
              targets.push(fetchedFeature.getData());
          var targetData = _.filter(adjacents, function (adjacent) {
              return adjacent.linkId === parseInt(target);
          });
          if (!_.isEmpty(targetData)) {
              $('#additionalSource').remove();
              $('#adjacentsData').remove();
              getLinkAdjacents(_.first(targetData));
          }
      });
    };

    var revertToFloatingAddress= function (idToFloating) {
      var data = {
        "linkId" : idToFloating
      };

      backend.revertToFloating(data, idToFloating, function () {
        eventbus.trigger('linkProperties:activateAllSelections');
        eventbus.trigger('roadLinks:refreshView');
        close();
        clearAndReset(false);
      }, function (errorObject) {
        applicationModel.removeSpinner();
        if (errorObject.status === INTERNAL_SERVER_ERROR_500 || errorObject.status === BAD_REQUEST) {
          eventbus.trigger('linkProperties:transferFailed', errorObject.status);
        }
      });
    };

    var getFloatingRoadMarker = function() {
      return floatingRoadMarker;
    };

    var setFloatingRoadMarker  = function(ft) {
      floatingRoadMarker = ft;
    };

    var getAnomalousMarkers = function(){
      return anomalousMarkers;
    };

    var setAnomalousMarkers = function(markers){
      anomalousMarkers = markers;
    };

    var getSources = function() {
      return _.union(_.map(sources, function (roadLink) {
        return roadLink;
      }));
    };

    var setSources = function(scs) {
      sources = scs;
    };

    var resetSources = function() {
      sources = [];
      return sources;
    };

    var resetTargets = function() {
      targets = [];
      return targets;
    };

    var setDirty = function(state){
      dirty = state;
    };

    var cancel = function() {
      dirty = false;
      _.each(current, function(selected) { selected.cancel(); });
      if(!_.isUndefined(_.first(current))){
        var originalData = _.first(current).getData();
        eventbus.trigger('linkProperties:cancelled', _.cloneDeep(originalData));
        eventbus.trigger('roadLinks:clearIndicators');
      }
    };

    var cancelAndReselect = function(action){
      if (action === applicationModel.actionCalculating) {
        var floatingMarkers = getFloatingRoadMarker();
        eventbus.trigger('linkProperties:floatingRoadMarkerPreviousSelected', floatingMarkers);
      }
      clearAndReset(false);
      setCurrent([]);
      eventbus.trigger('linkProperties:clearHighlights');
    };

    var clearAndReset = function(afterDefloat){
      roadCollection.resetTmp();
      roadCollection.resetChangedIds();
      applicationModel.resetCurrentAction();
      applicationModel.setContinueButton(false);
      applicationModel.setActiveButtons(false);
      roadCollection.resetPreMovedRoadAddresses();
      clearFeaturesToKeep();
      eventbus.trigger('roadLinks:clearIndicators');
      if(!afterDefloat) {
        roadCollection.resetNewTmpRoadAddresses();
        resetSources();
        resetTargets();
        previousAdjacents = [];
        _.defer(function () {
            if (!_.isEmpty(getFeaturesToKeep())) {
                setCurrent(roadCollection.toRoadLinkModel(getFeaturesToKeep()));
                eventbus.trigger("linkProperties:selected", extractDataForDisplay(getFeaturesToKeep()));
            }
        });
      }
    };

    var cancelAfterDefloat = function(action, changedTargetIds) {
      dirty = false;
      var originalData = _.filter(featuresToKeep, function(feature){
        return feature.roadLinkType === RoadLinkType.FloatingRoadLinkType.value;
      });
      if(action !== applicationModel.actionCalculated && action !== applicationModel.actionCalculating)
        clearFeaturesToKeep();
      if(_.isEmpty(changedTargetIds)) {
        clearAndReset(true);
        eventbus.trigger('linkProperties:selected', _.cloneDeep(originalData));
      }
      $('#adjacentsData').remove();
      if(applicationModel.isActiveButtons() || action === -1){
        if(action !== applicationModel.actionCalculated){
          applicationModel.setActiveButtons(false);
          eventbus.trigger('roadLinks:unSelectIndicators', originalData);
        }
        if (action){
          applicationModel.setContinueButton(false);
          eventbus.trigger('roadLinks:deleteSelection');
        }
        eventbus.trigger('roadLinks:fetched', action, !changedTargetIds);
        applicationModel.setContinueButton(true);
      }
    };

    var setLinkProperty = function(key, value) {
      dirty = true;
      _.each(current, function(selected) { selected.setLinkProperty(key, value); });
      eventbus.trigger('linkProperties:changed');
    };
    var setTrafficDirection = _.partial(setLinkProperty, 'trafficDirection');
    var setFunctionalClass = _.partial(setLinkProperty, 'functionalClass');
    var setLinkType = _.partial(setLinkProperty, 'linkType');

    var get = function() {
      return _.map(current, function(roadLink) {
        return roadLink.getData();
      });
    };

    var getCurrentFloatings = function(){
      return _.filter(current, function(curr){
        return curr.getData().floating === SelectionType.Floating.value;
      });
    };

    var getFeaturesToKeepFloatings = function() {
      return _.filter(getFeaturesToKeep(), function (fk) {
        return fk.floating === SelectionType.Floating.value;
      });
    };

    var getFeaturesToKeepUnknown = function() {
      return _.filter(getFeaturesToKeep(), function (fk) {
        return fk.anomaly === Anomaly.NoAddressGiven.value;
      });
    };

    var isLinkIdInFeaturesToKeep = function(linkId){
      var featuresToKeepLinkIds = _.map(getFeaturesToKeep(), function(fk){
        return fk.linkId;
      });
      return _.contains(featuresToKeepLinkIds, linkId);
    };

    var count = function() {
      return current.length;
    };

    var getFeaturesToKeep = function(){
      return _.cloneDeep(featuresToKeep);
    };

    var addToFeaturesToKeep = function(data4Display){
      if(_.isArray(data4Display)){
        featuresToKeep = featuresToKeep.concat(data4Display);
      } else {
        featuresToKeep.push(data4Display);
      }
    };

    var clearFeaturesToKeep = function() {
      if(applicationModel.selectionTypeIs(SelectionType.Floating) || applicationModel.selectionTypeIs(SelectionType.Unknown)){
        featuresToKeep = _.filter(featuresToKeep, function(feature){
          return feature.floating === SelectionType.Floating.value;
        });
      } else {
        featuresToKeep = [];
      }
    };

    var filterFeaturesAfterSimulation = function(features){
      var linkIdsToRemove = linkIdsToExclude();
      if(applicationModel.getCurrentAction() === applicationModel.actionCalculated){
        //Filter the features without said linkIds
        if(linkIdsToRemove.length !== 0){
          return _.reject(features, function(feature){
              return _.contains(linkIdsToRemove, feature.linkData.linkId);
          });
        } else {
          return features;
        }
      } else return features;
    };

    var linkIdsToExclude = function(){
      return _.chain(getFeaturesToKeepFloatings().concat(getFeaturesToKeepUnknown()).concat(getFeaturesToKeep())).map(function(feature){
        return feature.linkId;
      }).uniq().value();
    };

    return {
      getSources: getSources,
      setSources: setSources,
      addTargets: addTargets,
      resetTargets: resetTargets,
      getFeaturesToKeep: getFeaturesToKeep,
      addToFeaturesToKeep: addToFeaturesToKeep,
      clearFeaturesToKeep: clearFeaturesToKeep,
      transferringCalculation: transferringCalculation,
      getLinkFloatingAdjacents: getLinkFloatingAdjacents,
      getLinkAdjacents: getLinkAdjacents,
      close: close,
      open: open,
      openFloating: openFloating,
      openUnknown: openUnknown,
      isDirty: isDirty,
      setDirty: setDirty,
      saveTransfer: saveTransfer,
      cancel: cancel,
      cancelAfterDefloat: cancelAfterDefloat,
      cancelAndReselect: cancelAndReselect,
      clearAndReset: clearAndReset,
      setTrafficDirection: setTrafficDirection,
      setFunctionalClass: setFunctionalClass,
      setLinkType: setLinkType,
      setFloatingRoadMarker: setFloatingRoadMarker,
      getFloatingRoadMarker: getFloatingRoadMarker,
      getAnomalousMarkers: getAnomalousMarkers,
      setAnomalousMarkers: setAnomalousMarkers,
      get: get,
      count: count,
      getFeaturesToKeepFloatings: getFeaturesToKeepFloatings,
      getFeaturesToKeepUnknown: getFeaturesToKeepUnknown,
      filterFeaturesAfterSimulation: filterFeaturesAfterSimulation,
      linkIdsToExclude: linkIdsToExclude,
      extractDataForDisplay: extractDataForDisplay,
      setCurrent: setCurrent,
      processOL3Features: processOl3Features,
      revertToFloatingAddress: revertToFloatingAddress,
      canOpenByLinearLocationId: canOpenByLinearLocationId
    };
  };
})(this);