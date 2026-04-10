/**
 * MainMenu - Renders the main navigation panel and manages top-level UI state.
 * Switches between the main button menu, link info view, and delegated module states.
 */
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { LinkInfo } from './link-info/LinkInfo.js';
import { MenuContainer } from '@components/MenuContainer.js';
import { NodeMenu } from '@node-menu/NodeMenu.js';
import { ProjectList } from '@view/project-menu/project-list/ProjectList.js';
import { AdminPanel } from '@view/admin-panel/AdminPanel.js';
import { RoadAddressBrowserWindow } from '@view/road-address-inspection/RoadAddressBrowserWindow.js';
import { RoadAddressChangesBrowserWindow } from '@view/road-address-inspection/RoadAddressChangesBrowserWindow.js';
import { RoadNamingToolWindow } from '@view/road-name-maintenance-modal/RoadNamingToolWindow.js';
import { RoadNetworkErrorsList } from '@view/road-network-errors-list/RoadNetworkErrorsList.js';
import { dateutil } from '@utils/DateUtils.js';
import { eventbus } from '@utils/eventbus.js';

/**
 * @param {Object} options
 * @param {Object} options.selectedLinkProperty
 * @param {Object} options.applicationModel
 * @param {Object} options.backend
 * @param {Object} options.map
 * @param {Object} options.eventbus
 * @param {Object} options.projectCollection
 * @param {Object} options.selectedProjectLinkProperty
 * @param {Object} options.projectLinkLayer
 * @param {Object} options.projectChangeInfoModel
 * @param {Object} options.roadNameCollection
 * @param {Object} options.models
 * @param {Object} options.models.nodeCollection
 * @param {Object} options.models.selectedNodesAndJunctions
 * @param {Object} options.models.roadCollection
 */
