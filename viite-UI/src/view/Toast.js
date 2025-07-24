// Toast for a duration based on message length (4–8 seconds)
// Useful for notifying user about success/failure events related to backend for example
// Usage example: Toast.show("Message", { type: 'success' });
// Add "." at the end of the message to maintain consistency

(function (root) {
    root.Toast = (function () {
        let container;

        const icons = {
            info: 'ℹ️',
            success: '✅',
            warning: '⚠️',
            error: '❌'
        };

        // Create toast container if it doesn't exist
        function ensureContainer() {
            if (!container) {
                container = document.createElement('div');
                container.id = 'toast-container';
                document.body.appendChild(container);
            }
        }

        function show(message, options = {}) {
            ensureContainer();

            const { type = 'info' } = options;

            // Calculate duration based on message length (e.g., 60ms per character)
            const baseDuration = message.length * 60;
            const duration = Math.min(Math.max(baseDuration, 4000), 8000); // Clamp between 4000 and 8000 ms

            const toast = document.createElement('div');
            toast.className = `toast ${type}`;

            const icon = document.createElement('span');
            icon.className = 'toast-icon';
            icon.textContent = icons[type] || '';

            const text = document.createElement('span');
            text.className = 'toast-message';
            text.textContent = message;

            toast.appendChild(icon);
            toast.appendChild(text);
            container.appendChild(toast);

            setTimeout(() => {
                toast.classList.add('hide');
                toast.addEventListener('animationend', () => toast.remove());
            }, duration);
        }


        return { show };
    })();
})(this);
