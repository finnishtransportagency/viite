(function(root) {
  root.RoadLinkBox = function(selectedProjectLinkProperty) {
    var className = 'road-link';
    var title = 'Selite';
    var selectToolIcon = '<img src="images/select-tool.svg"/>';
    var expandedTemplate = _.template('' +
      '<div class="panel <%= className %>">' +
        '<header class="panel-header expanded"><%- title %></header>' +
        '<div class="legend-container no-copy"></div>' +
      '</div>');

    var roadClassLegend = $('<div id="legendDiv" class="panel-section panel-legend linear-asset-legend road-class-legend no-copy"></div>');
    var calibrationPointPicture = $('' +
      '<div class="legend-entry">' +
      '    <div class="label">Tieosan alku</div>' +
      '    <div class="calibration-point-image"></div>' +
      '</div>');

    var junctionPicture = $('' +
      '<div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">' +
      '<object type="image/svg+xml" data="images/junction.svg" style="margin-right: 5px; margin-top: 5px">\n' +
      '</object>' +
      '<div class="label">Liittymä</div>' +
      '</div>');

    var junctionTemplatePicture = $('' +
      '<div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">' +
      '<object type="image/svg+xml" data="images/junction-template.svg" style="margin-right: 5px; margin-top: 5px"></object>' +
      '<div class="label">Liittymäaihio</div>' +
      '</div>');

    var nodeTemplatePicture = $('' +
      '<div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">' +
      '<object type="image/svg+xml" data="images/node-point-template.svg" style="margin-right: 5px; margin-top: 5px"></object>' +
      '<div class="label">Solmukohta-aihio</div>' +
      '</div>');

    var roadClasses = [
      [1, 'Valtatie (1-39)'],
      [2, 'Kantatie (40-99)'],
      [3, 'Seututie (100-999)'],
      [4, 'Yhdystie (1000-9999)'],
      [5, 'Yhdystie (10001-19999)'],
      [6, 'Numeroitu katu (40000-49999)'],
      [7, 'Ramppi tai kiertoliittymä (20001 - 39999)'],
      [8, 'Jalka- tai pyörätie (70001 - 89999, 90001 - 99999)'],
      [9, 'Yksityistie, talvitie tai polku (50001-62999)'],
      [11,'Muu tieverkko'],
      [98, 'Tietyyppi kunnan katuosuus tai yks.tie'],
      [99,'Tuntematon']
    ];

    var constructionTypes = [
      [0, 'Muu tieverkko, rakenteilla'],
      [1, 'Tuntematon, rakenteilla']
    ];

    var nodes = [
        [1, 'Normaali tasoliittymä'],
        [3, 'Kiertoliittymä'],
        [4, 'Y-liittymä'],
        [5, 'Eritasoliittymä'],
        [7, 'Hoitoraja'],
        [8, 'ELY-raja'],
        [10, 'Moniajoratainen liittymä'],
        [12, 'Liityntätie'],
        [13,'Tien alku/loppu'],
        [14,'Silta'],
        [15,'Huoltoaukko'],
        [16,'Yksityistie- tai katuliittymä'],
        [17,'Porrastettu liittymä']
    ];

    var buildMultiColoredSegments = function () {
      var segments = '<div class = "rainbow-container"><div class="edge-left symbol linear linear-asset-1" />';
      for (var i = 1; i <= 6; i++) {
        segments = segments +
            '<div class="middle symbol linear rainbow-asset-' + i + '" />';
      }
      return segments + '<div class="middle symbol linear rainbow-asset-2" />' + '<div class="middle symbol linear rainbow-asset-1 " /> <div class="edge-right symbol linear linear-asset-1" /></div>';
    };

    var constructionTypeLegendEntries = _.map(constructionTypes, function(constructionType) {
      return '<div class="legend-entry">' +
          '<div class="label">' + constructionType[1] + '</div>' +
          '<div class="symbol linear construction-type-' + constructionType[0] + '" />' +
          '</div>';
    }).join('');

    var roadClassLegendEntries = _.map(roadClasses, function(roadClass) {
      var defaultLegendEntry =
        '<div class="legend-entry">' +
          '<div class="label">' + roadClass[1] + '</div>';
        if (roadClass[0] !== 98)
          defaultLegendEntry += '<div class="symbol linear linear-asset-' + roadClass[0] + '" />';
        else
          defaultLegendEntry += buildMultiColoredSegments();
      return defaultLegendEntry + '</div>';
    }).join('');

    var nodesLegendEntries = _.map(nodes, function(node) {
      return '<div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">' +
        '<img src="images/node-sprite.svg#' + node[0] + '" style="margin-right: 5px"/>' +
        '<div class="label">' + node[0] + " " + node[1] + '</div>' +
        '</div>';
    }).join('');

    var roadProjectOperations = function () {
      return '<div class="legend-entry">' +
        '<div class="label">Ennallaan</div>' +
        '<div class="symbol linear operation-type-unchanged" />' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">Uusi</div>' +
        '<div class="symbol linear operation-type-new" />' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">Siirto</div>' +
        '<div class="symbol linear operation-type-transfer" />' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">Lakkautus</div>' +
        '<div class="symbol linear operation-type-terminated" />' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">Numerointi</div>' +
        '<div class="symbol linear operation-type-renumbered" />' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">Käsittelemätön</div>' +
        '<div class="symbol linear operation-type-unhandeled" />' +
        '<div class="label">Muu tieverkko, rakenteilla</div>' +
        '<div class="symbol linear construction-type-0" />' +
          '<div class="label">Tuntematon, rakenteilla</div>' +
          '<div class="symbol linear construction-type-1" />' +
        '</div>';
    };

    roadClassLegend.append(roadClassLegendEntries);
    roadClassLegend.append(constructionTypeLegendEntries);
    roadClassLegend.append(calibrationPointPicture);

    var Tool = function(toolName, icon, description) {
      var className = toolName.toLowerCase();
      var element = $('<div class="action"/>').addClass(className).attr('action', toolName).append(icon).on('click', function() {
        executeOrShowConfirmDialog(function() {
          applicationModel.setSelectedTool(toolName);
        });
      });

      var deactivate = function() {
        element.removeClass('active');
      };
      var activate = function() {
        element.addClass('active');
      };

      var executeOrShowConfirmDialog = function(f) {
        if (selectedProjectLinkProperty.isDirty()) {
          new Confirm();
        } else {
          f();
        }
      };

      return {
        element: element,
        deactivate: deactivate,
        activate: activate,
        name: toolName,
        description: description
      };
    };

    var ToolSelection = function(tools) {
      var element = $('<div class="panel-section panel-actions" />');
      _.each(tools, function(tool) {
        element.append(tool.element);
        element.append('<div>' + tool.description + '</div>');
      });

      var hide = function() { element.hide(); };
      var show = function() { element.show(); };

      eventbus.on('tool:changed', function(name) {
        _.each(tools, function(tool) {
          if (applicationModel.isSelectedTool(tool.name)) {
            tool.activate();
          } else {
            tool.deactivate();
          }
        });
      });

      eventbus.on('tool:clear', function () {
        reset();
      });

      var reset = function() {
        _.each(tools, function(tool) {
          tool.deactivate();
        });
      };

      hide();

      return {
        element: element,
        reset: reset,
        show: show,
        hide: hide
      };
    };

    var nodeToolSelection = new ToolSelection([
      new Tool(LinkValues.Tool.Select.value, selectToolIcon, LinkValues.Tool.Select.description)
    ]);

    var templateAttributes = {
      className: className,
      title: title
    };

    var elements = {
      expanded: $(expandedTemplate(templateAttributes))
    };

    var bindExternalEventHandlers = function() {
      eventbus.on('userData:fetched', function (userData) {
        if (_.includes(userData.roles, 'viite')) {
          $('#formProjectButton').removeAttr('style');
          elements.expanded.append(nodeToolSelection.element);
        }
      });
    };

    eventbus.on('layer:selected', toggleLegends);

    eventbus.on('nodesAndJunctions:open', function () {
      eventbus.trigger('linkProperties:deactivateAllSelections');
    });

    eventbus.on('nodesAndJunctions:close', function () {
      eventbus.trigger('linkProperties:enableInteractions');
    });

    bindExternalEventHandlers();

    elements.expanded.find('.legend-container').append(roadClassLegend);
    var element = $('<div class="panel-group ' + className + 's"/>').append(elements.expanded).hide();

    function show() {
      element.show();
    }

    function hide() {
      element.hide();
    }

    function toggleLegends() {
      var container = $('#legendDiv');
      if(applicationModel.getSelectedLayer() === "roadAddressProject") {
        container.empty();
        container.append(roadProjectOperations());
        container.append(calibrationPointPicture);
        nodeToolSelection.hide();
      }
      else if(applicationModel.getSelectedLayer() === "node"){
        container.empty();
        roadClassLegend.append(nodesLegendEntries);
        roadClassLegend.append(junctionPicture);
        roadClassLegend.append(junctionTemplatePicture);
        roadClassLegend.append(nodeTemplatePicture);
        nodeToolSelection.reset();
        nodeToolSelection.show();
      } else {
        container.empty();
        roadClassLegend.append(roadClassLegendEntries);
        roadClassLegend.append(constructionTypeLegendEntries);
        roadClassLegend.append(calibrationPointPicture);
        nodeToolSelection.hide();
      }
    }

    return {
      title: title,
      layerName: 'linkProperty',
      element: element,
      show: show,
      hide: hide
    };
  };
})(this);