export function MainMenu(options = {}) {
  const selectedLinkProperty = options.selectedLinkProperty;
  const rootElement = $('#menu-container');
  const linkInfo = new LinkInfo(selectedLinkProperty);
  const applicationModel = options.applicationModel;
  const startupParameters = applicationModel.getStartupParameters();
  const activeEventbus = options.eventbus || eventbus;
  const projectCollection = options.projectCollection;
  const models = options.models || {};
  const roadNamingTool = new RoadNamingToolWindow(options.roadNameCollection);
  const roadAddressBrowser = new RoadAddressBrowserWindow(options.backend);
  const roadAddressChangesBrowser = new RoadAddressChangesBrowserWindow(options.backend);
  const roadNetworkErrorsList = new RoadNetworkErrorsList(options.backend, { applicationModel: applicationModel });
  const adminPanel = new AdminPanel(options.backend, {
    applicationModel: applicationModel
  });
  const nodeMenu = new NodeMenu(
    options.map,
    models.nodeCollection,
    options.backend,
    models.selectedNodesAndJunctions,
    models.roadCollection,
    startupParameters,
    {
      applicationModel: applicationModel,
      dateutil: dateutil,
      moment: moment,
      navigateToHash: function (hashValue) {
        location.hash = hashValue;
      }
    }
  );
  let menu = null;
  const mainMenuApi = { setState: () => undefined };

  if (nodeMenu) {
    nodeMenu.initialize();
  }

  const showProjectList = () => {
    const projectList = new ProjectList(projectCollection, {
      applicationModel: applicationModel,
      map: options.map,
      backend: options.backend,
      eventbus: activeEventbus,
      mainMenu: mainMenuApi,
      selectedProjectLinkProperty: options.selectedProjectLinkProperty,
      projectLinkLayer: options.projectLinkLayer,
      projectChangeInfoModel: options.projectChangeInfoModel,
      startupParameters: startupParameters
    });
    projectList.show();
  };

  const createMenuContainer = () => {
    if (!MenuContainer) {
      return null;
    }
      return new MenuContainer(rootElement);
  };

    const renderBody = (html, config = {}) => {
      const header = config.header || '';
      const onClose = config.onClose || null;
      menu = createMenuContainer();
      if (menu) {
        menu.setHeader(header);
        menu.setOnClose(onClose);
        menu.setBody(html);
      } else {
        rootElement.html(html);
      }
    };

    const setState = (state, data) => {
      switch (state) {
        case 'main':
          renderBody(renderMainMenuBody());
          bindMenuActions();
          break;
        case 'linkInfo':
          renderBody(linkInfo.render(data), {
            header: 'Tieosoitteen ominaisuustiedot',
            onClose: () => setState('main')
          });
          break;
        case 'project':
          if (applicationModel.isProjectOpen()) {
            new ConfirmPopup('Projektin muokkaus on kesken...', { type: 'alert' });
          } else {
            showProjectList();
          }
          break;
        case 'nameTool':
          roadNamingTool.show();
          break;
        case 'node':
          nodeMenu.show();
          break;
        case 'roadAddressBrowser':
          roadAddressBrowser.show();
          break;
        case 'roadAddressChangesBrowser':
          roadAddressChangesBrowser.show();
          break;
        case 'roadNetworkErrors':
          roadNetworkErrorsList.show();
          break;
        case 'adminPanel':
          adminPanel.show();
          break;
        default:
          renderBody(renderMainMenuBody());
          bindMenuActions();
          break;
      }
    };

    const renderMainMenuBody = () => {
      const roles = startupParameters.roles;
      const isUserAdmin = _.includes(roles, 'admin');
      const isUserOperator = _.includes(roles, 'operator');
      const hasUserBasicRights = _.includes(roles, 'viite');

      return `
        <div class="main-menu-container">
          <div class="main-menu-button-wrapper">
            ${hasUserBasicRights ? `
              <button id="formProjectButton" class="btn-primary btn-lg">Tieosoiteprojektit</button>
              <button id="formNameToolButton" class="btn-primary btn-lg">Tiennimen ylläpito</button>
            ` : ''}
            <button id="formNodesAndJunctionsButton" class="btn-primary btn-lg">Solmut ja liittymät</button>
            <button id="formRoadAddressBrowserButton" class="btn-primary btn-lg">Tieosoitteiden katselu</button>
            <button id="formRoadAddressChangesBrowserButton" class="btn-primary btn-lg">Tieosoitemuutosten katselu</button>
            ${isUserOperator ? `<button id="formRoadNetworkErrorsListButton" class="btn-primary btn-lg">Tieosoiteverkon virheet</button>` : ''}
            ${isUserAdmin ? `<button id="formAdminPanelButton" class="btn-primary btn-lg">Admin paneeli</button>` : ''}
          </div>
        </div>`;
    };

    const bindMenuActions = () => {
      const buttonToState = {
        formProjectButton: 'project',
        formNameToolButton: 'nameTool',
        formNodesAndJunctionsButton: 'node',
        formRoadAddressBrowserButton: 'roadAddressBrowser',
        formRoadAddressChangesBrowserButton: 'roadAddressChangesBrowser',
        formRoadNetworkErrorsListButton: 'roadNetworkErrors',
        formAdminPanelButton: 'adminPanel'
      };

      rootElement.off('click.mainMenu', 'button');
      
      rootElement.on('click.mainMenu', 'button', (e) => {
        e.preventDefault();
        const buttonId = e.currentTarget.id;
        const nextState = buttonToState[buttonId];
        if (nextState) {
          setState(nextState);
        }
      });
    };

    const bindEvents = () => {

      // When link is clicked on a map, show link properties
      activeEventbus.on('linkProperties:selected linkProperties:cancelled', (linkProperties) => {
        const props = _.isArray(linkProperties) ? _.head(linkProperties) : linkProperties;
        if (props) setState('linkInfo', props);
      });

      // Close link properties menu when map is clicked that doesn't have a link
      activeEventbus.on('linkProperties:unselected', () => {
        if (!applicationModel.isProjectOpen()) setState('main');
      });
      
      // Close nodes and junctions menu and return to main menu
      activeEventbus.on('nodesAndJunctions:close', () => {
        setState('main');
      });
      
    };

  bindEvents();
  setState('main');
  mainMenuApi.setState = setState;
  
  return {
    setState
  };
}
