// Contains layer-specific legend HTML snippets used by SearchPanel.
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;

const calibrationPointPicture = `
  <div class="legend-entry">
    <div class="label">Kalibrointipiste</div>
    <div class="calibration-point-image"></div>
  </div>`;

const roadPartStartPointPicture = `
  <div class="legend-entry">
    <div class="label">Tieosan alku</div>
    <div class="calibration-point-image"></div>
  </div>`;

const junctionPicture = `
  <div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">
    <object type="image/svg+xml" data="images/junction.svg" style="margin-right: 5px; margin-top: 5px"></object>
    <div class="label">Liittymä</div>
  </div>`;

const junctionTemplatePicture = `
  <div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">
    <object type="image/svg+xml" data="images/junction-template.svg" style="margin-right: 5px; margin-top: 5px"></object>
    <div class="label">Liittymäaihio</div>
  </div>`;

const nodeTemplatePicture = `
  <div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">
    <object type="image/svg+xml" data="images/node-point-template.svg" style="margin-right: 5px; margin-top: 5px"></object>
    <div class="label">Solmukohta-aihio</div>
  </div>`;

const roadClasses = [
  [1, 'Valtatie (1-39)'],
  [2, 'Kantatie (40-99)'],
  [3, 'Seututie (100-999)'],
  [4, 'Yhdystie (1000-9999)'],
  [5, 'Yhdystie (10000-19999)'],
  [6, 'Numeroitu katu (40000-49999)'],
  [7, 'Ramppi tai kiertoliittymä (20000 - 39999)'],
  [8, 'Kävelyn ja pyöräilyn väylä (70001 - 99999)'],
  [9, 'Yksityistie, talvitie tai polku (50000-62999)'],
  [11, 'Osoitteeton (kunta tai yksityinen)'],
  [98, 'Hallinnollinen luokka kunta tai yksityinen'],
  [99, 'Osoitteeton (valtio)']
];

function buildMultiColoredSegments() {
  let segments = '<div class="rainbow-container"><div class="edge-left symbol linear linear-asset-1"></div>';
  for (let i = 1; i <= 6; i++) {
    segments += `<div class="middle symbol linear rainbow-asset-${i}"></div>`;
  }
  return `${segments}<div class="middle symbol linear rainbow-asset-2"></div><div class="middle symbol linear rainbow-asset-1 "></div> <div class="edge-right symbol linear linear-asset-1"></div></div>`;
}

function createLifecycleStatusLegendEntries() {
  let html = '';

  Object.keys(ViiteEnumerations.LifeCycleStatus).forEach(function (status) {
    const statusInfo = ViiteEnumerations.LifeCycleStatus[status];
    if (!statusInfo) return;

    const statusValue = statusInfo.value;
    const statusDescription = statusInfo.description;
    let additionalClass = '';

    if (statusDescription.includes('Rakenteilla (kunta/yksityinen)')) {
      additionalClass = 'striped-gray';
    } else if (statusDescription.includes('Rakenteilla (valtio)')) {
      additionalClass = 'striped-orange';
    }

    html += `<div class="legend-entry">
      <div class="label">${statusDescription}</div>
      <div class="symbol linear construction-type-${statusValue} ${additionalClass}"></div>
    </div>`;
  });

  return html;
}

function getRoadClassLegendEntries() {
  const colorMap = {
    1: 'red',
    2: 'orange',
    3: 'beige',
    4: 'dark-blue',
    5: 'cyan',
    6: 'purple',
    7: 'stripe-cyan',
    8: 'stripe-faded-pink',
    9: 'stripe-pink',
    11: 'gray',
    99: 'dark-gray'
  };

  return roadClasses.map(function (roadClass) {
    const roadClassId = roadClass[0];
    const roadClassLabel = roadClass[1];

    let legendEntry = `<div class="legend-entry"><div class="label">${roadClassLabel}</div>`;
    if (roadClassId === 98) {
      legendEntry += buildMultiColoredSegments();
    } else {
      const colorClass = colorMap[roadClassId] || '';
      legendEntry += `<div class="symbol linear linear-asset-${roadClassId} ${colorClass}"></div>`;
    }

    return `${legendEntry}</div>`;
  }).join('');
}

function createNodeLegendEntries() {
  let html = '';

  Object.keys(ViiteEnumerations.NodeType).forEach(function (node) {
    const nodeType = ViiteEnumerations.NodeType[node];
    if (!nodeType || nodeType === ViiteEnumerations.NodeType.UnknownNodeType) return;

    html += `<div class="legend-entry" style="min-width: 100%;display: inline-flex;justify-content: left;align-items: center;">
      <img src="images/node-sprite.svg#${nodeType.value}" style="margin-right: 5px"/>
      <div class="label">${nodeType.value} ${nodeType.description}</div>
    </div>`;
  });

  return html;
}

function getRoadProjectLegendEntries() {
  return (`
    <div class="legend-entry">
      <div class="label">${RoadAddressChangeType.Unchanged.displayText}</div>
      <div class="symbol linear operation-type-unchanged dark-blue"></div>
    </div>
    <div class="legend-entry">
      <div class="label">${RoadAddressChangeType.New.displayText}</div>
      <div class="symbol linear operation-type-new pink"></div>
    </div>
    <div class="legend-entry">
      <div class="label">${RoadAddressChangeType.Transfer.displayText}</div>
      <div class="symbol linear operation-type-transfer red"></div>
    </div>
    <div class="legend-entry">
      <div class="label">${RoadAddressChangeType.Terminated.displayText}</div>
      <div class="symbol linear operation-type-terminated black"></div>
    </div>
    <div class="legend-entry">
      <div class="label">${RoadAddressChangeType.Numbering.displayText}</div>
      <div class="symbol linear operation-type-renumbered brown"></div>
    </div>
    <div class="legend-entry">
      <div class="label">${RoadAddressChangeType.NotHandled.displayText}</div>
      <div class="symbol linear operation-type-unhandeled yellow"></div>
    </div>
    ${createLifecycleStatusLegendEntries()}
    <div class="legend-entry">
      <div class="label"> Osoitteeton (valtio)</div>
      <div class="symbol linear linear-asset-99 dark-gray"></div>
    </div>
    <div class="legend-entry">
      <div class="label"> Osoitteeton (kunta/yksityinen)</div>
      <div class="symbol linear linear-asset-11 gray"></div>
    </div>
  `);
}

export function getLegendDisplayHtml(selectedLayer) {
  if (selectedLayer === 'roadAddressProject') {
    return `${getRoadProjectLegendEntries()}${calibrationPointPicture}`;
  }

  if (selectedLayer === 'node') {
    return `${createNodeLegendEntries()}${junctionPicture}${junctionTemplatePicture}${nodeTemplatePicture}`;
  }

  return `${getRoadClassLegendEntries()}${createLifecycleStatusLegendEntries()}${roadPartStartPointPicture}`;
}
