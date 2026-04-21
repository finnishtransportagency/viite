/**
 * SelectedLinkProperty - Manages selected road link properties
 * 
 * Handles selected link functionality including:
 * - Multi-selection support for road links
 * - Property extraction and display
 * - Dirty state tracking for modifications
 * - Link property validation and editing
 * - Backend integration for link operations
 */
import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { dateutil } from '@utils/DateUtils.js';
import { setSelectionType } from '@model/ApplicationModel.js';

export function SelectedLinkProperty(roadCollection) {
    let current = [];
    let dirty = false;
    let featuresToKeep = [];
    const LinkSource = ViiteEnumerations.LinkGeomSource;
    const SelectionType = ViiteEnumerations.SelectionType;


    const close = function () {
      if (!_.isEmpty(current) && !isDirty()) {
        _.each(current, function (selected) {
          selected.unselect();
        });
        setCurrent([]);
        dirty = false;
        featuresToKeep = [];
        eventbus.trigger('linkProperties:unselected');
      }
    };


    function setCurrent(data) {
      current = data;
    }

    const canOpenByLinearLocationId = function (linearLocationId) {
      return !_.isUndefined(linearLocationId) && linearLocationId > ViiteEnumerations.UnknownRoadId;
    };

    const extractDataForDisplay = function (selectedData) {

      const extractUniqueValues = function (dataToExtract, property) {
        return _.chain(dataToExtract).map(property).uniq().value().join(', ');
      };

      const isMultiSelect = selectedData.length > 1;
      const selectedLinkIds = {selectedLinks: _.map(selectedData, 'linkId')};
      const selectedIds = {selectedIds: _.map(selectedData, 'id')};
      let properties = _.merge(_.cloneDeep(_.head(selectedData)), selectedLinkIds, selectedIds);
      const roadLinkSource = {
        roadLinkSource: _.chain(selectedData).map(function (s) {
          return s.roadLinkSource;
        }).uniq().map(function (a) {
          const linkGeom = _.find(LinkSource, function (source) {
            return source.value === parseInt(a, 10);
          });
          if (_.isUndefined(linkGeom))
            return LinkSource.Unknown.descriptionFI;
          else return linkGeom.descriptionFI;
        }).uniq().join(", ").value()
      };
      if (isMultiSelect) {
        const endRoadOnSelection = _.chain(selectedData).sortBy(function (sd) {
          return sd.addrMRange.end;
        }).last().value();
        const ambiguousFields = ['maxAddressNumberLeft', 'maxAddressNumberRight', 'minAddressNumberLeft', 'minAddressNumberRight',
          'municipalityCode', 'verticalLevel', 'roadNameFi', 'roadNameSe', 'roadNameSm', 'modifiedAt', 'modifiedBy',
          'endDate', 'discontinuity', 'addrMRange.start', 'addrMRange.end'];
        properties = _.omit(properties, ambiguousFields);
        const latestModified = dateutil.extractLatestModifications(selectedData);
        const municipalityCodes = {municipalityCode: extractUniqueValues(selectedData, 'municipalityCode')};
        const verticalLevels = {verticalLevel: extractUniqueValues(selectedData, 'verticalLevel')};
        const roadPartNumbers = {roadPartNumber: extractUniqueValues(selectedData, 'roadPartNumber')};
        const elyCodes = {elyCode: extractUniqueValues(selectedData, 'elyCode')};
        const evkCodes = {evkCode: extractUniqueValues(selectedData, 'evkCode')};
        // TODO Check that merge was done correctly
        const discontinuity = {discontinuity: parseInt(extractUniqueValues([endRoadOnSelection], 'discontinuity'), 10)};
        const addrMRange = {
          addrMRange: {
            start: _.minBy(_.chain(selectedData).map('addrMRange.start').uniq().value()),
            end: _.maxBy(_.chain(selectedData).map('addrMRange.end').uniq().value())
          }
        };

        const roadNames = {
          roadNameFi: extractUniqueValues(selectedData, 'roadNameFi'),
          roadNameSe: extractUniqueValues(selectedData, 'roadNameSe'),
          roadNameSm: extractUniqueValues(selectedData, 'roadNameSm')
        };
        properties = _.merge(properties, latestModified, municipalityCodes, verticalLevels, roadPartNumbers, roadNames, elyCodes, evkCodes, addrMRange, discontinuity);
      }
      properties = _.merge(properties, roadLinkSource);
      return properties;
    };

    const isOnLinearLocation = function (data) {
      return !_.isUndefined(data) && !_.isUndefined(data.linearLocationId) && data.linearLocationId !== 0;
    };

    const openSingleClick = function (data) {
      if (isOnLinearLocation(data)) {
        setCurrent(roadCollection.getGroupByLinearLocationId(data.linearLocationId));
      } else {
        setCurrent(roadCollection.getGroupByLinkId(data.linkId));
      }
    };

    const openDoubleClick = function (data) {
      if (isOnLinearLocation(data)) {
        setCurrent(roadCollection.getByLinearLocationId(data.linearLocationId));
      } else {
        setCurrent(roadCollection.getByLinkId(data.linkId));
      }
    };

    const openCtrl = function (linearLocationIds, linkIds, isCtrlClick, visibleFeatures) {
      if (isCtrlClick) {
        setCurrent([]);
        const addressedRoadLinkModels = roadCollection.getRoadLinkModelsByLinearLocationIds(linearLocationIds);
        const unAddressedRoadLinkModels = roadCollection.getByLinkIds(linkIds);
        const roadLinks = addressedRoadLinkModels.concat(unAddressedRoadLinkModels);
        setCurrent(roadLinks);
        _.forEach(current, function (selected) {
          selected.select();
        });
        roadCollection.setSelectedRoadLinkModels(roadLinks);
        processOlFeatures(visibleFeatures);
        eventbus.trigger('linkProperties:selected', extractDataForDisplay(get()));
      }
    };

    const open = function (data, isSingleClick, visibleFeatures) {
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

    function processOlFeatures(visibleFeatures) {
      const selectedFeatures = _.filter(visibleFeatures, function (vf) {
        return (_.some(get().concat(featuresToKeep), function (s) {
          if (s.linearLocationId !== ViiteEnumerations.UnknownRoadId && s.linearLocationId !== ViiteEnumerations.NewRoadId) {
            return s.linearLocationId === vf.linkData.linearLocationId && s.mmlId === vf.linkData.mmlId;
          } else {
            return s.linkId === vf.linkData.linkId && s.mmlId === vf.linkData.mmlId && s.floating === vf.linkData.floating;
          }
        }));
      });
      eventbus.trigger('linkProperties:olSelected', selectedFeatures);
    }

    eventbus.on('linkProperties:closed', function () {
      setSelectionType(SelectionType.All);
      clearFeaturesToKeep();
    });

    eventbus.on('roadAddress:openProject', function (_result) {
      close();
    });

    function isDirty() {
      return dirty;
    }

    const setDirty = function (state) {
      dirty = state;
    };

    const cancel = function () {
      dirty = false;
      _.each(current, function (selected) {
        selected.cancel();
      });
      if (!_.isUndefined(_.head(current))) {
        const originalData = _.head(current).getData();
        eventbus.trigger('linkProperties:cancelled', _.cloneDeep(originalData));
      }
    };

    function get() {
      return _.map(current, function (roadLink) {
        return roadLink.getData();
      });
    }

    const count = function () {
      return current.length;
    };

    const getFeaturesToKeep = function () {
      return _.cloneDeep(featuresToKeep);
    };

    const addToFeaturesToKeep = function (data4Display) {
      if (_.isArray(data4Display)) {
        featuresToKeep = featuresToKeep.concat(data4Display);
      } else {
        featuresToKeep.push(data4Display);
      }
    };

    function clearFeaturesToKeep() {
      featuresToKeep = [];
    }

    const filterFeaturesAfterSimulation = function (features) {
      const linkIdsToRemove = linkIdsToExclude();
      if (linkIdsToRemove.length === 0) {
        return features;
      } else {
        return _.reject(features, function (feature) {
          return _.includes(linkIdsToRemove, feature.linkData.linkId);
        });
      }
    };

    function linkIdsToExclude() {
      return _.chain(getFeaturesToKeep().concat(roadCollection.getUnaddressedRoadLinkGroups())).map(function (feature) {
        return feature.linkId;
      }).uniq().value();
    }

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
}
