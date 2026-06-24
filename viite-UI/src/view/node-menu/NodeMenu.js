import { NodeDataMenu } from '@node-menu/NodeDataMenu.js';
import { NodeEditor } from '@node-menu/NodeEditor.js';
import { NodeSearchMenu } from '@node-menu/NodeSearchMenu.js';
import { setMainMenuState } from '@view/MainMenu.js';
import { getStartupParameters, selectLayer } from '@model/ApplicationModel.js';

const stateConfig = {};

export const setNodeMenuState = (newState, cancelTarget) => {
	const state = stateConfig[newState];
	if (!state) throw new Error(`Unknown node menu state: ${newState}`);
	state.render(cancelTarget);
};

// High-level state manger that decides which menu content to show based on the current state
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

	const permissionToEditNodes = getStartupParameters()?.roles?.includes('viite') ?? false;

	const searchMenu = new NodeSearchMenu(
		map,
		nodeCollection,
		backend,
		selectedNodesAndJunctions
	);

	const dataMenu = new NodeDataMenu (selectedNodesAndJunctions);

	const nodeEditor = new NodeEditor (
		selectedNodesAndJunctions,
		backend,
		roadCollection,
		() => menu.getBody(),
		permissionToEditNodes
	);

	// Maps menu states to their render functions, which are responsible for rendering the correct content and header for each state
	Object.assign(stateConfig, {
		search: {
			render: () => {
				menu.setHeader('Solmut ja liittymät', closeNodeMenu);
				menu.setBody(searchMenu.render());
				menu.setFooter('');
			}
		},
		'display-templates': {
			render: () => {
				const effectiveTemplates = selectedNodesAndJunctions.getCurrentTemplates() || {};

				menu.setHeader('Aihioiden tiedot', closeNodeMenu);
				menu.setBody(dataMenu.renderBody(effectiveTemplates));
				menu.setFooter(dataMenu.renderFooter());
			}
		},
		editor: {
			render: (cancelTarget = 'templates') => {
				const node = selectedNodesAndJunctions.getCurrentNode();
				const templates = selectedNodesAndJunctions.getCurrentTemplates();
				menu.setHeader(nodeEditor.getHeader(), closeNodeMenu);
				menu.setBody('');
				nodeEditor.showNode(node, templates, { cancelTarget });
				menu.setFooter(nodeEditor.renderFooter());
			}
		}
	});

	return { setNodeMenuState };
}