(function (root) {
  /**
   * NodeMenu - State-driven wrapper for Search -> DataMenu -> Editor flow.
   * Owns panel rendering, event wiring and search-first transition rules.
   */
  root.NodeMenu = function (map, nodeCollection, backend, selectedNodesAndJunctions, roadCollection, startupParameters) {
    const STATE = {
      SEARCH: 'search',
      DISPLAY_NODE: 'display-node',
      DISPLAY_TEMPLATES: 'display-templates',
      EDITOR: 'editor'
    };

    let menu = null;
    let initialized = false;
    let currentState = STATE.SEARCH;

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

    const dataTable = new root.DataTable();
    const searchMenu = new root.NodeSearchMenu(dataTable, getBodyContainer, {
      searchResultsFontSize: startupParameters.nodeSearchResultsFontSize || 12
    });
    const dataMenu = new root.NodeDataMenu(dataTable, getBodyContainer);
    const nodeEditor = new root.NodeEditor(selectedNodesAndJunctions, dataTable, startupParameters, backend, roadCollection, getBodyContainer);

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

          console.log('Node search click:', {
            index: index,
            resultsCount: nodesWithAttributes.length,
            selectedNode: node,
            selectedNodeKeys: node ? _.keys(node) : []
          });

          if (!node) {
            console.warn('Node search click: no node found for index', index);
            return;
          }

          eventbus.trigger('nodeSearchTool:clickNode', index, map);
          selectedNodesAndJunctions.openNode(node);
        },
        onNodePointTemplateClick: function (templateId) {
          eventbus.trigger('nodeSearchTool:clickNodePointTemplate', templateId);
          window.location.hash = 'node/nodePointTemplate/' + templateId;
        },
        onJunctionTemplateClick: function (templateId) {
          eventbus.trigger('nodeSearchTool:clickJunctionTemplate', templateId);
          window.location.hash = 'node/junctionTemplate/' + templateId;
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

      menu = new root.MenuContainer('#menu-container', closeHandler);
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
  };

  root.NodeMenuStateRouter = root.NodeMenu;
}(this));