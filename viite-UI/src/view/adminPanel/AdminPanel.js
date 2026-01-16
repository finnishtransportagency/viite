// Panel that contains all the tools available for users with admin role
(function (root) {

    root.AdminPanel = function (backend) {

        const dynamicLinkNetwork = window.dynamicLinkNetworkContent(backend, dateutil, ViiteConstants);

        const showAdminPanelWindow = function () {
            $('.container').append(`
                <div class="admin-panel-modal-overlay viite-modal-overlay confirm-modal" id="adminPanel">
                    <div class="admin-panel-modal-window"></div>
                </div>
            `);

            const freshAdminPanelWindow = $(`
                <div class="generic-window" id="adminPanelWindow">
                    <div class="generic-window-header">
                        <p>Admin Paneeli</p>
                        <button class="close wbtn-close" id="closeAdminPanel">
                            <i class="fas fa-window-close"></i>
                        </button>
                    </div>
                    <div id="adminPanelWindowContent"></div>
                </div>
            `);

            const navBar = $(`
                <nav class="navbar">
                    <button class="tab-button active" data-tab="tab1">Dynaaminen tielinkkiverkko</button>
                    <button class="tab-button" data-tab="tab2">Käyttäjien hallinta</button>
                    <button class="tab-button" data-tab="tab3">Alkulataus</button>
                    <button class="tab-button" data-tab="tab4">Tieosoiteverkon virheet</button>
                </nav>
            `);

            const contentForTabs = $(`
                <div class="content-area">
                    <div id="tab1" class="tab-content active">
                        ${dynamicLinkNetwork.getContent()}
                    </div>
                    <div id="tab2" class="tab-content">
                        <div id="userManagementPanelContainer"></div>
                    </div>
                    <div id="tab3" class="tab-content">
                        <p>TODO Alkulatauksen käynnistys tapahtuu täältä</p>
                    </div>
                    <div id="tab4" class="tab-content">
                        <p>TODO Tieosoiteverkon virheet listaus siirtyy tänne (ehkä?)</p>
                    </div>
                </div>
            `);

            $('.admin-panel-modal-window').append(freshAdminPanelWindow);
            const contentWrapper = $('#adminPanelWindowContent');

            contentWrapper.append(navBar);
            contentWrapper.append(contentForTabs);

            // Re-bind everything fresh
            dynamicLinkNetwork.addDatePickersToInputFields();
            dynamicLinkNetwork.bindEvents('.generic-window');

            // Initialize the new user management module
            if (root.UserManagement && root.UserManagement.Main) {
                root.UserManagement.Main.init('#userManagementPanelContainer');
            }

            bindEvents();
        };

        const hideAdminPanelWindow = function () {
            $('.admin-panel-modal-overlay').remove();
        };

        const controlTabs = function (clickedButton, contentWrapper) {
            if (clickedButton.hasClass('active')) return;

            const tabButtons = contentWrapper.find('.navbar .tab-button');
            const tabContents = contentWrapper.find('.content-area .tab-content');

            // Deactivate all buttons and hide all content panes within this window
            tabButtons.removeClass('active');
            tabContents.removeClass('active');

            clickedButton.addClass('active');

            // Activate the corresponding content pane
            // Construct the ID selector (e.g., #tab1) and find it within the contentWrapper
            const targetTabId = clickedButton.data('tab');
            const targetTabContent = contentWrapper.find(`#${targetTabId}`);
            targetTabContent.addClass('active');
        };

        const bindEvents = function () {
            $('.generic-window-header').on('click', '#closeAdminPanel', function () {
                hideAdminPanelWindow();
            });

            // Navbar Tab Button Event Binding
            const contentWrapper = $('#adminPanelWindowContent');

            // Use event delegation: listen for clicks on the contentWrapper,
            // but only trigger the function if the click happened on an element
            // matching '.navbar .tab-button' inside the wrapper.
            contentWrapper.on('click', '.navbar .tab-button', function () {
                // 'this' refers to the specific .tab-button that was clicked
                const clickedButton = $(this);
                controlTabs(clickedButton, contentWrapper);
            });
        };

        return {
            showAdminPanelWindow: showAdminPanelWindow
        };
    };
}(this));
