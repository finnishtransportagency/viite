export function initNotificationBanner(backend) {
	backend.getNotificationBanner(function (data) {
		const message = data && data.message;
		if (!message) return;

		const banner = document.createElement('div');
		banner.id = 'notification-banner';
		banner.className = 'notification-banner';

		const messageText = document.createElement('span');
		messageText.className = 'banner-message';
		messageText.textContent = message;

		const closeButton = document.createElement('button');
		closeButton.type = 'button';
		closeButton.className = 'banner-close';
		closeButton.setAttribute('aria-label', 'Close notification');
		closeButton.textContent = '\u00D7';
		closeButton.addEventListener('click', function () {
			if (banner.parentNode) {
				banner.parentNode.removeChild(banner);
			}
		});

		banner.appendChild(messageText);
		banner.appendChild(closeButton);

		const header = document.getElementById('header');
		if (header && header.parentNode) {
			header.parentNode.insertBefore(banner, header);
		}
	});
}
