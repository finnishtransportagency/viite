import { NodeDataMenu } from '@node-menu/NodeDataMenu.js';
import { NodeEditor } from '@node-menu/NodeEditor.js';
import { NodeSearchMenu } from '@node-menu/NodeSearchMenu.js';
import { setMainMenuState } from '@view/MainMenu.js';
import { eventbus } from '@utils/eventbus.js';
import { getStartupParameters, selectLayer } from '@model/ApplicationModel.js';

// Exported function reference (initialized later)
export let setNodeMenuState = () => {
  throw new Error('NodeMenu not initialized yet');
};

export function NodeMenu(
  map,
  nodeCollection,
  backend,
  selectedNodesAndJunctions,
  roadCollection,
  menu
) {
  const closeNodeMenu = () => {
    selectLayer('linkProperty', true);
    setMainMenuState('main');
  };

  const setNodeMenuStateInternal = (newState, ...args) => {
    stateConfig[newState].render(...args);
  };

  const permissionToEditNodes =
    getStartupParameters()?.roles?.includes('viite') ?? false;

  const searchMenu = new NodeSearchMenu(
    map,
    nodeCollection,
    backend,
    selectedNodesAndJunctions,
    setNodeMenuStateInternal
  );

  const dataMenu = new NodeDataMenu(
    selectedNodesAndJunctions,
    setNodeMenuStateInternal
  );

  const nodeEditor = new NodeEditor(
    selectedNodesAndJunctions,
    backend,
    roadCollection,
    () => menu.getBody(),
    permissionToEditNodes
  );

  const stateConfig = {
    search: {
      render: () => {
        menu.setHeader('Solmut ja liittymät', closeNodeMenu);
        menu.setBody(searchMenu.render());
        menu.setFooter('');
      }
    },
    'display-templates': {
      render: (templates) => {
        const effectiveTemplates =
          templates ||
          selectedNodesAndJunctions.getCurrentTemplates() ||
          {};

        menu.setHeader('Aihioiden tiedot', closeNodeMenu);
        menu.setBody(dataMenu.renderBody(effectiveTemplates));
        menu.setFooter(dataMenu.renderFooter());
      }
    },
    editor: {
      render: (node, templates) => {
        menu.setHeader(nodeEditor.getHeader(), closeNodeMenu);
        menu.setBody('');

        nodeEditor.showNode(node, templates, {
          onExit: (target) => {
            if (target === 'templates') {
              const templatesToShow =
                templates ||
                selectedNodesAndJunctions.getCurrentTemplates();

              if (
                !templatesToShow ||
                (_.isEmpty(templatesToShow.nodePoints) &&
                  _.isEmpty(templatesToShow.junctions))
              ) {
                setNodeMenuStateInternal('search');
                return;
              }

              eventbus.trigger(
                'selectedNodesAndJunctions:openTemplates',
                templatesToShow
              );

              setNodeMenuStateInternal(
                'display-templates',
                templatesToShow
              );
            } else {
              setNodeMenuStateInternal('search');
            }
          }
        });

        menu.setFooter(nodeEditor.renderFooter());
      }
    }
  };

  setNodeMenuState = setNodeMenuStateInternal;

  const render = () => {
    setNodeMenuStateInternal('search');
  };

  return {
    setNodeMenuState: setNodeMenuStateInternal,
    render
  };
}