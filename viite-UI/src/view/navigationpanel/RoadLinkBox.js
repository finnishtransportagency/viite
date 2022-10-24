(function (root) {
  root.RoadLinkBox = function (selectedProjectLinkProperty) {
    var LinkStatus = LinkValues.LinkStatus;
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
      [8, 'Kävelyn ja pyöräilyn väylä (70001 - 99999)'],
      [9, 'Yksityistie, talvitie tai polku (50001-62999)'],
      [11, 'Osoitteeton (kunta tai yksityinen)'],
      [98, 'Hallinnollinen luokka kunta tai yksityinen'],
      [99, 'Osoitteeton (valtio)']
    ];

    var lifecycleStatusUINames = [
      [0, 'Rakenteilla (kunta/yksityinen)'],
      [1, 'Rakenteilla (valtio)']
    ];

    var nodes = [
      [1, 'Normaali tasoliittymä'],
      [3, 'Kiertoliittymä'],
      [4, 'Y-liittymä'],
      [5, 'Eritasoliittymä'],
      [7, 'Hallinnollinen raja'],
      [8, 'ELY-raja'],
      [10, 'Moniajoratainen liittymä'],
      [12, 'Liityntätie'],
      [13, 'Tien alku/loppu'],
      [14, 'Silta'],
      [15, 'Huoltoaukko'],
      [16, 'Yksityistie- tai katuliittymä'],
      [17, 'Porrastettu liittymä'],
      [18, 'Lautta']
    ];

    var buildMultiColoredSegments = function () {
      var segments = '<div class = "rainbow-container"><div class="edge-left symbol linear linear-asset-1"></div>';
      for (var i = 1; i <= 6; i++) {
        segments = segments +
          '<div class="middle symbol linear rainbow-asset-' + i + '"></div>';
      }
      return segments + '<div class="middle symbol linear rainbow-asset-2"></div><div class="middle symbol linear rainbow-asset-1 "></div> <div class="edge-right symbol linear linear-asset-1"></div></div>';
    };

    var lifecycleStatusLegendEntries = _.map(lifecycleStatusUINames, function (lifecycleStatus) {
      return '<div class="legend-entry">' +
        '<div class="label">' + lifecycleStatus[1] + '</div>' +
        '<div class="symbol linear construction-type-' + lifecycleStatus[0] + '"></div>' +
        '</div>';
    }).join('');

    var roadClassLegendEntries = _.map(roadClasses, function (roadClass) {
      var defaultLegendEntry =
        '<div class="legend-entry">' +
        '<div class="label">' + roadClass[1] + '</div>';
      if (roadClass[0] === 98)
        defaultLegendEntry += buildMultiColoredSegments();
      else
        defaultLegendEntry += '<div class="symbol linear linear-asset-' + roadClass[0] + '"></div>';
      return defaultLegendEntry + '</div>';
    }).join('');

    var nodesLegendEntries = _.map(nodes, function (node) {
      return '<div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">' +
        '<img src="images/node-sprite.svg#' + node[0] + '" style="margin-right: 5px"/>' +
        '<div class="label">' + node[0] + " " + node[1] + '</div>' +
        '</div>';
    }).join('');

    var roadProjectOperations = function () {
      return '<div class="legend-entry">' +
        '<div class="label">' + LinkStatus.Unchanged.displayText + '</div>' +
        '<div class="symbol linear operation-type-unchanged"></div>' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">' + LinkStatus.New.displayText + '</div>' +
        '<div class="symbol linear operation-type-new"></div>' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">' + LinkStatus.Transfer.displayText + '</div>' +
        '<div class="symbol linear operation-type-transfer"></div>' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">' + LinkStatus.Terminated.displayText + '</div>' +
        '<div class="symbol linear operation-type-terminated"></div>' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">' + LinkStatus.Numbering.displayText + '</div>' +
        '<div class="symbol linear operation-type-renumbered"></div>' +
        '</div>' +
        '<div class="legend-entry">' +
        '<div class="label">' + LinkStatus.NotHandled.displayText + '</div>' +
        '<div class="symbol linear operation-type-unhandeled"></div>' +
        '<div class="label">Rakenteilla (kunta/yksityinen)</div>' +
        '<div class="symbol linear construction-type-0"></div>' +
        '<div class="label">Rakenteilla (valtio)</div>' +
        '<div class="symbol linear construction-type-1"></div>' +
        '</div>';
    };

    roadClassLegend.append(roadClassLegendEntries);
    roadClassLegend.append(lifecycleStatusLegendEntries);
    roadClassLegend.append(calibrationPointPicture);

    var Tool = function (toolName, icon, description) {
      var classNameForTool = toolName.toLowerCase();
      var toolElement = $('<div class="action"></div>').addClass(classNameForTool).attr('action', toolName).append(icon).on('click', function () {
        executeOrShowConfirmDialog(function () {
          applicationModel.setSelectedTool(toolName);
        });
      });

      var deactivate = function () {
        toolElement.removeClass('active');
      };
      var activate = function () {
        toolElement.addClass('active');
      };

      var executeOrShowConfirmDialog = function (f) {
        if (selectedProjectLinkProperty.isDirty()) {
          new Confirm();
        } else {
          f();
        }
      };

      return {
        element: toolElement,
        deactivate: deactivate,
        activate: activate,
        name: toolName,
        description: description
      };
    };

    var ToolSelection = function (tools) {
      var toolSelectionElement = $('<div class="panel-section panel-actions"></div>');
      _.each(tools, function (tool) {
        toolSelectionElement.append(tool.element);
        toolSelectionElement.append('<div>' + tool.description + '</div>');
      });

      var doHide = function () {
        toolSelectionElement.hide();
      };
      var doShow = function () {
        toolSelectionElement.show();
      };

      eventbus.on('tool:changed', function (_name) {
        _.each(tools, function (tool) {
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

      var reset = function () {
        _.each(tools, function (tool) {
          tool.deactivate();
        });
      };

      doHide();

      return {
        element: toolSelectionElement,
        reset: reset,
        show: doShow,
        hide: doHide
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

    var bindExternalEventHandlers = function () {
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
    var element = $('<div class="panel-group ' + className + 's"></div>').append(elements.expanded).hide();

    function show() {
      element.show();
    }

    function hide() {
      element.hide();
    }

    function toggleLegends() {
      var container = $('#legendDiv');
      if (applicationModel.getSelectedLayer() === "roadAddressProject") {
        container.empty();
        container.append(roadProjectOperations());
        container.append(calibrationPointPicture);
        nodeToolSelection.hide();
      } else if (applicationModel.getSelectedLayer() === "node") {
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
        roadClassLegend.append(lifecycleStatusLegendEntries);
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
}(this));
