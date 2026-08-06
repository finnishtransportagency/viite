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
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { dateutil } from '@utils/DateUtils.js';
import { setMainMenuState } from '@view/MainMenu.js';

export function SelectedLinkProperty(roadCollection) {
	let current = [];
	let dirty = false;
	let linkPropertyLayer = null;

	const LinkSource = ViiteEnumerations.LinkGeomSource;

	const close = function () {
		if (!_.isEmpty(current) && !isDirty()) {
			setCurrent([]);
			dirty = false;
			linkPropertyLayer?.onLinkPropertyUnselected();
			setMainMenuState('main');
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
					return source.value === parseInt(a);
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
			const discontinuity = {discontinuity: parseInt(extractUniqueValues([endRoadOnSelection], 'discontinuity'))};
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
			roadCollection.setSelectedRoadLinkModels(roadLinks);
			processOlFeatures(visibleFeatures);
			const selectedDisplayData = extractDataForDisplay(get());
			linkPropertyLayer?.onLinkPropertySelected(selectedDisplayData);
			setMainMenuState('linkInfo', selectedDisplayData);
		}
	};

	const open = function (data, isSingleClick, visibleFeatures) {
		if (isSingleClick) {
			openSingleClick(data);
		} else {
			openDoubleClick(data);
		}
		processOlFeatures(visibleFeatures);
		const selectedDisplayData = extractDataForDisplay(get());
		linkPropertyLayer?.onLinkPropertySelected(selectedDisplayData);
		setMainMenuState('linkInfo', selectedDisplayData);
	};

	function processOlFeatures(visibleFeatures) {
		const selectedFeatures = _.filter(visibleFeatures, function (vf) {
			return (_.some(get(), function (s) {
				if (s.linearLocationId !== ViiteEnumerations.UnknownRoadId && s.linearLocationId !== ViiteEnumerations.NewRoadId) {
					return s.linearLocationId === vf.linkData.linearLocationId && s.mmlId === vf.linkData.mmlId;
				} else {
					return s.linkId === vf.linkData.linkId && s.mmlId === vf.linkData.mmlId && s.floating === vf.linkData.floating;
				}
			}));
		});
		linkPropertyLayer?.onLinkPropertySelected(selectedFeatures);
	}

	function isDirty() {
		return dirty;
	}

	function get() {
		return _.map(current, function (roadLink) {
			return roadLink.getData();
		});
	}

	const count = function () {
		return current.length;
	};

	const setLinkPropertyLayer = function (layer) { linkPropertyLayer = layer; };


	return {
		setLinkPropertyLayer: setLinkPropertyLayer,
		close: close,
		open: open,
		openCtrl: openCtrl,
		isDirty: isDirty,
		get: get,
		count: count,
		canOpenByLinearLocationId: canOpenByLinearLocationId
	};
}
