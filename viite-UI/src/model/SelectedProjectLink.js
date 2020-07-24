(function (root) {
  root.SelectedProjectLink = function (projectLinkCollection) {

    var current = [];
    var me = this;
    var featuresToKeep = [];
    var dirty = false;

    var open = function (id, multiSelect) {
      if (multiSelect) {
        me.ids = projectLinkCollection.getMultiProjectLinks(id);
        current = projectLinkCollection.getProjectLink(me.ids);
      } else {
        current = projectLinkCollection.getProjectLink([id]);
        me.ids = [id];
      }
      eventbus.trigger('projectLink:clicked', get(id));
    };

    var openWithErrorMessage = function (ids, errorMessage) {
      current = projectLinkCollection.getProjectLink(ids);
      me.ids = ids;
      eventbus.trigger('projectLink:errorClicked', get(ids[0]), errorMessage);
    };

    var isDirty = function () {
      return dirty;
    };

    var setDirty = function (value) {
      dirty = value;
    };

    var openCtrl = function (linkIds) {
      if (linkIds.length === 0) {
        cleanIds();
        close();
      } else {
        var added = _.difference(linkIds, me.ids);
        me.ids = linkIds;
        current = _.filter(current, function (link) {
            return _.includes(linkIds, link.getData().id || link.getData().linkId);
          }
        );
        current = current.concat(projectLinkCollection.getProjectLink(added));
        eventbus.trigger('projectLink:clicked', get());
      }
    };

    var get = function (id) {
      var clicked = _.filter(current, function (c) {
        if (c.getData().id > 0) {
          return c.getData().id === id;
        } else {
          return c.getData().linkId === id;
        }
      });
      var others = _.filter(_.map(current, function (projectLink) {
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
    };

    var setCurrent = function (newSelection) {
      current = newSelection;
    };

    var getCurrent = function () {
      return _.map(current, function (curr) {
        return curr.getData();
      });
    };

    var getFeaturesToKeep = function () {
      return featuresToKeep;
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

    var isSelected = function (linkId) {
      return _.includes(me.ids, linkId);
    };

    var clean = function () {
      current = [];
    };

    var cleanIds = function () {
      me.ids = [];
    };

    var close = function () {
      current = [];
      eventbus.trigger('layer:enableButtons', true);
    };

    var isSplit = function () {
      return get().length > 1 && !_.isUndefined(get()[0].connectedLinkId);
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
      isSplit: isSplit,
      isMultiLink: isMultiLink,
      isDirty: isDirty,
      setDirty: setDirty
    };
  };
}(this));
