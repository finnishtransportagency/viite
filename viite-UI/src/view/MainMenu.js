(function (root) {
  root.MainMenu = function (selectedLinkProperty, roadNamingTool, projectList, roadAddressBrowser, roadAddressChangesBrowser, startupParameters, roadNetworkErrorsList, adminPanel) {
    const rootElement = $('#feature-attributes');
    const linkInfo = new root.LinkInfo(selectedLinkProperty);

    const setState = (state, data) => {
      rootElement.empty();
      switch (state) {
        case 'main':     renderMainMenu(); break;
        case 'linkInfo': rootElement.html(linkInfo.render(data)); break;
        case 'project':  /* Handled by ProjectList */ break;
        case 'node':     /* Handled by NodesAndJunctions component */ break;
        default:         renderMainMenu(); break;
      }
    };

    const renderMainMenu = () => {
      const roles = startupParameters.roles;
      const html = `
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
      
      rootElement.append(html);
      bindMenuActions();
    };

    const bindMenuActions = () => {
      // Remove any existing event handlers to prevent duplicates
      rootElement.off('click', 'button');
      
      rootElement.on('click', 'button', (e) => {
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
            //eventbus.trigger('nodesAndJunctions:open');
            // nodesAndJuctionsMenu.show();
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
      
    };

    bindEvents();
    setState('main');
    
    return {
      setState
    };
  };
}(this));
