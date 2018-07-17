(function(root) {
  root.RoadLinkBox = function(selectedProjectLinkProperty) {
    var className = 'road-link';
    var title = 'Selite';
    var selectToolIcon = '<img src="images/select-tool.svg"/>';
    var cutToolIcon = '<img src="images/cut-tool.svg"/>';
    var expandedTemplate = _.template('' +
      '<div class="panel <%= className %>">' +
        '<header class="panel-header expanded"><%- title %></header>' +
        '<div class="legend-container"></div>' +
      '</div>');

    var administrativeClassLegend = $('' +
      '<div class="panel-section panel-legend road-link-legend">' +
        '<div class="legend-entry">' +
          '<div class="label">Valtion omistama</div>' +
            '<div class="symbol linear road"/>' +
          '</div>' +
          '<div class="legend-entry">' +
            '<div class="label">Kunnan omistama</div>' +
            '<div class="symbol linear street"/>' +
          '</div>' +
          '<div class="legend-entry">' +
            '<div class="label">Yksityisen omistama</div>' +
            '<div class="symbol linear private-road"/>' +
          '</div>' +
          '<div class="legend-entry">' +
            '<div class="label">Ei tiedossa tai kevyen liikenteen väylä</div>' +
          '<div class="symbol linear unknown"/>' +
        '</div>' +
      '</div>');

    var roadClassLegend = $('<div id="legendDiv" class="panel-section panel-legend linear-asset-legend road-class-legend"></div>');

    var floatingLegend = $('' +
      '<div class="legend-entry">' +
      '<div class="label">Irti geometriasta</div>' +
      '</div>' +
      '<div class="floating-flag-with-stick-image"></div>'+
      '<div class="legend-entry">' +
      '<div class="symbol linear linear-asset-12" /></div>' +
      '</div>');

    var calibrationPointPicture = $('' +
      '<div class="legend-entry">' +
      '<div class="label">Etäisyyslukema</div>' +
      '</div>' +
      '<div class="calibration-point-image"></div>');

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
        '<div class="label">Suravage-linkit</div>' +
        '<div class="symbol linear operation-type-suravage" />' +
        '<div class="label">Muu tieverkko, rakenteilla</div>' +
        '<div class="symbol linear construction-type-0" />' +
        '</div>';
    };

    roadClassLegend.append(roadClassLegendEntries);
    roadClassLegend.append(constructionTypeLegendEntries);
    roadClassLegend.append(floatingLegend);
    roadClassLegend.append(calibrationPointPicture);

    var Tool = function(toolName, icon) {
      var className = toolName.toLowerCase();
      var element = $('<div class="action"/>').addClass(className).attr('action', toolName).append(icon).click(function() {
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
        name: toolName
      };
    };

    var ToolSelection = function(tools) {
      var element = $('<div class="panel-section panel-actions" />');
      _.each(tools, function(tool) {
        element.append(tool.element);
      });
      var hide = function() {
        element.hide();
      };
      var show = function() {
        element.show();
      };
      var deactivateAll = function() {
        _.each(tools, function(tool) {
          tool.deactivate();
        });
      };
      var reset = function() {
        deactivateAll();
        tools[0].activate();
      };
      eventbus.on('tool:changed', function(name) {
        _.each(tools, function(tool) {
          if (tool.name != name) {
            tool.deactivate();
          } else {
            tool.activate();
          }
        });
      });

      hide();
      
      return {
        element: element,
        reset: reset,
        show: show,
        hide: hide
      };
    };

    var toolSelection = new ToolSelection([
      new Tool('Select', selectToolIcon),
      new Tool('Cut', cutToolIcon)
    ]);

    var editModeToggle = new EditModeToggleButton(
      toolSelection
    );

    var templateAttributes = {
      className: className,
      title: title
    };

    var elements = {
      expanded: $(expandedTemplate(templateAttributes))
    };

    var bindExternalEventHandlers = function() {
      eventbus.on('userData:fetched', function (userData) {
        if (_.contains(userData.roles, 'viite')) {
          elements.expanded.append(editModeToggle.element);
          $('#projectListButton').removeAttr('style');
        }
      });
    };

    eventbus.on('editMode:setReadOnly', function(mode) {
      editModeToggle.toggleEditMode(mode);
      elements.expanded.append(toolSelection.element);
    });

    eventbus.on('application:readOnly', function() {
      if(applicationModel.getSelectedLayer() != "linkProperty")
      elements.expanded.append(toolSelection.element);
    });

    eventbus.on('roadAddressProject:clearTool', function(){
      toolSelection.hide();
    });

    eventbus.on('layer:selected roadAddressProject', toggleProjectLegends);

    bindExternalEventHandlers();

    elements.expanded.find('.legend-container').append(roadClassLegend);
    var element = $('<div class="panel-group ' + className + 's"/>').append(elements.expanded).hide();

    function show() {
      editModeToggle.toggleEditMode(applicationModel.isReadOnly());
      element.show();
    }

    function hide() {
      element.hide();
    }

    function toggleProjectLegends() {
      var container = $('#legendDiv');
      if(applicationModel.getSelectedLayer() !== "linkProperty") {
        container.empty();
        container.append(roadProjectOperations());
        container.append(calibrationPointPicture);
      } else {
        $('.panel-actions').hide();
        container.empty();
        roadClassLegend.append(roadClassLegendEntries);
        roadClassLegend.append(constructionTypeLegendEntries);
        roadClassLegend.append(floatingLegend);
        roadClassLegend.append(calibrationPointPicture);
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
