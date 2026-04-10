/**
 * MainMenu - Renders the main navigation panel and manages top-level UI state.
 * Switches between the main button menu, link info view, and delegated module states.
 */
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { LinkInfo } from './link-info/LinkInfo.js';
import { MenuContainer } from '@components/MenuContainer.js';
import { ProjectList } from '@view/project-menu/project-list/ProjectList.js';
import { eventbus } from '@utils/eventbus.js';

export function MainMenu(selectedLinkProperty, roadNamingTool, roadAddressBrowser, roadAddressChangesBrowser, startupParameters, roadNetworkErrorsList, adminPanel, nodesAndJunctionsModule, options = {}) {
  const rootElement = $('#menu-container');
  const linkInfo = new LinkInfo(selectedLinkProperty);
  const applicationModel = options.applicationModel;
  const activeEventbus = options.eventbus || eventbus;
  const projectCollection = options.projectCollection;
  let menu = null;

  const showProjectList = () => {
    const projectList = new ProjectList(projectCollection, {
      applicationApi: options.applicationApi,
      applicationModel: applicationModel,
      projectMenu: options.projectMenu
    });
    projectList.show();
  };

  const createMenuContainer = () => {
    if (!MenuContainer) {
      return null;
    }
      return new MenuContainer(rootElement);
  };

    const renderBody = (html) => {
      menu = createMenuContainer();
      if (menu) {
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
          renderBody(linkInfo.render(data));
          break;
        case 'project':  /* Handled by ProjectList */ break;
        case 'node':     /* Handled by NodesAndJunctions component */ break;
        default:
          renderBody(renderMainMenuBody());
          bindMenuActions();
          break;
      }
    };

    const renderMainMenuBody = () => {
      const roles = startupParameters.roles;
      return `
        <div class="main-menu-container">
          <div class="main-menu-button-wrapper">
            ${_.includes(roles, 'viite') ? `
              <button id="formProjectButton" class="btn-primary btn-lg">Tieosoiteprojektit</button>
              <button id="formNameToolButton" class="btn-primary btn-lg">Tiennimen ylläpito</button>
            ` : ''}
            <button id="formNodesAndJunctionsButton" class="btn-primary btn-lg">Solmut ja liittymät</button>
            <button id="formRoadAddressBrowserButton" class="btn-primary btn-lg">Tieosoitteiden katselu</button>
            <button id="formRoadAddressChangesBrowserButton" class="btn-primary btn-lg">Tieosoitemuutosten katselu</button>
            ${_.includes(roles, 'operator') ? `<button id="formRoadNetworkErrorsListButton" class="btn-primary btn-lg">Tieosoiteverkon virheet</button>` : ''}
            ${_.includes(roles, 'admin') ? `<button id="formAdminPanelButton" class="btn-primary btn-lg">Admin paneeli</button>` : ''}
          </div>
        </div>`;
    };

    const bindMenuActions = () => {
      rootElement.off('click.mainMenu', 'button');
      
      rootElement.on('click.mainMenu', 'button', (e) => {
        e.preventDefault();
        const buttonId = e.currentTarget.id;
        let projectOpen;

        switch (buttonId) {
          case 'formProjectButton':
            projectOpen = applicationModel.isProjectOpen();
            if (projectOpen) {
              new ConfirmPopup("Projektin muokkaus on kesken...", { type: "alert" });
            } else {
              showProjectList();
            }
            break;

          case 'formNameToolButton':
            roadNamingTool.show();
            break;

          case 'formNodesAndJunctionsButton':
            nodesAndJunctionsModule.show();
            break;

          case 'formRoadAddressBrowserButton':
            roadAddressBrowser.show();
            break;

          case 'formRoadAddressChangesBrowserButton':
            roadAddressChangesBrowser.show();
            break;

          case 'formRoadNetworkErrorsListButton':
            roadNetworkErrorsList.show();
            break;

          case 'formAdminPanelButton':
            adminPanel.show();
            break;
          
          default:
            break;
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
  
  return {
    setState
  };
}
