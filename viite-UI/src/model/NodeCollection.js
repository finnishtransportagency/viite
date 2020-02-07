(function (root) {
  root.NodeCollection = function (backend, locationSearch) {
    var me = this;
    var nodes = [];
    var nodesWithAttributes = [];
    var mapTemplates = [];
    var userNodePointTemplates = [];
    var userJunctionTemplates = [];

    this.setMapTemplates = function(templates) {
      mapTemplates = templates;
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

    this.getNodePointTemplatesByCoordinates = function (coordinates) {
      return _.filter(mapTemplates.nodePoints, function (nodePointTemplate) {
        return _.isEqual(nodePointTemplate.coordinates, coordinates);
      });
    };

    this.getJunctionTemplateByCoordinates = function (coordinates) {
      return _.filter(mapTemplates.junctions, function (junctionTemplate) {
        return _.find(junctionTemplate.junctionPoints, function (junctionPoint) {
          return _.isEqual(junctionPoint.coordinates, coordinates);
        });
      });
    };

    fetchCoordinates = function (collection, callback) {
      if (!_.isEmpty(collection)) {
        Promise.all(_.map(collection, function (template) {
          return locationSearch.search(template.roadNumber + ' ' + template.roadPartNumber + ' ' + template.addrM);
        }))
          .then(function (results) {
            _.partition(_.flatMap(_.zip(_.flatMap(results), collection), _.spread(function (coordinates, template) {
              // add coordinates to templates;
              return _.merge(template, {
                coordinates: {
                  x: parseFloat(coordinates.lon.toFixed(3)),
                  y: parseFloat(coordinates.lat.toFixed(3))
                }
              });
            })), function (splittedTemplate) {
              return _.has(splittedTemplate, 'junctionId');
            });

            callback();
          });
      }
    };

    this.moveToLocation = function (template) {
      if (!_.isUndefined(template)) {
        locationSearch.search(template.roadNumber + ' ' + template.roadPartNumber + ' ' + template.addrM).then(function (results) {
          if (results.length >= 1) {
            var result = results[0];
            eventbus.trigger('coordinates:selected', {
              lon: result.lon,
              lat: result.lat,
              zoom: zoomlevels.minZoomForJunctions
            });
            backend.getNodesAndJunctions({
              boundingBox: [result.lon, result.lat, result.lon, result.lat].join(","),
              zoom: zoomlevels.minZoomForJunctions
            }, function (fetchedNodesAndJunctions) {
              if (_.has(fetchedNodesAndJunctions, 'nodePointTemplates') || _.has(fetchedNodesAndJunctions, 'junctionTemplates')) {
                var referencePoint = { x: parseFloat(result.lon.toFixed(3)), y: parseFloat(result.lat.toFixed(3)) };
                var templates = {
                  nodePoints: fetchedNodesAndJunctions.nodePointTemplates,
                  junctions:  fetchedNodesAndJunctions.junctionTemplates
                };
                fetchCoordinates(_.concat(templates.nodePoints, _.flatMap(templates.junctions, 'junctionPoints')), function () {
                  eventbus.trigger('selectedNodesAndJunctions:openTemplates', {
                    nodePoints: _.filter(templates.nodePoints, function (nodePoint) {
                      return _.isEqual(nodePoint.coordinates, referencePoint);
                    }),
                    junctions: _.filter(templates.junctions, function (junction) {
                      return !_.find(junction.junctionPoints, function (junctionPoint) {
                        return !_.isEqual(junctionPoint.coordinates, referencePoint);
                      });
                    })
                  });

                  applicationModel.removeSpinner();
              });
              }
            });
          } else {
            applicationModel.removeSpinner();
          }
        });
      }
    };

    eventbus.on('node:fetchCoordinates', function (node, callback) {
      fetchCoordinates(_.concat(node.nodePoints, _.flatMap(node.junctions, 'junctionPoints')), callback || applicationModel.removeSpinner);
    });

    eventbus.on('node:fetched', function(fetchResult, zoom) {
      var nodes = fetchResult.nodes;
      var templates = {
        nodePoints: fetchResult.nodePointTemplates,
        junctions: fetchResult.junctionTemplates
      };

      //  add nodes to map
      me.setNodes(nodes);
      eventbus.trigger('node:addNodesToMap', nodes, { nodePoints: [], junctions: [] }, zoom);

      //  add templates to map
      me.setMapTemplates(templates);
      eventbus.trigger('node:fetchCoordinates', templates, function () {
        eventbus.trigger('node:addNodesToMap', [], templates, zoom);
      });
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