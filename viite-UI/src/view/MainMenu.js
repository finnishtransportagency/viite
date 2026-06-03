/**
 * MainMenu - Renders the main navigation panel and manages top-level UI state.
 * Switches between the main button menu, link info view, and delegated module states.
 */

import { LinkInfo } from './link-info/LinkInfo.js';
import { MenuContainer } from '@components/MenuContainer.js';
import { NodeMenu } from '@node-menu/NodeMenu.js';
import { ProjectList } from '@view/project-menu/project-list/ProjectList.js';
import { AdminPanel } from '@view/admin-panel/AdminPanel.js';
import { RoadAddressBrowserWindow } from '@view/road-address-inspection/RoadAddressBrowserWindow.js';
import { RoadAddressChangesBrowserWindow } from '@view/road-address-inspection/RoadAddressChangesBrowserWindow.js';
import { RoadNamingToolWindow } from '@view/road-name-maintenance-modal/RoadNamingToolWindow.js';
import { RoadNetworkErrorsList } from '@view/road-network-errors-list/RoadNetworkErrorsList.js';
import { getStartupParameters } from '@model/ApplicationModel.js';

export const MENU_STATE = {
  MAIN: 'main',
  LINK_INFO: 'linkInfo',
  PROJECT: 'project',
  NAME_TOOL: 'nameTool',
  NODE: 'node',
  ROAD_ADDRESS_BROWSER: 'roadAddressBrowser',
  ROAD_ADDRESS_CHANGES: 'roadAddressChangesBrowser',
  ROAD_NETWORK_ERRORS: 'roadNetworkErrors',
  ADMIN_PANEL: 'adminPanel'
};

let setMainMenuState = () => {};

export { setMainMenuState };

export function MainMenu(options = {}) {
  const rootElement = $('#menu-container');
  const {
    eventbus: activeEventbus,
    selectedLinkProperty,
    roadNameCollection,
    backend,
    map,
    models = {}
  } = options;

  const {
    nodeCollection,
    selectedNodesAndJunctions,
    roadCollection
  } = models;

  const roles = getStartupParameters().roles || [];

  const menu = new MenuContainer();
  if (!rootElement.length) {
    console.error('MainMenu: #menu-container was not found');
    return { setState: () => {} };
  }
  rootElement[0].appendChild(menu.root);

  const views = {
    linkInfo: new LinkInfo(selectedLinkProperty, menu),
    projectList: new ProjectList({ ...options, eventbus: activeEventbus, menu }),
    roadNamingTool: new RoadNamingToolWindow(roadNameCollection),
    roadAddressBrowser: new RoadAddressBrowserWindow(backend),
    roadAddressChangesBrowser: new RoadAddressChangesBrowserWindow(backend),
    roadNetworkErrorsList: new RoadNetworkErrorsList(backend, {}),
    adminPanel: new AdminPanel(backend, {}),
    nodeMenu: new NodeMenu(
      map,
      nodeCollection,
      backend,
      selectedNodesAndJunctions,
      roadCollection,
      menu
    )
  };

  const hasRole = (role) => _.includes(roles, role);

  const renderMainMenu = () => `
    <div class="main-menu-container">
      <div class="main-menu-button-wrapper">
        ${hasRole('viite') ? `
          <button id="formProjectButton" class="btn-primary btn-lg">Tieosoiteprojektit</button>
          <button id="formNameToolButton" class="btn-primary btn-lg">Tiennimen ylläpito</button>
        ` : ''}

        <button id="formNodesAndJunctionsButton" class="btn-primary btn-lg">Solmut ja liittymät</button>
        <button id="formRoadAddressBrowserButton" class="btn-primary btn-lg">Tieosoitteiden katselu</button>
        <button id="formRoadAddressChangesBrowserButton" class="btn-primary btn-lg">Tieosoitemuutosten katselu</button>

        ${hasRole('operator') ? '<button id="formRoadNetworkErrorsListButton" class="btn-primary btn-lg">Tieosoiteverkon virheet</button>' : ''}
        ${hasRole('admin') ? '<button id="formAdminPanelButton" class="btn-primary btn-lg">Admin paneeli</button>' : ''}
      </div>
    </div>`;

  const showMain = () => {
    menu.setHeader();
    menu.setFooter();
    menu.setBody(renderMainMenu());
    bindMenuActions();
  };

  const actions = {
    [MENU_STATE.MAIN]: showMain,
    [MENU_STATE.LINK_INFO]: (data) => {
      menu.setHeader('Tieosoitteen ominaisuustiedot');
      menu.setFooter();
      menu.setBody(views.linkInfo.render(data));
    },
    [MENU_STATE.PROJECT]: () => views.projectList.show(),
    [MENU_STATE.NAME_TOOL]: () => views.roadNamingTool.show(),
    [MENU_STATE.NODE]: () => views.nodeMenu.render(),
    [MENU_STATE.ROAD_ADDRESS_BROWSER]: () => views.roadAddressBrowser.show(),
    [MENU_STATE.ROAD_ADDRESS_CHANGES]: () => views.roadAddressChangesBrowser.show(),
    [MENU_STATE.ROAD_NETWORK_ERRORS]: () => views.roadNetworkErrorsList.show(),
    [MENU_STATE.ADMIN_PANEL]: () => views.adminPanel.show()
  };

  function setState(state, data) {
    (actions[state] || actions[MENU_STATE.MAIN])(data);
  }

  function bindMenuActions() {
    const buttonMap = {
      formProjectButton: MENU_STATE.PROJECT,
      formNameToolButton: MENU_STATE.NAME_TOOL,
      formNodesAndJunctionsButton: MENU_STATE.NODE,
      formRoadAddressBrowserButton: MENU_STATE.ROAD_ADDRESS_BROWSER,
      formRoadAddressChangesBrowserButton: MENU_STATE.ROAD_ADDRESS_CHANGES,
      formRoadNetworkErrorsListButton: MENU_STATE.ROAD_NETWORK_ERRORS,
      formAdminPanelButton: MENU_STATE.ADMIN_PANEL
    };

    const mainMenuButtonSelector = [
      '#formProjectButton',
      '#formNameToolButton',
      '#formNodesAndJunctionsButton',
      '#formRoadAddressBrowserButton',
      '#formRoadAddressChangesBrowserButton',
      '#formRoadNetworkErrorsListButton',
      '#formAdminPanelButton'
    ].join(', ');

    rootElement.off('click.mainMenu', mainMenuButtonSelector);
    rootElement.on('click.mainMenu', mainMenuButtonSelector, (event) => {
      const next = buttonMap[event.currentTarget.id];
      if (next) {
        event.preventDefault();
        setState(next);
      }
    });
  }

  setMainMenuState = setState;
  menu.setDefaultClose(() => setState(MENU_STATE.MAIN));
  setState(MENU_STATE.MAIN);

  return { setState };
}
