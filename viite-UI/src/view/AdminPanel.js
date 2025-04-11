(function (root) {
    root.AdminPanel = function (backend) {
        const adminPanelWindow =
            $(
                '<div class="generic-window" id="adminPanelWindow">' +
                    '<div class="generic-window-header"> ' +
                        '<p>Admin Paneeli</p>' +
                        '<button class="close btn-close" id="closeAdminPanel">x</button>' +
                    '</div>' +
                    '<div id="adminPanelWindowContent"></div>' +
                '</div>');

        const navBar =
            $('<nav class="navbar">' +
                '<button class="tab-button active" data-tab="tab1">Dynaaminen tielinkkiverkko</button>' +
                '<button class="tab-button" data-tab="tab2">Käyttäjien hallinta</button>' +
                '<button class="tab-button" data-tab="tab3">Alkulataus</button>' +
                '<button class="tab-button" data-tab="tab4">Tieosoiteverkon virheet</button>' +
                '</nav>');

        const contentForTabs = $(
                '<div class="content-area">' +
                    '<div id="tab1" class="tab-content active">' +
                        '<p>TODO Dynaamisen verkon hallinta tapahtuu täällä</p>' +
                    '</div>' +
                    '<div id="tab2" class="tab-content">' +
                        '<p>TODO Käyttäjien hallinta tapahtuu täällä</p>' +
                    '</div>' +
                    '<div id="tab3" class="tab-content">' +
                        '<p>TODO Alkulatauksen käynnistys tapahtuu täältä</p>' +
                    '</div>' +
                    '<div id="tab4" class="tab-content">' +
                        '<p>TODO Tieosoiteverkon virheet listaus siirtyy tänne</p>' +
                    '</div>' +
                '</div>'
            );

        const showAdminPanelWindow = function () {
            $('.container').append(
                '<div class="admin-panel-modal-overlay viite-modal-overlay confirm-modal" id="adminPanel">' +
                '<div class="admin-panel-modal-window"></div>' +
                '</div>');

            $('.admin-panel-modal-window').append(adminPanelWindow);

            const contentWrapper = $('#adminPanelWindowContent');
            contentWrapper.append(navBar);
            contentWrapper.append(contentForTabs);

            bindEvents();
        };

        const hideAdminPanelWindow = function () {
            $('.admin-panel-modal-overlay').remove();
        };

        const bindEvents = function () {

            $('.generic-window-header').on('click', '#closeAdminPanel', function() {
                hideAdminPanelWindow();
            });

            // Navbar Tab Button Event Binding
            const contentWrapper = $('#adminPanelWindowContent'); // Get the main content container

            // Use event delegation: listen for clicks on the contentWrapper,
            // but only trigger the function if the click happened on an element
            // matching '.navbar .tab-button' inside the wrapper.
            contentWrapper.on('click', '.navbar .tab-button', function() {
                // 'this' refers to the specific .tab-button that was clicked
                const clickedButton = $(this);

                // If the clicked button is already active, do nothing
                if (clickedButton.hasClass('active')) {
                    return;
                }

                const tabButtons = contentWrapper.find('.navbar .tab-button');
                const tabContents = contentWrapper.find('.content-area .tab-content');

                // Deactivate all buttons and hide all content panes within this window
                tabButtons.removeClass('active');
                tabContents.removeClass('active'); // Hides content with CSS rule

                // Activate the clicked button
                clickedButton.addClass('active');

                // Activate the corresponding content pane
                // Construct the ID selector (e.g., #tab1) and find it within the contentWrapper
                const targetTabId = clickedButton.data('tab'); // Get the value of data-tab="xxx"
                const targetTabContent = contentWrapper.find('#' + targetTabId);
                targetTabContent.addClass('active'); // Makes content visible via CSS rule
            });
        };

        return {
            showAdminPanelWindow: showAdminPanelWindow
        };
    };
}(this));
