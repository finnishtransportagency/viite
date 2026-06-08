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
import { button } from '@components/button/Button.js';

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
    linkInfo: new LinkInfo(selectedLinkProperty),
    projectList: new ProjectList({ ...options, eventbus: activeEventbus, menu, roadCollection }),
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
          ${button({ id: 'formProjectButton', label: 'Tieosoiteprojektit', className: 'btn-primary btn-lg', onClick: () => setState(MENU_STATE.PROJECT) })}
          ${button({ id: 'formNameToolButton', label: 'Tiennimen ylläpito', className: 'btn-primary btn-lg', onClick: () => setState(MENU_STATE.NAME_TOOL) })}
        ` : ''}

        ${button({ id: 'formNodesAndJunctionsButton', label: 'Solmut ja liittymät', className: 'btn-primary btn-lg', onClick: () => setState(MENU_STATE.NODE) })}
        ${button({ id: 'formRoadAddressBrowserButton', label: 'Tieosoitteiden katselu', className: 'btn-primary btn-lg', onClick: () => setState(MENU_STATE.ROAD_ADDRESS_BROWSER) })}
        ${button({ id: 'formRoadAddressChangesBrowserButton', label: 'Tieosoitemuutosten katselu', className: 'btn-primary btn-lg', onClick: () => setState(MENU_STATE.ROAD_ADDRESS_CHANGES) })}

        ${hasRole('operator') ? button({ id: 'formRoadNetworkErrorsListButton', label: 'Tieosoiteverkon virheet', className: 'btn-primary btn-lg', onClick: () => setState(MENU_STATE.ROAD_NETWORK_ERRORS) }) : ''}
        ${hasRole('admin') ? button({ id: 'formAdminPanelButton', label: 'Admin paneeli', className: 'btn-primary btn-lg', onClick: () => setState(MENU_STATE.ADMIN_PANEL) }) : ''}
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
      menu.setHeader('Tieosoitteen ominaisuustiedot', () => selectedLinkProperty.close());
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

  function bindMenuActions() {}

  setMainMenuState = setState;
  menu.setDefaultClose(() => setState(MENU_STATE.MAIN));
  setState(MENU_STATE.MAIN);

  return { setState };
}
