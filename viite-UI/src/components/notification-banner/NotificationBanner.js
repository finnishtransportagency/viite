export function initNotificationBanner(backend) {
	backend.getNotificationBanner(function (data) {
	const message = data && data.message;
		if (!message) return;

		const banner = document.createElement('div');
		banner.id = 'notification-banner';
		banner.className = 'notification-banner';
		banner.textContent = message;

		const header = document.getElementById('header');
		if (header && header.parentNode) {
			header.parentNode.insertBefore(banner, header);
		}
	});
}
