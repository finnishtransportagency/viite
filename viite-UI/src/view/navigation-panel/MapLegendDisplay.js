/**
 * MapLegendDisplay component
 * Displays road link legend and tool selection panel for road address link properties.
 * @param {Object} applicationModel - Application state manager
 * @returns {Object} Component with element, title, and visibility control methods
 */
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/eventbus.js';

export function MapLegendDisplay(applicationModel) {
  const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    const className = 'road-link';
    const title = 'Selite';
    const selectToolIcon = '<img src="images/select-tool.svg"/>';
    const expandedTemplate = _.template(`
      <div class="panel <%= className %>">
      <header class="panel-header expanded"><%- title %></header>
      <div class="legend-container no-copy"></div>
      </div>`);

    const roadClassLegend = $('<div id="legendDiv" class="panel-section panel-legend linear-asset-legend road-class-legend no-copy"></div>');

    const calibrationPointPicture = `
      <div class="legend-entry">
          <div class="label">Kalibrointipiste</div>
          <div class="calibration-point-image"></div>
      </div>`;

    const roadPartStartPointPicture = `
      <div class="legend-entry">
          <div class="label">Tieosan alku</div>
          <div class="calibration-point-image"></div>
      </div>`;

    const junctionPicture = `
      <div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">
        <object type="image/svg+xml" data="images/junction.svg" style="margin-right: 5px; margin-top: 5px">
        </object>
        <div class="label">Liittymä</div>
      </div>`;

    const junctionTemplatePicture = `
      <div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">
        <object type="image/svg+xml" data="images/junction-template.svg" style="margin-right: 5px; margin-top: 5px"></object>
        <div class="label">Liittymäaihio</div>
      </div>`;

    const nodeTemplatePicture = `
      <div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">
        <object type="image/svg+xml" data="images/node-point-template.svg" style="margin-right: 5px; margin-top: 5px"></object>
        <div class="label">Solmukohta-aihio</div>
      </div>`;

    const roadClasses = [
      [1, 'Valtatie (1-39)'],
      [2, 'Kantatie (40-99)'],
      [3, 'Seututie (100-999)'],
      [4, 'Yhdystie (1000-9999)'],
      [5, 'Yhdystie (10000-19999)'],
      [6, 'Numeroitu katu (40000-49999)'],
      [7, 'Ramppi tai kiertoliittymä (20000 - 39999)'],
      [8, 'Kävelyn ja pyöräilyn väylä (70001 - 99999)'],
      [9, 'Yksityistie, talvitie tai polku (50000-62999)'],
      [11, 'Osoitteeton (kunta tai yksityinen)'],
      [98, 'Hallinnollinen luokka kunta tai yksityinen'],
      [99, 'Osoitteeton (valtio)']
    ];

    const buildMultiColoredSegments = function () {
      let segments = '<div class="rainbow-container"><div class="edge-left symbol linear linear-asset-1"></div>';
      for (let i = 1; i <= 6; i++) {
        segments += `<div class="middle symbol linear rainbow-asset-${i}"></div>`;
      }
      return `${segments}<div class="middle symbol linear rainbow-asset-2"></div><div class="middle symbol linear rainbow-asset-1 "></div> <div class="edge-right symbol linear linear-asset-1"></div></div>`;
    };

    function createLifecycleStatusLegendEntries ()  {
      let html = '';
      for (const status in ViiteEnumerations.LifeCycleStatus) {
        if (Object.prototype.hasOwnProperty.call(ViiteEnumerations.LifeCycleStatus, status)) {
          const statusValue = ViiteEnumerations.LifeCycleStatus[status].value;
          const statusDescription = ViiteEnumerations.LifeCycleStatus[status].description;
          let additionalClass = '';
          
          if (statusDescription.includes('Rakenteilla (kunta/yksityinen)')) {
            additionalClass = 'striped-gray';
          } else if (statusDescription.includes('Rakenteilla (valtio)')) {
            additionalClass = 'striped-orange';
          }
          
          html += `<div class="legend-entry">
                    <div class="label">${statusDescription}</div>
                    <div class="symbol linear construction-type-${statusValue} ${additionalClass}"></div>
                  </div>`;
        }
      }
      return html;
    }

    const roadClassLegendEntries = _.map(roadClasses, function (roadClass) {
      const getColorClass = function(roadClassId) {
        const colorMap = {
          1: 'red',
          2: 'orange', 
          3: 'beige',
          4: 'dark-blue',
          5: 'cyan',
          6: 'purple',
          7: 'stripe-cyan',
          8: 'stripe-faded-pink',
          9: 'stripe-pink',
          11: 'gray',
          99: 'dark-gray'
        };
        return colorMap[roadClassId] || '';
      };
      
      let defaultLegendEntry = `<div class="legend-entry">
        <div class="label">${roadClass[1]}</div>`;
      if (roadClass[0] === 98)
        defaultLegendEntry += buildMultiColoredSegments();
      else
        defaultLegendEntry += `<div class="symbol linear linear-asset-${roadClass[0]} ${getColorClass(roadClass[0])}"></div>`;
      return `${defaultLegendEntry}</div>`;
    }).join('');

    const createNodeLegendEntries = function() {
      let html = '';
      for (const node in ViiteEnumerations.NodeType) {
        if (Object.prototype.hasOwnProperty.call(ViiteEnumerations.NodeType, node) && ViiteEnumerations.NodeType[node] !== ViiteEnumerations.NodeType.UnknownNodeType)
          html += `<div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">
              <img src="images/node-sprite.svg#${ViiteEnumerations.NodeType[node].value}" style="margin-right: 5px"/>
              <div class="label">${ViiteEnumerations.NodeType[node].value} ${ViiteEnumerations.NodeType[node].description}</div>
              </div>`;
      }
      return html;
    };

    const roadProjectOperations = function () {
      return (
        `<div class="legend-entry">
          <div class="label">${RoadAddressChangeType.Unchanged.displayText}</div>
          <div class="symbol linear operation-type-unchanged dark-blue"></div>
        </div>
        <div class="legend-entry">
          <div class="label">${RoadAddressChangeType.New.displayText}</div>
          <div class="symbol linear operation-type-new pink"></div>
        </div>
        <div class="legend-entry">
          <div class="label">${RoadAddressChangeType.Transfer.displayText}</div>
          <div class="symbol linear operation-type-transfer red"></div>
        </div>
        <div class="legend-entry">
          <div class="label">${RoadAddressChangeType.Terminated.displayText}</div>
          <div class="symbol linear operation-type-terminated black"></div>
        </div>
        <div class="legend-entry">
          <div class="label">${RoadAddressChangeType.Numbering.displayText}</div>
          <div class="symbol linear operation-type-renumbered brown"></div>
        </div>
        <div class="legend-entry">
          <div class="label">${RoadAddressChangeType.NotHandled.displayText}</div>
          <div class="symbol linear operation-type-unhandeled yellow"></div>
        </div>
        ${createLifecycleStatusLegendEntries()}
        <div class="legend-entry">
          <div class="label"> Osoitteeton (valtio)</div>
          <div class="symbol linear linear-asset-99 dark-gray"></div>
        </div>
        <div class="legend-entry">
          <div class="label"> Osoitteeton (kunta/yksityinen)</div>
          <div class="symbol linear linear-asset-11 gray"></div>
        </div>`
      );
    };

    roadClassLegend.append(roadClassLegendEntries);
    roadClassLegend.append(createLifecycleStatusLegendEntries());
    roadClassLegend.append(calibrationPointPicture);

    const Tool = function (toolName, icon, description) {
      const classNameForTool = toolName.toLowerCase();
      const toolElement = $('<div class="action"></div>').addClass(classNameForTool).attr('action', toolName).append(icon).on('click', function () {
        selectTool();
      });

      const deactivate = function () {
        toolElement.removeClass('active');
      };
      const activate = function () {
        toolElement.addClass('active');
      };

      const selectTool = function () {
        applicationModel.setSelectedTool(toolName);
      };

      return {
        element: toolElement,
        deactivate: deactivate,
        activate: activate,
        name: toolName,
        description: description
      };
    };

    const ToolSelection = function (tools) {
      const toolSelectionElement = $('<div class="panel-section panel-actions"></div>');
      _.each(tools, function (tool) {
        toolSelectionElement.append(tool.element);
        toolSelectionElement.append(`<div>${tool.description}</div>`);
      });

      const doHide = function () {
        toolSelectionElement.hide();
      };
      const doShow = function () {
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

      const reset = function () {
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

    const nodeToolSelection = new ToolSelection([
      new Tool(ViiteEnumerations.Tool.Select.value, selectToolIcon, ViiteEnumerations.Tool.Select.description)
    ]);

    const templateAttributes = {
      className: className,
      title: title
    };

    const elements = {
      expanded: $(expandedTemplate(templateAttributes))
    };

    const bindExternalEventHandlers = function () {
      eventbus.on('userData:fetched', function (userData) {
        if (_.includes(userData.roles, 'viite')) {
          $('#formProjectButton').removeAttr('style');
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
    const element = $(`<div class="panel-group ${className}s"></div>`).append(elements.expanded).hide();

    function show() {
      element.show();
    }

    function hide() {
      element.hide();
    }

    function toggleLegends() {

      const container = $('#legendDiv');
      if (applicationModel.getSelectedLayer() === "roadAddressProject") {
        container.empty();
        container.append(roadProjectOperations());
        container.append(calibrationPointPicture);
        nodeToolSelection.hide();
      } else if (applicationModel.getSelectedLayer() === "node") {
        container.empty();
        roadClassLegend.append(createNodeLegendEntries());
        roadClassLegend.append(junctionPicture);
        roadClassLegend.append(junctionTemplatePicture);
        roadClassLegend.append(nodeTemplatePicture);
        nodeToolSelection.reset();
        nodeToolSelection.show();
        elements.expanded.append(nodeToolSelection.element);
      } else {
        container.empty();
        roadClassLegend.append(roadClassLegendEntries);
        roadClassLegend.append(createLifecycleStatusLegendEntries());
        roadClassLegend.append(roadPartStartPointPicture);
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
  }
