(function(root) {
    root.NodeLayer = function (map, roadLayer, nodeCollection, roadCollection, linkPropertiesModel, applicationModel) {
      Layer.call(this, map);
      var me = this;
      var indicatorVector = new ol.source.Vector({});
      var directionMarkerVector = new ol.source.Vector({});
      var nodeMarkerVector = new ol.source.Vector({});
      var junctionMarkerVector = new ol.source.Vector({});
      var nodePointTemplateVector = new ol.source.Vector({});
      var junctionTemplateVector = new ol.source.Vector({});
      var isActiveLayer = false;
      var cachedMarker = null;

      var SelectionType = LinkValues.SelectionType;
      var Anomaly = LinkValues.Anomaly;
      var SideCode = LinkValues.SideCode;
      var RoadZIndex = LinkValues.RoadZIndex;

      var indicatorLayer = new ol.layer.Vector({
        source: indicatorVector,
        name: 'indicatorLayer',
        zIndex: RoadZIndex.IndicatorLayer.value
      });
      indicatorLayer.set('name', 'indicatorLayer');

      var directionMarkerLayer = new ol.layer.Vector({
        source: directionMarkerVector,
        name: 'directionMarkerLayer',
        zIndex: RoadZIndex.VectorLayer.value
      });
      directionMarkerLayer.set('name', 'directionMarkerLayer');

      var nodeMarkerLayer = new ol.layer.Vector({
        source: nodeMarkerVector,
        name: 'nodeMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value
      });
      nodeMarkerLayer.set('name', 'nodeMarkerLayer');

      var junctionMarkerLayer = new ol.layer.Vector({
        source: junctionMarkerVector,
        name: 'junctionMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value + 1
      });
      junctionMarkerLayer.set('name', 'junctionMarkerLayer');

      var nodePointTemplateLayer = new ol.layer.Vector({
        source: nodePointTemplateVector,
        name: 'nodePointTemplateLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1
      });
      nodePointTemplateLayer.set('name', 'nodePointTemplateLayer');

      var junctionTemplateLayer = new ol.layer.Vector({
        source: junctionTemplateVector,
        name: 'junctionTemplateLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1
      });
      junctionTemplateLayer.set('name', 'junctionTemplateLayer');

      var layers = [directionMarkerLayer, nodeMarkerLayer, junctionMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer];

      var setGeneralOpacity = function (opacity) {
        roadLayer.layer.setOpacity(opacity);
        indicatorLayer.setOpacity(opacity);
        directionMarkerLayer.setOpacity(opacity);
        nodeMarkerLayer.setOpacity(opacity);
        junctionMarkerLayer.setOpacity(opacity);
        nodePointTemplateLayer.setOpacity(opacity);
        junctionTemplateLayer.setOpacity(opacity);
      };
      /**
       * Type of interactions we want the map to be able to respond.
       * A selected feature is moved to a new/temporary layer out of the default roadLayer.
       * This interaction is restricted to a single click (there is a 250 ms enforced
       * delay between single clicks in order to differentiate from double click).
       * @type {ol.interaction.Select}
       */
      var junctionPointTemplateClick = new ol.interaction.Select({
        //Multi is the one en charge of defining if we select just the feature we clicked or all the overlapping
        multi: false,
        //This will limit the interaction to the specific layer, in this case the layer where the roadAddressLinks are drawn
        layers: [junctionTemplateLayer],
        //Limit this interaction to the singleClick
        condition: ol.events.condition.singleClick
      });
      junctionPointTemplateClick.set('name','junctionPointTemplateClickInteractionNL');



      /**
       * We now declare what kind of custom actions we want when the interaction happens.
       * Note that 'select' is triggered when a feature is either selected or deselected.
       * The event holds the selected features in the events.selected and the deselected in event.deselected.
       *
       * In this particular case we are fetching every node point template marker in view and
       * sending them to the selectedNode.open for further processing.
       */

      junctionPointTemplateClick.on('select', function (event) {
        // var selected = _.find(event.selected, function (selectionTarget) {
        //   return !_.isUndefined(selectionTarget.nodePointTemplateInfo);
        // });
        // sets selected mode by default - in case a node point is clicked without any mode
        var selected = _.find(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.junctionPointTemplateInfo);
        });
        eventbus.trigger('junctionEdit:selected', selected.junctionPointTemplateInfo.junctionId);
        // if (!_.isUndefined(selected) && applicationModel.selectedToolIs(LinkValues.Tool.Unknown.value)) {
        //   applicationModel.setSelectedTool(LinkValues.Tool.SelectNode.value);
        // }
        // if (applicationModel.selectedToolIs(LinkValues.Tool.SelectNode.value) && !_.isUndefined(selected)) {
        //   //selectedNodePoint.open(selected.junctionPointTemplateInfo);
        //   eventbus.trigger('junctionEdit:selected', selected);
        // } else {
        //   //selectedNodePoint.close();
        // }

      });
      /**
       * Simple method that will add various open layers 3 features to a selection.
       * @param ol3Features
       */
      var addNodeFeaturesToSelection = function (ol3Features) {
        var olUids = _.map(junctionPointTemplateClick.getFeatures().getArray(), function(feature){
          return feature.ol_uid;
        });
        _.each(ol3Features, function(feature){
          if (!_.contains(olUids, feature.ol_uid)) {
            junctionPointTemplateClick.getFeatures().push(feature);
            olUids.push(feature.ol_uid); // prevent adding duplicate entries
          }
        });
      };

      /**
       * Event triggered by the selectedNode.open() returning all the open layers 3 features
       * that need to be included in the selection.
       */
      me.eventListener.listenTo(eventbus, 'node:ol3Selected', function(ol3Features){
        addNodeFeaturesToSelection(ol3Features);
      });

      /**
       * This will add all the following interactions from the map:
       * - nodePointTemplateClick
       */
      var addClickInteractions = function () {
        map.addInteraction(junctionPointTemplateClick);
      };

      // /**
      //  * This will remove all the following interactions from the map:
      //  * - nodePointTemplateClick
      //  */
      // var removeSelectInteractions = function() {
      //   map.removeInteraction(nodePointTemplateClick);
      // };

      // We add the defined interactions to the map.
      addClickInteractions();

      var redraw = function () {
        if(applicationModel.getSelectedLayer() === 'node') {

          cachedMarker = new LinkPropertyMarker();
          var underConstructionLinks = roadCollection.getUnderConstructionLinks();
          var roadLinks = _.reject(roadCollection.getAll(), function (rl) {
            return _.contains(_.map(underConstructionLinks, function (sl) {
              return sl.linkId;
            }), rl.linkId);
          });
          me.clearLayers([directionMarkerLayer, nodeMarkerLayer, junctionMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer]);

          if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForRoadNetwork) {

            var directionRoadMarker = _.filter(roadLinks, function (roadLink) {
              return roadLink.floating !== SelectionType.Floating.value && roadLink.anomaly !== Anomaly.NoAddressGiven.value && roadLink.anomaly !== Anomaly.GeometryChanged.value && (roadLink.sideCode === SideCode.AgainstDigitizing.value || roadLink.sideCode === SideCode.TowardsDigitizing.value);
            });
            _.each(directionRoadMarker, function (directionLink) {
              var marker = cachedMarker.createMarker(directionLink);
              if (zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers)
                directionMarkerLayer.getSource().addFeature(marker);
            });
          }

          if (applicationModel.getCurrentAction() === -1) {
            applicationModel.removeSpinner();
          }
        }
      };

      this.refreshView = function () {
        //Generalize the zoom levels as the resolutions and zoom levels differ between map tile sources
        roadCollection.reset();
        roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
        roadLayer.layer.changed();
      };

      this.layerStarted = function (eventListener) {
        eventListener.listenTo(eventbus, 'roadLinks:fetched', function () {
          if(applicationModel.getSelectedLayer() === 'node') {
            redraw();
          }
        });

        eventListener.listenTo(eventbus, 'node:addNodesToMap', function(nodes, nodePointTemplates, junctionPointTemplates, zoom){
          if (parseInt(zoom, 10) >= zoomlevels.minZoomForNodes) {
            _.each(nodes, function (node) {
              var nodeMarker = new NodeMarker();
              nodeMarkerLayer.getSource().addFeature(nodeMarker.createNodeMarker(node));
            });
          }

          if (parseInt(zoom, 10) >= zoomlevels.minZoomForJunctions){
            var roadLinksWithValues = _.reject(roadCollection.getAll(), function (rl) {
              return rl.roadNumber === 0;
            });
            var junctions = [];
            var junctionPoints = [];
            var junctionPointsWithRoadlinks;
            _.map(nodes, function(node){
              junctions =  junctions.concat(node.junctions);
            });

            _.map(junctions, function (junction) {
              junctionPoints = junctionPoints.concat(junction.junctionPoints);
            });

            junctionPointsWithRoadlinks = _.map(junctionPoints, function (junctionPoint) {
                return {
                  junctionPoint: junctionPoint,
                  roadLinks:_.filter(roadLinksWithValues, function (roadLink) {
                    return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
                  }),
                  junction: _.find(junctions, function (junction) {
                    return junction.id === junctionPoint.junctionId;
                  })
                };
              }
            );

            _.each(junctionPointsWithRoadlinks, function (junctionPoint) {
              _.each(junctionPoint.roadLinks, function (roadLink) {
               var junctionMarker = new JunctionMarker();
               junctionMarkerLayer.getSource().addFeature(junctionMarker.createJunctionMarker(junctionPoint.junctionPoint, junctionPoint.junction, roadLink));
              });
            });

            _.each(nodePointTemplates, function (nodePointTemplate) {
              var nodePointTemplateMarker = new NodePointTemplateMarker();
              var roadLinkForPoint = _.find(roadLinksWithValues, function (roadLink) {
                return (roadLink.startAddressM === nodePointTemplate.addrM || roadLink.endAddressM === nodePointTemplate.addrM) && roadLink.roadwayNumber === nodePointTemplate.roadwayNumber;
              });
              if (!_.isUndefined(roadLinkForPoint)) {
                nodePointTemplateLayer.getSource().addFeature(nodePointTemplateMarker.createNodePointTemplateMarker(nodePointTemplate, roadLinkForPoint));
              }
            });

            _.each(junctionPointTemplates, function (junctionPointTemplate) {
              var junctionPointTemplateMarker = new JunctionPointTemplateMarker();
              var roadLinkForPoint = _.find(roadLinksWithValues, function (roadLink) {
                return (roadLink.startAddressM === junctionPointTemplate.addrM || roadLink.endAddressM === junctionPointTemplate.addrM) && roadLink.roadwayNumber === junctionPointTemplate.roadwayNumber;
              });
              if (!_.isUndefined(roadLinkForPoint)) {
                junctionTemplateLayer.getSource().addFeature(junctionPointTemplateMarker.createJunctionPointTemplateMarker(junctionPointTemplate, roadLinkForPoint));
              }
            });
          }
        });

        eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
          me.refreshView();
        });

        eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers);
      };

      me.eventListener.listenTo(eventbus, 'nodeLayer:fetch', function () {
        map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
        roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1);
      });

      me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
        isActiveLayer = layer === 'node';
        me.clearLayers();
        if (previouslySelectedLayer === 'node') {
          hideLayer();
        } else if (previouslySelectedLayer === 'linkProperty') {
          setGeneralOpacity(1);
          showLayer();
          eventbus.trigger('nodeLayer:fetch');
        }
        me.toggleLayersVisibility(layers, applicationModel.getRoadVisibility());
      });

      var showLayer = function () {
        me.start();
        me.layerStarted(me.eventListener);
        $('#projectListButton').prop('disabled', true);
      };

      var hideLayer = function () {
        me.clearLayers(layers);
        $('#projectListButton').prop('disabled', false);
      };

      me.toggleLayersVisibility(layers, true);
      me.addLayers(layers);

      return {
        show: showLayer,
        hide: hideLayer,
        minZoomForContent: me.minZoomForContent
      };
    };

  })(this);