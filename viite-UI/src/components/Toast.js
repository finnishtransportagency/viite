// Toast for a duration based on message length (3–5 seconds)
// Useful for notifying user about errors and successes without requiring manual dismissal
/* Usage:
import { showToast } from '@/components/Toast.js';

document.getElementById('save-button').addEventListener('click', function () {
    showToast('Tallennettu onnistuneesti', { type: 'success' });
});
*/

let container;

const icons = {
    info: 'ℹ️',
    success: '✅',
    warning: '⚠️',
    error: '❌'
};

export function showToast(message, options = {}) {
    container = container || document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        document.body.appendChild(container);
    }

    const { type = 'info' } = options;

    // Calculate duration based on message length
    const baseDuration = message.length * 70;
    const duration = Math.min(Math.max(baseDuration, 3000), 5000); // Clamp between 3000 and 5000 ms

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

