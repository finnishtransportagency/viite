(function(root) {
    root.SelectedProjectLink = function(projectLinkCollection) {

        var current = [];
        var me = this;
        var ids = [];
        var dirty = false;
        var splitSuravage = {};
        var LinkStatus = LinkValues.LinkStatus;
        var preSplitData = null;
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

        var orderSplitParts = function(links) {
            var splitLinks = _.partition(links, function(link) {
                return !_.isUndefined(link.connectedLinkId);
            });
            return _.sortBy(splitLinks[0], function (s) { return s.status == LinkStatus.Transfer.value ? 1 : s.status; });
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

        var openSplit = function (linkid, multiSelect) {
            if (!multiSelect) {
                current = projectLinkCollection.getProjectLink([linkid]);
                me.ids = [linkid];
            } else {
                me.ids = projectLinkCollection.getMultiProjectLinks(linkid);
                current = projectLinkCollection.getProjectLink(me.ids);
            }
            var orderedSplitParts = orderSplitParts(get());
            var suravageA = getLinkMarker(orderedSplitParts, [LinkStatus.Transfer.value, LinkStatus.Unchanged.value]);
            var suravageB = getLinkMarker(orderedSplitParts, [LinkStatus.New.value]);
            var terminatedC = getLinkMarker(orderedSplitParts, [LinkStatus.Terminated.value]);
            suravageA.marker = "A";
            if (!suravageB) {
                suravageB = zeroLengthSplit(suravageA);
                suravageA.points = suravageA.originalGeometry;
            }
            suravageB.marker = "B";
            if (terminatedC) {
                terminatedC.marker = "C";
            }
            eventbus.trigger('split:projectLinks',  [suravageA, suravageB, terminatedC]);
            var splitPoint = GeometryUtils.connectingEndPoint(suravageA.points, suravageB.points);
            projectLinkCollection.getCutLine(suravageA.linkId, splitPoint);
        };

        var preSplitSuravageLink = function(suravage) {
            projectLinkCollection.preSplitProjectLinks(suravage, nearest);
        };

        var zeroLengthSplit = function(suravageLink) {
            return {
                connectedLinkId: suravageLink.connectedLinkId,
                linkId: suravageLink.linkId,
                startAddressM: 0,
                endAddressM: 0,
                startMValue: 0,
                endMValue: 0
            };
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

        var openShift = function(linkIds) {
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

        var getPreSplitData = function() {
            return preSplitData;
        };

        var setCurrent = function(newSelection) {
            current = newSelection;
        };

        var getCurrent = function () {
            return _.map(current, function(curr) {
                return curr.getData();
            });
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

        var revertSuravage = function() {
            splitSuravage = {};
        };

        var getNearestPoint = function () {
            return nearest;
        };

        var setNearestPoint = function(point) {
            nearest = point;
        };

        eventbus.on('projectLink:preSplitSuccess', function(data) {
            preSplitData = data;
            var suravageA = data.a;
            if (!suravageA) {
                suravageA = zeroLengthSplit(data.b);
            }
            var suravageB = data.b;
            if (!suravageB) {
                suravageB = zeroLengthSplit(suravageA);
                suravageB.status = LinkStatus.New.value;
            }
            var terminatedC = data.c;
            if (!terminatedC) {
                terminatedC = zeroLengthTerminated(suravageA);
            }
            me.ids = projectLinkCollection.getMultiProjectLinks(suravageA.linkId);
            current = projectLinkCollection.getProjectLink(_.flatten(me.ids));
            suravageA.marker = "A";
            suravageB.marker = "B";
            terminatedC.marker = "C";
            suravageA.text = "SUUNNITELMALINKKI";
            suravageB.text = "SUUNNITELMALINKKI";
            terminatedC.text = "NYKYLINKKI";
            suravageA.splitPoint = nearest;
            suravageB.splitPoint = nearest;
            terminatedC.splitPoint = nearest;
            applicationModel.removeSpinner();
            eventbus.trigger('split:projectLinks', [suravageA, suravageB, terminatedC]);
            eventbus.trigger('split:cutPointFeature', data.split, terminatedC);
        });

        var isSplit = function () {
            return get().length > 1 && !_.isUndefined(get()[0].connectedLinkId);
        };

        var isMultiLink = function () {
            return get().length > 1 && _.isUndefined(get()[0].connectedLinkId);
        };

        return {
            open: open,
            openWithErrorMessage: openWithErrorMessage,
            openShift: openShift,
            openSplit: openSplit,
            get: get,
            clean: clean,
            cleanIds: cleanIds,
            close: close,
            isSelected: isSelected,
            setCurrent: setCurrent,
            getCurrent: getCurrent,
            isSplit: isSplit,
            isMultiLink: isMultiLink,
            isDirty: isDirty,
            setDirty: setDirty,
            preSplitSuravageLink: preSplitSuravageLink,
            getPreSplitData: getPreSplitData,
            revertSuravage: revertSuravage,
            setNearestPoint: setNearestPoint
        };
    };
})(this);
