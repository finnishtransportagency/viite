// Usage example: Toast.show("Message", { type: 'success' });
// Do not add "." at the end of the message to maintain consistency

(function (root) {
    root.Toast = (function () {
        let container;

        const icons = {
            info: 'ℹ️',
            success: '✅',
            warning: '⚠️',
            error: '❌'
        };

        function ensureContainer() {
            if (!container) {
                container = document.createElement('div');
                container.id = 'toast-container';
                document.body.appendChild(container);
            }
        }

        function show(message, options = {}) {
            ensureContainer();

            const { type = 'info', duration = 4500 } = options;
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
