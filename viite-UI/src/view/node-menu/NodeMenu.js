/**
 * Coordinates the node search, detail display, and edit flows inside the node side panel.
 * Switches between search, template, and editor states while keeping map actions synchronized.
 */
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { DataTable } from '@node-menu/DataTable.js';
import { InstructionsPopup } from '@components/InstructionsPopup.js';
import { MenuContainer } from '@components/MenuContainer.js';
import { NodeDataMenu } from '@node-menu/NodeDataMenu.js';
import { NodeEditor } from '@node-menu/NodeEditor.js';
import { NodeSearchMenu } from '@node-menu/NodeSearchMenu.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/eventbus.js';
export function NodeMenu(map, nodeCollection, backend, selectedNodesAndJunctions, roadCollection, startupParameters, dependencies) {
  const applicationModel = dependencies.applicationModel;
  const navigateToHash = dependencies.navigateToHash;

    const STATE = {
      SEARCH: 'search',
      DISPLAY_NODE: 'display-node',
      DISPLAY_TEMPLATES: 'display-templates',
      EDITOR: 'editor'
    };

    let menu = null;
    let initialized = false;
    let currentState = STATE.SEARCH;
    let pendingSearchNodeNumber = null;

    const setCurrentState = function (nextState) {
      if (currentState === nextState) {
        return;
      }

      console.log('NodeMenu state changed:', currentState, '->', nextState);
      currentState = nextState;
    };

    const getBodyContainer = function () {
      return menu ? menu.getBody() : $('#menu-container');
    };

    const hasCompleteNodeData = function (node) {
      return Boolean(node) && _.isArray(node.nodePoints) && _.isArray(node.junctions);
    };

    const openSearchNodeWithMapData = function (searchNode) {
      const completeNode = nodeCollection.getNodeByNodeNumber(searchNode.nodeNumber);
      if (hasCompleteNodeData(completeNode)) {
        selectedNodesAndJunctions.openNode(completeNode);
        return;
      }

      pendingSearchNodeNumber = searchNode.nodeNumber;
      eventbus.once('node:fetched', function () {
        if (pendingSearchNodeNumber !== searchNode.nodeNumber) {
          return;
        }

        const fetchedNode = nodeCollection.getNodeByNodeNumber(searchNode.nodeNumber);
        selectedNodesAndJunctions.openNode(hasCompleteNodeData(fetchedNode) ? fetchedNode : searchNode);
      });
    };

    const dataTable = new DataTable();
    const searchMenu = new NodeSearchMenu(dataTable, getBodyContainer, {
      searchResultsFontSize: startupParameters.nodeSearchResultsFontSize || 12
    });
    const dataMenu = new NodeDataMenu(dataTable, getBodyContainer, {
      applicationModel: applicationModel
    });
    const nodeEditor = new NodeEditor(selectedNodesAndJunctions, dataTable, startupParameters, backend, roadCollection, getBodyContainer, {
      ConfirmPopup: ConfirmPopup,
      dateutil: dependencies.dateutil,
      moment: dependencies.moment,
      ViiteEnumerations: ViiteEnumerations,
      eventbus: eventbus
    });

    const showSearch = function () {
      setCurrentState(STATE.SEARCH);

      if (menu) {
        menu.setHeader('Solmut ja liittymät');
        menu.setFooter('');
      }

      searchMenu.show();
      searchMenu.bindEvents({
        onSearch: function (data) {
          applicationModel.addSpinner();
          searchMenu.clearSearchResults();
          searchMenu.clearUntreatedTemplates();
          nodeCollection.getNodesByRoadAttributes(data);
        },
        onClear: function () {
          applicationModel.addSpinner();
          searchMenu.clearSearchResults();
          searchMenu.clearUntreatedTemplates();
          fetchUntreatedTemplates();
          searchMenu.setClearEnabled(false);
          searchMenu.setSearchEnabled(false);
        },
        onResultClick: function (index) {
          const nodesWithAttributes = nodeCollection.getNodesWithAttributes();
          const node = nodesWithAttributes[index];

          if (!node) {
            console.warn('Node search click: no node found for index', index);
            return;
          }

          eventbus.trigger('nodeSearchTool:clickNode', index, map);
          openSearchNodeWithMapData(node);
        },
        onNodePointTemplateClick: function (templateId) {
          eventbus.trigger('nodeSearchTool:clickNodePointTemplate', templateId);
          navigateToHash(`node/nodePointTemplate/${templateId}`);
        },
        onJunctionTemplateClick: function (templateId) {
          eventbus.trigger('nodeSearchTool:clickJunctionTemplate', templateId);
          navigateToHash(`node/junctionTemplate/${templateId}`);
        }
      });

      applicationModel.selectLayer('node');
      applicationModel.addSpinner();
      fetchUntreatedTemplates();
      searchMenu.setClearEnabled(false);
      searchMenu.setSearchEnabled(false);
    };

    const showNodeDisplay = function (node, templates) {
      setCurrentState(STATE.DISPLAY_NODE);

      if (menu) {
        menu.setHeader(dataMenu.getNodeHeader());
      }

      dataMenu.showNode(node, templates);
      dataMenu.bindEvents({
        onEditNode: function () {
          const currentNode = selectedNodesAndJunctions.getCurrentNode();
          if (_.isEmpty(currentNode)) {
            new ConfirmPopup('Avaa ensin hakutulos ennen muokkausta.', { type: 'alert' });
            return;
          }
          showNodeEditor(currentNode, templates);
        },
        onBackToSearch: function () {
          showSearch();
        }
      });
    };

    const showTemplateDisplay = function (templates) {
      setCurrentState(STATE.DISPLAY_TEMPLATES);

      if (menu) {
        menu.setHeader(dataMenu.getTemplateHeader());
        menu.setFooter(dataMenu.renderTemplateFooter());
      }

      dataMenu.showTemplates(templates);
      dataMenu.bindEvents({
        onEditNode: _.noop,
        onSaveTemplates: function () {
          selectedNodesAndJunctions.saveNode();
        },
        onBackToSearch: function () {
          selectedNodesAndJunctions.closeTemplates();
          showSearch();
        }
      });
    };

    const showNodeEditor = function (node, templates) {
      setCurrentState(STATE.EDITOR);

      if (menu) {
        menu.setHeader(nodeEditor.getHeader());
        menu.setFooter(nodeEditor.renderFooter());
      }

      nodeEditor.showNode(node, templates, {
        onExit: function (target) {
          if (target === 'display') {
            showNodeDisplay(node, templates);
          } else {
            showSearch();
          }
        }
      });
    };

    const fetchUntreatedTemplates = function () {
      backend.getTemplates(function (data) {
        const nodePointTemplates = data.nodePointTemplates;
        const junctionTemplates = data.junctionTemplates;
        eventbus.trigger('templates:fetched', nodePointTemplates, junctionTemplates);
        searchMenu.showUntreatedTemplates(nodePointTemplates, junctionTemplates);
        applicationModel.removeSpinner();
      });
    };

    const bindGlobalEvents = function () {
      eventbus.on('nodeSearchTool:fetched', function (hasResults) {
        if (currentState !== STATE.SEARCH) {
          return;
        }

        applicationModel.removeSpinner();
        searchMenu.setClearEnabled(true);
        if (hasResults) {
          searchMenu.showSearchResults(nodeCollection.getNodesWithAttributes());
          searchMenu.clearUntreatedTemplates();
          eventbus.trigger('nodeSearchTool:refreshView', map);
        } else {
          new InstructionsPopup(jQuery('.digiroad2')).show('Ei tuloksia', 3000);
        }
      });

      eventbus.on('node:selected', function (currentNode, templates) {
        if (!_.isEmpty(currentNode)) {
          showNodeEditor(currentNode, templates);
        }
      });

      eventbus.on('templates:selected', function (templates) {
        if (!_.isEmpty(templates.nodePoints) || !_.isEmpty(templates.junctions)) {
          showTemplateDisplay(templates);
        }
      });

      eventbus.on('nodesAndJunctions:open', function () {
        showSearch();
      });
    };

    const initialize = function () {
      if (initialized) {
        return;
      }
      bindGlobalEvents();
      initialized = true;
    };

    const show = function () {
      const closeHandler = function () {
        applicationModel.selectLayer('linkProperty', true);
        eventbus.trigger('nodesAndJunctions:close');
      };

      menu = new MenuContainer('#menu-container', closeHandler);
      eventbus.trigger('nodesAndJunctions:open');
    };

    const hide = function () {
      nodeEditor.cleanup();
      if (menu) {
        menu.clear();
        menu = null;
      }
      eventbus.trigger('nodesAndJunctions:close');
    };

    const setState = function (state) {
      if (state === 'closed') {
        hide();
      } else {
        show();
      }
    };

    return {
      initialize: initialize,
      show: show,
      hide: hide,
      setState: setState
    };
  }

window.NodeMenu = NodeMenu;
window.NodeMenuStateRouter = NodeMenu;