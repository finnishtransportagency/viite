import { Environment } from '@utils/EnvironmentUtils.js';
import { getRoadLinkDate } from '@utils/BackendUtils.js';

export function Header(startupParameters) {
	const container = jQuery('#header');
	renderHeader(container);
	renderHeaderInfo(container, startupParameters);
}

function renderHeader(container) {
	const element = `
    <a href="./"><span class="logo">Viite</span></a>
    <span class="headerTooltip" id="headerTooltip"></span>
    <span class="notification" id="notification"></span>
    <a href="manual/index.html" target="_blank" class="header-link">K&auml;ytt&ouml;ohje</a>
  `;

	container.empty();
	container.append(element);
}

function renderHeaderInfo(container, startupParameters) {
	const toolTip = `<i class="fas fa-info-circle" title="Versio: ${startupParameters.deploy_date}"></i>\n`;
	const headerTooltip = container.find('#headerTooltip');
	headerTooltip.empty();
	headerTooltip.append(toolTip);

	getRoadLinkDate(function (versionData) {
		const notification = container.find('#notification');
		notification.append(Environment.localizedName());
		notification.append(' Tielinkkiaineisto: ' + versionData.result);
	});
}