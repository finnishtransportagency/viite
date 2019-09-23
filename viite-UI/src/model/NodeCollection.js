(function (root) {
  root.NodeCollection = function (backend, locationSearch, selectedNodePoint) {
    var me = this;
    var nodes = [];
    var nodesWithAttributes = [];
    var mapNodePointTemplates = [];
    var mapJunctionTemplates = [];
    var userNodePointTemplates = [];
    var userJunctionTemplates = [];

    this.getNodes = function() {
      return nodes;
    };

    this.setMapNodePointTemplates = function(list) {
      mapNodePointTemplates = list;
    };

    this.setMapJunctionTemplates = function(list) {
      mapJunctionTemplates = list;
    };

    this.setUserTemplates = function(list) {
      userNodePointTemplates = _.map(_.filter(list, function(nodePoint){
        return !_.isUndefined(nodePoint.nodePointTemplate) ;
      }), function(template){
        return template.nodePointTemplate;
      });
      userJunctionTemplates = _.map(_.filter(list, function (junction) {
        return !_.isUndefined(junction.junctionTemplate);
      }), function(template) {
        return template.junctionTemplate;
      });
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
      var nodes = _.filter(fetchResult, function(node){
        return !_.isUndefined(node.name) ;
      });
      var nodePointTemplates = _.unique(_.map(_.filter(fetchResult, function(node) {
        return !_.isUndefined(node.nodePointTemplate) ;
      }), function (nodePointTemp) {
        return nodePointTemp.nodePointTemplate;
      }), "id");
      var junctionPointTemplates = _.unique(_.map(_.filter(fetchResult, function(node) {
          return !_.isUndefined(node.junctionPointTemplate) ;
      }), function (junctionPointTemp) {
          return junctionPointTemp.junctionPointTemplate;
      }), "id");

      me.setNodes(nodes);
      me.setMapNodePointTemplates(nodePointTemplates);
      me.setMapJunctionTemplates(mapJunctionTemplates);
      eventbus.trigger('node:addNodesToMap', nodes, nodePointTemplates, junctionPointTemplates, zoom);
    });

    eventbus.on('templates:fetched', function(data) {
      me.setUserTemplates(data);
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
      var moveToLocation = function(nodePoint) {
        if (!_.isUndefined(nodePoint)) {
          locationSearch.search(nodePoint.roadNumber + ' ' + nodePoint.roadPartNumber + ' ' + nodePoint.addrM).then(function (results) {
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
        backend.getNodePointTemplateById(id, function (results) {
          moveToLocation(results.nodePointTemplate);
          selectedNodePoint.openNodePointTemplates(_.unique([results.nodePointTemplate], "id"));
        });
      } else {
        moveToLocation(nodePointTemplate);
        selectedNodePoint.openNodePointTemplates(_.unique([nodePointTemplate], "id"));
      }
    });

    eventbus.on('nodeSearchTool:clickJunctionTemplate', function(id) {
      applicationModel.addSpinner();
      var junctionTemplate = _.find(userJunctionTemplates, function (template) {
        return template.junctionId === parseInt(id);
      });
      locationSearch.search(junctionTemplate.roadNumber + ' ' + junctionTemplate.roadPartNumber + ' ' + junctionTemplate.addrM).then(function(results) {
        if (results.length >= 1) {
          var result = results[0];
          eventbus.trigger('coordinates:selected', { lon: result.lon, lat: result.lat, zoom: 12 });
        }
        applicationModel.removeSpinner();
    });
        selectedNodePoint.openJunctionPointTemplates(_.unique([junctionTemplate], "junctionId"));
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