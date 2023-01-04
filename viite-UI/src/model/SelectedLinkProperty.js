(function (root) {
  root.SelectedLinkProperty = function (backend, roadCollection) {
    var current = [];
    var dirty = false;
    var featuresToKeep = [];
    var LinkSource = ViiteEnumerations.LinkGeomSource;
    var SelectionType = ViiteEnumerations.SelectionType;


    var close = function () {
      if (!_.isEmpty(current) && !isDirty()) {
        _.each(current, function (selected) {
          selected.unselect();
        });
        applicationModel.setActiveButtons(false);
        eventbus.trigger('layer:enableButtons', true);
        eventbus.trigger('linkProperties:unselected');
        setCurrent([]);
        dirty = false;
        featuresToKeep = [];
        applicationModel.setActiveButtons(false);
      }
    };


    var setCurrent = function (data) {
      current = data;
    };

    var canOpenByLinearLocationId = function (linearLocationId) {
      return !_.isUndefined(linearLocationId) && linearLocationId > ViiteEnumerations.UnknownRoadId;
    };

    var extractDataForDisplay = function (selectedData) {

      var extractUniqueValues = function (dataToExtract, property) {
        return _.chain(dataToExtract).map(property).uniq().value().join(', ');
      };

      var isMultiSelect = selectedData.length > 1;
      var selectedLinkIds = {selectedLinks: _.map(selectedData, 'linkId')};
      var selectedIds = {selectedIds: _.map(selectedData, 'id')};
      var properties = _.merge(_.cloneDeep(_.head(selectedData)), selectedLinkIds, selectedIds);
      var roadLinkSource = {
        roadLinkSource: _.chain(selectedData).map(function (s) {
          return s.roadLinkSource;
        }).uniq().map(function (a) {
          var linkGeom = _.find(LinkSource, function (source) {
            return source.value === parseInt(a);
          });
          if (_.isUndefined(linkGeom))
            return LinkSource.Unknown.descriptionFI;
          else return linkGeom.descriptionFI;
        }).uniq().join(", ").value()
      };
      if (isMultiSelect) {
        var endRoadOnSelection = _.chain(selectedData).sortBy(function (sd) {
          return sd.endAddressM;
        }).last().value();
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
        var startAddressM = {startAddressM: _.minBy(_.chain(selectedData).map('startAddressM').uniq().value())};
        var endAddressM = {endAddressM: _.maxBy(_.chain(selectedData).map('endAddressM').uniq().value())};

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

    var isOnLinearLocation = function (data) {
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

    var openCtrl = function (linearLocationIds, linkIds, isCtrlClick, visibleFeatures) {
      if (isCtrlClick) {
        setCurrent([]);
        var addressedRoadLinkModels = roadCollection.getRoadLinkModelsByLinearLocationIds(linearLocationIds);
        var unAddressedRoadLinkModels = roadCollection.getByLinkIds(linkIds);
        var roadLinks = addressedRoadLinkModels.concat(unAddressedRoadLinkModels);
        setCurrent(roadLinks);
        _.forEach(current, function (selected) {
          selected.select();
        });
        roadCollection.setSelectedRoadLinkModels(roadLinks);
        processOlFeatures(visibleFeatures);
        eventbus.trigger('linkProperties:selected', extractDataForDisplay(get()));
      }
    };

    var open = function (data, isSingleClick, visibleFeatures) {
      if (isSingleClick) {
        openSingleClick(data);
      } else {
        openDoubleClick(data);
      }
      _.forEach(current, function (selected) {
        selected.select();
      });
      processOlFeatures(visibleFeatures);
      eventbus.trigger('linkProperties:selected', extractDataForDisplay(get()));
    };

    var processOlFeatures = function (visibleFeatures) {
      var selectedFeatures = _.filter(visibleFeatures, function (vf) {
        return (_.some(get().concat(featuresToKeep), function (s) {
          if (s.linearLocationId !== ViiteEnumerations.UnknownRoadId && s.linearLocationId !== ViiteEnumerations.NewRoadId) {
            return s.linearLocationId === vf.linkData.linearLocationId && s.mmlId === vf.linkData.mmlId;
          } else {
            return s.linkId === vf.linkData.linkId && s.mmlId === vf.linkData.mmlId && s.floating === vf.linkData.floating;
          }
        }));
      });
      eventbus.trigger('linkProperties:olSelected', selectedFeatures);
    };

    eventbus.on('linkProperties:closed', function () {
      eventbus.trigger('layer:enableButtons', true);
      applicationModel.setSelectionType(SelectionType.All);
      clearFeaturesToKeep();
    });

    eventbus.on('roadAddress:openProject', function (_result) {
      close();
    });

    var isDirty = function () {
      return dirty;
    };

    var setDirty = function (state) {
      dirty = state;
    };

    var cancel = function () {
      dirty = false;
      _.each(current, function (selected) {
        selected.cancel();
      });
      if (!_.isUndefined(_.head(current))) {
        var originalData = _.head(current).getData();
        eventbus.trigger('linkProperties:cancelled', _.cloneDeep(originalData));
        eventbus.trigger('roadLinks:clearIndicators');
      }
    };

    var get = function () {
      return _.map(current, function (roadLink) {
        return roadLink.getData();
      });
    };

    var count = function () {
      return current.length;
    };

    var getFeaturesToKeep = function () {
      return _.cloneDeep(featuresToKeep);
    };

    var addToFeaturesToKeep = function (data4Display) {
      if (_.isArray(data4Display)) {
        featuresToKeep = featuresToKeep.concat(data4Display);
      } else {
        featuresToKeep.push(data4Display);
      }
    };

    var clearFeaturesToKeep = function () {
      featuresToKeep = [];
    };

    var filterFeaturesAfterSimulation = function (features) {
      var linkIdsToRemove = linkIdsToExclude();
      if (linkIdsToRemove.length === 0) {
        return features;
      } else {
        return _.reject(features, function (feature) {
          return _.includes(linkIdsToRemove, feature.linkData.linkId);
        });
      }
    };

    var linkIdsToExclude = function () {
      return _.chain(getFeaturesToKeep().concat(roadCollection.getUnaddressedRoadLinkGroups())).map(function (feature) {
        return feature.linkId;
      }).uniq().value();
    };

    return {
      getFeaturesToKeep: getFeaturesToKeep,
      addToFeaturesToKeep: addToFeaturesToKeep,
      clearFeaturesToKeep: clearFeaturesToKeep,
      close: close,
      open: open,
      openCtrl: openCtrl,
      isDirty: isDirty,
      setDirty: setDirty,
      cancel: cancel,
      get: get,
      count: count,
      filterFeaturesAfterSimulation: filterFeaturesAfterSimulation,
      linkIdsToExclude: linkIdsToExclude,
      extractDataForDisplay: extractDataForDisplay,
      setCurrent: setCurrent,
      canOpenByLinearLocationId: canOpenByLinearLocationId
    };
  };
}(this));
