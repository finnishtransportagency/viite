(function (root) {
  root.NodeCollection = function (backend, locationSearch, selectedNodesAndJunctions) {
    var me = this;
    var nodes = [];
    var nodesWithAttributes = [];
    var mapNodePointTemplates = [];
    var mapJunctionTemplates = [];
    var userNodePointTemplates = [];
    var userJunctionTemplates = [];

    this.setMapNodePointTemplates = function(list) {
      mapNodePointTemplates = list;
    };

    this.setMapJunctionTemplates = function(list) {
      mapJunctionTemplates = list;
    };

    this.setUserTemplates = function(nodePointTemplates, junctionTemplates) {
      userNodePointTemplates = nodePointTemplates;
      userJunctionTemplates = junctionTemplates;
    };

    this.setNodes = function(list) {
      nodes = list;
    };

    this.getNodesWithAttributes = function() {
      return nodesWithAttributes;
    };

    this.setNodesWithAttributes = function(list) {
      nodesWithAttributes = list;
    };

    this.getNodesByRoadAttributes = function(roadAttributes) {
      return backend.getNodesByRoadAttributes(roadAttributes, function (result) {
        if (result.success) {
          var searchResult = result.nodes;
          me.setNodesWithAttributes(searchResult);
          eventbus.trigger('nodeSearchTool:fetched', searchResult.length);
        } else {
          applicationModel.removeSpinner();
          new ModalConfirm(result.errorMessage);
        }
      });
    };

    eventbus.on('node:fetched', function(fetchResult, zoom) {
      var nodes = fetchResult.nodes;
      var nodePointTemplates = fetchResult.nodePointTemplates;
      var junctionTemplates = fetchResult.junctionTemplates;

      me.setNodes(nodes);
      me.setMapNodePointTemplates(nodePointTemplates);
      me.setMapJunctionTemplates(junctionTemplates);
      eventbus.trigger('node:addNodesToMap', nodes, nodePointTemplates, junctionTemplates, zoom);
    });

    eventbus.on('templates:fetched', function(nodePointTemplates, junctionTemplates) {
      me.setUserTemplates(nodePointTemplates, junctionTemplates);
    });

    eventbus.on('nodeSearchTool:clickNode', function (index, map) {
      var node = nodesWithAttributes[index];
      map.getView().animate({
        center: [node.coordX, node.coordY],
        zoom: 12,
        duration: 1500
      });
    });

    eventbus.on('nodeSearchTool:clickNodePointTemplate', function(id) {
      var moveToLocation = function(nodePointTemplate) {
        if (!_.isUndefined(nodePointTemplate)) {
          locationSearch.search(nodePointTemplate.roadNumber + ' ' + nodePointTemplate.roadPartNumber + ' ' + nodePointTemplate.addrM).then(function (results) {
            if (results.length >= 1) {
              var result = results[0];
              eventbus.trigger('coordinates:selected', {lon: result.lon, lat: result.lat, zoom: 12});
            }
            applicationModel.removeSpinner();
          });
        }
      };

      applicationModel.addSpinner();
      var nodePointTemplate = _.find(userNodePointTemplates, function (template) {
        return template.id === parseInt(id);
      });
      if (_.isUndefined(nodePointTemplate)) {
        backend.getNodePointTemplateById(id, function (nodePointTemplate) {
          moveToLocation(nodePointTemplate);
          selectedNodesAndJunctions.openNodePointTemplate([nodePointTemplate]);
        });
      } else {
        moveToLocation(nodePointTemplate);
        selectedNodesAndJunctions.openNodePointTemplate([nodePointTemplate]);
      }
    });

    eventbus.on('nodeSearchTool:clickJunctionTemplate', function(id) {
      applicationModel.addSpinner();
      var junctionTemplate = _.find(userJunctionTemplates, function (template) {
        return template.id === parseInt(id);
      });
      if (!_.isUndefined(junctionTemplate)) {
        locationSearch.search(junctionTemplate.roadNumber + ' ' + junctionTemplate.roadPartNumber + ' ' + junctionTemplate.addrM).then(function (results) {
          if (results.length >= 1) {
            var result = results[0];
            eventbus.trigger('coordinates:selected', {lon: result.lon, lat: result.lat, zoom: zoomlevels.minZoomForJunctions});
          }
          applicationModel.removeSpinner();
        });
        selectedNodesAndJunctions.openJunctionTemplate(_.first(_.uniq([junctionTemplate], "id")));
      } else {
        applicationModel.removeSpinner();
      }
    });

    eventbus.on('nodeSearchTool:refreshView', function (map) {
      var coords = [];
      _.each(nodesWithAttributes, function(node) {
        coords.push([node.coordX, node.coordY]);
      });
      map.getView().fit(new ol.geom.Polygon([coords]), map.getSize());
    });
  };
})(this);