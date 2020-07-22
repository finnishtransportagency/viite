(function (root) {
  root.NodeCollection = function (backend, locationSearch) {
    var me = this;
    var nodes = [];
    var nodesWithAttributes = [];
    var mapTemplates = [];
    var userNodePointTemplates = [];
    var userJunctionTemplates = [];
    var saving = events.spinners.saving;
    var fetching = events.spinners.fetched;

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

    this.moveToLocation = function (template) {
      if (!_.isUndefined(template)) {
        applicationModel.addSpinner();
        locationSearch.search(template.roadNumber + ' ' + template.roadPartNumber + ' ' + template.addrM).then(function (results) {
          if (results.length >= 1) {
            var result = results[0];
            eventbus.trigger('coordinates:selected', {
              lon: result.lon,
              lat: result.lat,
              zoom: zoomlevels.minZoomForJunctions
            });

            eventbus.trigger('nodeLayer:fetch', function(fetchedNodesAndJunctions) {
              applicationModel.removeSpinner();
              if (_.has(fetchedNodesAndJunctions, 'nodePointTemplates') || _.has(fetchedNodesAndJunctions, 'junctionTemplates')) {
                var referencePoint = { x: parseFloat(result.lon.toFixed(3)), y: parseFloat(result.lat.toFixed(3)) };
                var templates = {
                  nodePoints: fetchedNodesAndJunctions.nodePointTemplates,
                  junctions:  fetchedNodesAndJunctions.junctionTemplates
                };
                eventbus.trigger('selectedNodesAndJunctions:openTemplates', {
                  nodePoints: _.filter(templates.nodePoints, function (nodePoint) {
                    return _.isEqual(nodePoint.coordinates, referencePoint);
                  }),
                  junctions: _.filter(templates.junctions, function (junction) {
                    return _.some(junction.junctionPoints, function (junctionPoint) {
                      return _.isEqual(junctionPoint.coordinates, referencePoint);
                    });
                  })
                });
              }
            });
          } else {
            applicationModel.removeSpinner();
          }
        });
      }
    };

    eventbus.on('node:fetched', function(fetchResult, zoom) {
      applicationModel.removeSpinner(fetching);
      var resultNodes = fetchResult.nodes;
      var templates = {
        nodePoints: fetchResult.nodePointTemplates,
        junctions: fetchResult.junctionTemplates
      };

      me.setNodes(resultNodes);
      me.setMapTemplates(templates);

      eventbus.trigger('node:addNodesToMap', resultNodes, templates, zoom);
    });

    eventbus.on('node:save', function (node) {
      var fail = function (message) {
        eventbus.trigger('node:saveFailed', message.errorMessage || 'Solmun tallennus epäonnistui.', saving);
      };

      if (!_.isUndefined(node)) {
        applicationModel.addSpinner(saving);
        if (node.id) {
          backend.updateNodeInfo(node, function (result) {
            if (result.success) {
              applicationModel.removeSpinner(saving);
              applicationModel.addSpinner(fetching);
              eventbus.trigger('node:saveSuccess');
            } else {
              fail(result);
            }
          }, fail);
        } else {
          backend.createNodeInfo(node, function (result) {
            if (result.success) {
              applicationModel.removeSpinner(saving);
              applicationModel.addSpinner(fetching);
              eventbus.trigger('node:saveSuccess');
            } else {
             fail(result);
            }
          }, fail);
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
      var nodePointTemplate = _.find(userNodePointTemplates, function (template) {
        return template.id === parseInt(id);
      });
      if (_.isUndefined(nodePointTemplate)) {
        backend.getNodePointTemplateById(id, function (nodePointTemplateFetched) {
          me.moveToLocation(nodePointTemplateFetched);
        });
      } else {
        me.moveToLocation(nodePointTemplate);
      }
    });

    eventbus.on('nodeSearchTool:clickJunctionTemplate', function(id) {
      var junctionTemplate = _.find(userJunctionTemplates, function (template) {
        return template.id === parseInt(id);
      });
      if (_.isUndefined(junctionTemplate)) {
        backend.getJunctionTemplateById(id, function (junctionTemplateFetched) {
          me.moveToLocation(junctionTemplateFetched);
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
}(this));
