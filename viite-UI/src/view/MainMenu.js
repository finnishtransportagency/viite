/**
 * MainMenu - Renders the main navigation panel and manages top-level UI state.
 * Switches between the main button menu, link info view, and delegated module states.
 */
(function (root) {
  root.MainMenu = function (selectedLinkProperty, roadNamingTool, projectList, roadAddressBrowser, roadAddressChangesBrowser, startupParameters, roadNetworkErrorsList, adminPanel, nodesAndJunctionsModule) {
    const rootElement = $('#menu-container');
    const linkInfo = new root.LinkInfo(selectedLinkProperty);
    let menu = null;

    const createMenuContainer = () => {
      if (!root.MenuContainer) {
        return null;
      }
        return new root.MenuContainer(rootElement);
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
              projectList.show();
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
      eventbus.on('linkProperties:selected linkProperties:cancelled', (linkProperties) => {
        const props = _.isArray(linkProperties) ? _.head(linkProperties) : linkProperties;
        if (props) setState('linkInfo', props);
      });

      // Close link properties menu when map is clicked that doesn't have a link
      eventbus.on('linkProperties:unselected', () => {
        if (!applicationModel.isProjectOpen()) setState('main');
      });
      
      // Close nodes and junctions menu and return to main menu
      eventbus.on('nodesAndJunctions:close', () => {
        setState('main');
      });
      
    };

    bindEvents();
    setState('main');
    
    return {
      setState
    };
  };
}(this));
