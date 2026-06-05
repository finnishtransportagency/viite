import { DynamicLinkNetworkContent as dynamicLinkNetworkContent } from './DynamicLinkNetworkContent.js';
import { ModalContainer } from '@components/modals/ModalContainer.js';
import { UserManagagement } from './user-management/UserManagement.js';
import { button } from '@components/button/Button.js';

// Panel that contains all the tools available for users with admin role
export function AdminPanel(backend) {



        const dynamicLinkNetwork = dynamicLinkNetworkContent(backend);

        const showAdminPanelWindow = function () {
            const modalContainer = new ModalContainer({
                className: 'admin-panel-modal'
            });

            const navBar = `
                <nav class="navbar">
                    ${button({ id: 'tab-btn-tab1', label: 'Dynaaminen tielinkkiverkko', className: 'tab-button active', onClick: () => controlTabs($('#tab-btn-tab1'), $('#adminPanelWindowContent'), 'tab1') })}
                    ${button({ id: 'tab-btn-tab2', label: 'Käyttäjien hallinta', className: 'tab-button', onClick: () => controlTabs($('#tab-btn-tab2'), $('#adminPanelWindowContent'), 'tab2') })}
                    ${button({ id: 'tab-btn-tab3', label: 'Alkulataus', className: 'tab-button', onClick: () => controlTabs($('#tab-btn-tab3'), $('#adminPanelWindowContent'), 'tab3') })}
                    ${button({ id: 'tab-btn-tab4', label: 'Tieosoiteverkon virheet', className: 'tab-button', onClick: () => controlTabs($('#tab-btn-tab4'), $('#adminPanelWindowContent'), 'tab4') })}
                </nav>
            `;

            const contentForTabs = `
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
            `;

            const contentWrapper = $('<div id="adminPanelWindowContent"></div>');
            contentWrapper.append(navBar);
            contentWrapper.append(contentForTabs);

            modalContainer.open({
                title: 'Admin Paneeli',
                content: contentWrapper
            });

            // Re-bind everything fresh
            dynamicLinkNetwork.addDatePickersToInputFields();
            dynamicLinkNetwork.bindEvents('.modal-container');

            // Initialize the new user management module
            
            UserManagagement.init('#userManagementPanelContainer', {});
        };

        const controlTabs = function (clickedButton, contentWrapper, targetTabId) {
            if (clickedButton.hasClass('active')) return;

            const tabButtons = contentWrapper.find('.navbar .tab-button');
            const tabContents = contentWrapper.find('.content-area .tab-content');

            tabButtons.removeClass('active');
            tabContents.removeClass('active');

            clickedButton.addClass('active');
            contentWrapper.find(`#${targetTabId}`).addClass('active');
        };

        return {
            show: showAdminPanelWindow
        };
}
