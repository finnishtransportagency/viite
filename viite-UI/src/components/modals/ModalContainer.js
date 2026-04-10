/**
 * @example
 * // Basic usage
 * const modal = new ModalContainer({
 *     helpUrl: 'manual.html#section',
 *     onClose: () => console.log('closed')
 * });
 * modal.open({
 *     title: 'Admin Paneeli',
 *     content: '<p>Content here</p>'
 * });
 */

/**
 * Configuration object for ModalContainer
 * @typedef {Object} ModalConfig
 * @property {string|null} [helpUrl=null] - URL for help button (null = no help button)
 * @property {string} [helpTitle='Avaa käyttöohje'] - Tooltip text for help button
 * @property {Function|null} [onClose=null] - Callback function when modal is closed
 * @property {Function|null} [onShow=null] - Callback function when modal is shown
 * @property {string} [className=''] - Additional CSS classes
 * @property {string} [style=''] - Custom inline styles
 */

/**
 * Configuration object for ModalContainer.open method
 * @typedef {Object} ModalOpenConfig
 * @property {string} [title=''] - Modal title displayed in header
 * @property {string|jQuery} content - HTML string or jQuery object to set as content
 */
        
export function ModalContainer(config) {

        const {
            helpUrl = null,
            helpTitle = 'Avaa käyttöohje',
            onClose = null,
            onShow = null,
            className = '',
            style = ''
        } = config;

        // Internal state variables
        let modalElement;            // jQuery object for modal container
        let overlayElement;          // jQuery object for dark overlay
        let contentContainer;        // jQuery object for content area
        let eventsBound = false;     // Track if events are already bound
        let currentTitle = '';       // Current modal title

        // Initialize modal immediately
        createModal();

        /**
         * Creates the modal header with title, optional help button, and close button
         * @returns {jQuery} Header element as jQuery object
         */
        function createHeader() {
            const headerHtml = `
                <div class="modal-header">
                    <p>${currentTitle}</p>
                    ${helpUrl ? `
                        <a href="${helpUrl}" target="_blank">
                            <button class="btn-manual" title="${helpTitle}">
                                <i class="fas fa-question"></i>
                            </button>
                        </a>
                    ` : ''}
                    <button class="close btn-close-modal">x</button>
                </div>
            `;
            return $(headerHtml);
        }

        /**
         * Creates the main modal container with header and content area
         * @returns {jQuery} Modal container element as jQuery object
         */
        function createModal() {
            const styleAttr = style ? ` style="${style}"` : '';
            modalElement = $(`<div class="modal-container ${className}"${styleAttr}></div>`).hide();
            const header = createHeader();
            contentContainer = $(`<div class="modal-content modal-content-scrollable"></div>`);
            
            modalElement.append(header);
            modalElement.append(contentContainer);
            
            return modalElement;
        }

        /**
         * Creates the overlay with dark background and modal window container
         * @returns {jQuery} Overlay element as jQuery object
         */
        function createOverlay() {
            overlayElement = $('<div class="modal-overlay viite-modal-overlay confirm-modal"></div>');
            const windowContainer = $('<div class="modal-window"></div>');
            windowContainer.append(modalElement);
            overlayElement.append(windowContainer);
            return overlayElement;
        }

        /**
         * Binds event handlers for modal interactions
         * Handles close button clicks, overlay clicks, and ESC key
         */
        function bindEvents() {
            if (!modalElement || eventsBound) return;
            
            eventsBound = true;

            // Close button click handler
            modalElement.on('click', 'button.close, button.btn-close-modal', function () {
                close();
                if (typeof onClose === 'function') {
                    onClose();
                }
            });
        }

        /**
         * Shows the modal with dark overlay
         * Creates overlay if needed and appends to DOM
         * @param {Object} openConfig - Configuration for opening modal
         * @param {string} [openConfig.title=''] - Modal title
         * @param {string|jQuery} openConfig.content - Modal content
         */
        function open(openConfig) {
            const { title = '', content } = openConfig;
            
            // Update title and content
            currentTitle = title;
            const titleElement = modalElement.find('.modal-header p');
            if (titleElement.length) {
                titleElement.text(title);
            }
            
            if (content !== undefined && contentContainer) {
                contentContainer.empty().append(content);
            }
            
            if (!overlayElement) {
                createOverlay();
                bindEvents();
            }
            
            $('.container').append(overlayElement);
            modalElement.show();
            
            // Call onShow callback if provided
            if (typeof onShow === 'function') {
                onShow();
            }
        }

        /**
         * Hides the modal and removes overlay from DOM
         */
        function close() {
            
            modalElement.hide();
            if (overlayElement) {
                modalElement.detach(); // Remove modal from DOM but keep event listeners
                overlayElement.remove();
                overlayElement = null;
            }
        }

        function getContent() {
            return contentContainer ? contentContainer : $();
        }

        return {
            open: open,
            close: close,
            getContent: getContent
        };
}
