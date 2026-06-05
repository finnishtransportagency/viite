import { Environment } from '@utils/EnvironmentUtils.js';

export function Header(container, backend, startupParameters) {
  renderHeader(container);
  renderHeaderInfo(container, backend, startupParameters);
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

function renderHeaderInfo(container, backend, startupParameters) {
  const toolTip = `<i class="fas fa-info-circle" title="Versio: ${startupParameters.deploy_date}"></i>\n`;
  const headerTooltip = container.find('#headerTooltip');
  headerTooltip.empty();
  headerTooltip.append(toolTip);

  backend.getRoadLinkDate(function (versionData) {
    const notification = container.find('#notification');
    notification.append(Environment.localizedName());
    notification.append(' Tielinkkiaineisto: ' + versionData.result);
  });
}