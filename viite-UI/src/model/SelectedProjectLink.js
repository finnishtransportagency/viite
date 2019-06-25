(function(root) {
    root.SelectedProjectLink = function(projectLinkCollection) {

        var current = [];
        var me = this;
        var ids = [];
        var featuresToKeep = [];
        var dirty = false;
        var LinkStatus = LinkValues.LinkStatus;
        var nearest = null;

        var open = function (id, multiSelect) {
            if (!multiSelect) {
                current = projectLinkCollection.getProjectLink([id]);
                me.ids = [id];
            } else {
                me.ids = projectLinkCollection.getMultiProjectLinks(id);
                current = projectLinkCollection.getProjectLink(me.ids);
            }
            eventbus.trigger('projectLink:clicked', get(id));
        };

        var openWithErrorMessage = function (ids, errorMessage) {
            current = projectLinkCollection.getProjectLink(ids);
            me.ids = ids;
            if (!_.isUndefined(current[0].getData().connectedLinkId) && current.length > 2) {
                openSplit(me.ids[0], true);
            } else {
                eventbus.trigger('projectLink:errorClicked', get(ids[0]), errorMessage);
            }
        };

        var getLinkMarker = function(linkList, statusList) {
            var filter = _.filter(linkList, function (link) {
                return _.contains(statusList,link.status);
            });
            if (filter.length > 1) {
                var min = _.min(_.map(filter, function (template) {
                    return template.startAddressM;
                }));

                var max = _.max(_.map(filter, function (template) {
                    return template.endAddressM;
                }));

                var toReturn = filter[0];
                toReturn.startAddressM = min;
                toReturn.endAddressM = max;
                return toReturn;
            }
            return filter[0];
        };

        var zeroLengthTerminated = function(adjacentLink) {
            return {
                connectedLinkId: adjacentLink.connectedLinkId,
                linkId: adjacentLink.linkId,
                status: LinkStatus.Terminated.value,
                startAddressM: 0,
                endAddressM: 0,
                startMValue: 0,
                endMValue: 0
            };
        };

        var isDirty = function() {
            return dirty;
        };

        var setDirty = function(value) {
            dirty = value;
        };

        var openCtrl = function(linkIds) {
            if (linkIds.length === 0) {
                cleanIds();
                close();
            } else {
                var added = _.difference(linkIds, me.ids);
                me.ids = linkIds;
                current = _.filter(current, function(link) {
                        return _.contains(linkIds, link.getData().id || link.getData().linkId);
                    }
                );
                current = current.concat(projectLinkCollection.getProjectLink(added));
                eventbus.trigger('projectLink:clicked', get());
            }
        };

        var get = function(id) {
            var clicked = _.filter(current, function (c) {
                if (c.getData().id > 0) {
                    return c.getData().id === id;
                } else {
                    return c.getData().linkId === id;
                }
            });
            var others = _.filter(_.map(current, function(projectLink) { return projectLink.getData(); }), function (link) {
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
        };

        var setCurrent = function(newSelection) {
            current = newSelection;
        };

        var getCurrent = function () {
            return _.map(current, function(curr) {
                return curr.getData();
            });
        };

        var getFeaturesToKeep = function(){
          return featuresToKeep;
        };

        var addToFeaturesToKeep = function(data4Display){
          if(_.isArray(data4Display)){
            featuresToKeep = featuresToKeep.concat(data4Display);
          } else {
            featuresToKeep.push(data4Display);
          }
        };

        var clearFeaturesToKeep = function() {
          featuresToKeep = [];
        };

        var isSelected = function(linkId) {
            return _.contains(me.ids, linkId);
        };

        var clean = function() {
            current = [];
        };

        var cleanIds = function() {
            me.ids = [];
        };

        var close = function() {
            current = [];
            eventbus.trigger('layer:enableButtons', true);
        };

        var setNearestPoint = function(point) {
            nearest = point;
        };

        var isMultiLink = function () {
            return get().length > 1 && _.isUndefined(get()[0].connectedLinkId);
        };

        return {
            open: open,
            openWithErrorMessage: openWithErrorMessage,
            openCtrl: openCtrl,
            get: get,
            clean: clean,
            cleanIds: cleanIds,
            close: close,
            isSelected: isSelected,
            setCurrent: setCurrent,
            getCurrent: getCurrent,
            getFeaturesToKeep: getFeaturesToKeep,
            addToFeaturesToKeep: addToFeaturesToKeep,
            clearFeaturesToKeep: clearFeaturesToKeep,
            isMultiLink: isMultiLink,
            isDirty: isDirty,
            setDirty: setDirty,
            setNearestPoint: setNearestPoint
        };
    };
})(this);
