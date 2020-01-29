(function (root) {
  root.NodeCollection = function (backend, locationSearch) {
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

    this.getNodeByNodeNumber = function(nodeNumber) {
      return _.find(nodes, function (node) {
        return node.nodeNumber === nodeNumber;
      });
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

    this.getCoordinates = function (roadNumber, roadPartNumber, addrM, setCoordinates) {
      locationSearch.search(roadNumber + ' ' + roadPartNumber + ' ' + addrM).then(setCoordinates);
    };

    this.getNodePointTemplatesByCoordinates = function (coordinates) {
      return _.filter(mapNodePointTemplates, function (nodePointTemplate) {
        return _.isEqual(nodePointTemplate.coordinates, coordinates);
      });
    };

    this.getJunctionTemplateByCoordinates = function (coordinates) {
      return _.filter(mapJunctionTemplates, function (junctionTemplate) {
        return _.find(junctionTemplate.junctionPoints, function (junctionPoint) {
          return _.isEqual(junctionPoint.coordinates, coordinates);
        });
      });
    };

    this.moveToLocation = function (template) {
      if (!_.isUndefined(template)) {
        locationSearch.search(template.roadNumber + ' ' + template.roadPartNumber + ' ' + template.addrM).then(function (results) {
          if (results.length >= 1) {
            var result = results[0];
            eventbus.trigger('template:clicked', {lon: result.lon, lat: result.lat, zoom: 12});
            // TODO 2220 open node form with templates from link!
            // backend.getNodesAndJunctions({boundingBox: [result.lon, result.lat, result.lon, result.lat].join(","), zoom: zoomlevels.minZoomForJunctions}, function(fetchedNodesAndJunctions) {
            //   if (_.has(fetchedNodesAndJunctions, 'nodePointTemplates') || _.has(fetchedNodesAndJunctions, 'junctionTemplates')) {
            //     var referencePoint = { x: parseFloat(result.lon).toFixed(3), y: parseFloat(result.lat).toFixed(3) };
            //     eventbus.trigger('selectedNodesAndJunctions:openTemplates', {
            //       nodePoints: _.filter(fetchedNodesAndJunctions.nodePointTemplates, function (nodePoint) {
            //         return _.isEqual(nodePoint.coordinates, referencePoint);
            //       }),
            //       junction: _.first(_.filter(fetchedNodesAndJunctions.junctionTemplates, function (junction) {
            //         return _.isEqual(_.first(junction.junctionPoints).coordinates, referencePoint);
            //       }))
            //     });
            //   }
            // });
          } else {
            applicationModel.removeSpinner();
          }
        });
      }
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

    eventbus.on('node:save', function (node) {
      applicationModel.addSpinner();
      if (!_.isUndefined(node)) {
        var dataJson = {
          coordinates: node.coordinates,
          name: node.name,
          nodeType: Number(node.type),
          startDate: node.startDate
        };
        if (!_.isUndefined(node.id)) {
          dataJson = _.merge(dataJson, {
            id: node.id,
            nodeNumber: node.nodeNumber
          });
          backend.saveNodeInfo(dataJson, function (result) {
            if (result.success) {
              eventbus.trigger('node:saveSuccess');
            } else {
              eventbus.trigger('node:saveFailed', result.errorMessage || 'Solmun tallennus epäonnistui.');
            }
          }, function (result) {
            eventbus.trigger('node:saveFailed', result.errorMessage || 'Solmun tallennus epäonnistui.');
          });
        } else {
          backend.createNodeInfo(dataJson, function (result) {
            if (result.success) {
              eventbus.trigger('node:saveSuccess');
            } else {
              eventbus.trigger('node:saveFailed', result.errorMessage || 'Solmun lisääminen epäonnistui.');
            }
          }, function (result) {
            eventbus.trigger('node:saveFailed', result.errorMessage || 'Solmun lisääminen epäonnistui.');
          });
        }
      }
    });

    eventbus.on('templates:fetched', function(nodePointTemplates, junctionTemplates) {
      me.setUserTemplates(nodePointTemplates, junctionTemplates);
    });

    eventbus.on('nodeSearchTool:clickNode', function (index, map) {
      var node = nodesWithAttributes[index];
      map.getView().animate({
        center: [node.coordinates.x, node.coordinates.y],
        zoom: 12,
        duration: 1500
      });
    });

    eventbus.on('nodeSearchTool:clickNodePointTemplate', function(id) {
      applicationModel.addSpinner();
      var nodePointTemplate = _.find(userNodePointTemplates, function (template) {
        return template.id === parseInt(id);
      });
      if (_.isUndefined(nodePointTemplate)) {
        backend.getNodePointTemplateById(id, function (nodePointTemplate) {
          me.moveToLocation(nodePointTemplate);
        });
      } else {
        me.moveToLocation(nodePointTemplate);
      }
    });

    eventbus.on('nodeSearchTool:clickJunctionTemplate', function(id) {
      applicationModel.addSpinner();
      var junctionTemplate = _.find(userJunctionTemplates, function (template) {
        return template.id === parseInt(id);
      });
      if (_.isUndefined(junctionTemplate)) {
        backend.getJunctionTemplateById(id, function (junctionTemplate) {
          me.moveToLocation(junctionTemplate);
        });
      } else {
        me.moveToLocation(junctionTemplate);
      }
    });

    eventbus.on('nodeSearchTool:refreshView', function (map) {
      var coords = [];
      _.each(nodesWithAttributes, function(node) {
        coords.push([node.coordinates.x, node.coordinates.y]);
      });
      map.getView().fit(new ol.geom.Polygon([coords]), map.getSize());
    });
  };
})(this);