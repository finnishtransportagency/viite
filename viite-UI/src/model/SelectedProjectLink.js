import { eventbus } from '@utils/eventbus.js';
import { updateProjectMenu } from '@view/project-menu/ProjectMenu.js';
import { highlightProjectLinkLayerFeatures } from '@view/map/layers/ProjectLinkLayer.js';
/**
 * SelectedProjectLink - Manages selected project links state
 * 
 * Handles project link selection including:
 * - Single and multi-selection support
 * - Project link retrieval and management
 * - Dirty state tracking for modifications
 * - Error handling for problematic links
 * - Event triggering for selection changes
 */
export function SelectedProjectLink(projectLinkCollection) {

	let current = [];
	const me = this;
	let featuresToKeep = [];
	let dirty = false;

	const open = function (id, multiSelect) {
		if (multiSelect) {
			me.ids = projectLinkCollection.getMultiProjectLinks(id);
			current = projectLinkCollection.getProjectLink(me.ids);
		} else {
			current = projectLinkCollection.getProjectLink([id]);
			me.ids = [id];
		}
		const linkData = get(id);
		updateProjectMenu(linkData);
		highlightProjectLinkLayerFeatures();
	};

	const openWithErrorMessage = function (ids, errorMessage) {
		current = projectLinkCollection.getProjectLink(ids);
		me.ids = ids;
		eventbus.trigger('projectLink:errorClicked', get(ids[0]), errorMessage);
	};

	const isDirty = function () { return dirty; };
	const setDirty = function (value) { dirty = value; };

	const openCtrl = function (linkIds) {
		if (linkIds.length === 0) {
			cleanIds();
			current = [];
		} else {
			const added = _.difference(linkIds, me.ids);
			me.ids = linkIds;
			current = _.filter(current, function (link) {
				return _.includes(linkIds, link.getData().id || link.getData().linkId);
			}
			);
			current = current.concat(projectLinkCollection.getProjectLink(added));
			const linkData = get();
			updateProjectMenu(linkData);
			highlightProjectLinkLayerFeatures();
		}
	};

	function get(id) {
		const clicked = _.filter(current, function (c) {
			if (c.getData().id > 0) {
				return c.getData().id === id;
			} else {
				return c.getData().linkId === id;
			}
		});
		const others = _.filter(_.map(current, function (projectLink) {
			return projectLink.getData();
		}), function (link) {
			if (link.id > 0) {
				return link.id !== id;
			} else {
				return link.linkId !== id;
			}
		});
		if (!_.isUndefined(clicked[0])) {
			return [clicked[0].getData()].concat(others);
		}
		return others;
	}

	const setCurrent = function (newSelection) {
		current = newSelection;
	};

	const getCurrent = function () {
		return _.map(current, function (curr) {
			return curr.getData();
		});
	};

	const getFeaturesToKeep = function () {
		return featuresToKeep;
	};

	const addToFeaturesToKeep = function (data4Display) {
		if (_.isArray(data4Display)) {
			featuresToKeep = featuresToKeep.concat(data4Display);
		} else {
			featuresToKeep.push(data4Display);
		}
	};

	const clearFeaturesToKeep = function () {
		featuresToKeep = [];
	};

	const isSelected = function (linkId) {
		return _.includes(me.ids, linkId);
	};

	const clean = function () {
		current = [];
	};

	function cleanIds() {
		me.ids = [];
	}


	const isSplit = function () {
		return get().length > 1 && !_.isUndefined(get()[0].connectedLinkId);
	};

	const isMultiLink = function () {
		return get().length > 1 && _.isUndefined(get()[0].connectedLinkId);
	};

	return {
		open: open,
		openWithErrorMessage: openWithErrorMessage,
		openCtrl: openCtrl,
		get: get,
		clean: clean,
		cleanIds: cleanIds,
		isSelected: isSelected,
		setCurrent: setCurrent,
		getCurrent: getCurrent,
		getFeaturesToKeep: getFeaturesToKeep,
		addToFeaturesToKeep: addToFeaturesToKeep,
		clearFeaturesToKeep: clearFeaturesToKeep,
		isSplit: isSplit,
		isMultiLink: isMultiLink,
		isDirty: isDirty,
		setDirty: setDirty
	};
}
