// Contains layer-specific legend HTML snippets used by SearchPanel.
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

const { RoadAddressChangeType, LifeCycleStatus, NodeType } = ViiteEnumerations;

// Constants -----------------------------------------------------------------

const ROAD_CLASSES = [
  { id: 1,  label: 'Valtatie (1-39)' },
  { id: 2,  label: 'Kantatie (40-99)' },
  { id: 3,  label: 'Seututie (100-999)' },
  { id: 4,  label: 'Yhdystie (1000-9999)' },
  { id: 5,  label: 'Yhdystie (10000-19999)' },
  { id: 6,  label: 'Numeroitu katu (40000-49999)' },
  { id: 7,  label: 'Ramppi tai kiertoliittymä (20000 - 39999)' },
  { id: 8,  label: 'Kävelyn ja pyöräilyn väylä (70001 - 99999)' },
  { id: 9,  label: 'Yksityistie, talvitie tai polku (50000-62999)' },
  { id: 11, label: 'Osoitteeton (kunta tai yksityinen)' },
  { id: 98, label: 'Hallinnollinen luokka kunta tai yksityinen' },
  { id: 99, label: 'Osoitteeton (valtio)' }
];

const ROAD_CLASS_COLORS = {
  1:  'red',
  2:  'orange',
  3:  'beige',
  4:  'dark-blue',
  5:  'cyan',
  6:  'purple',
  7:  'stripe-cyan',
  8:  'stripe-faded-pink',
  9:  'stripe-pink',
  11: 'gray',
  99: 'dark-gray'
};

const LIFECYCLE_STRIPE_CLASSES = {
  'Rakenteilla (kunta/yksityinen)': 'striped-gray',
  'Rakenteilla (valtio)':           'striped-orange'
};

const PROJECT_CHANGE_TYPES = [
  { type: RoadAddressChangeType.Unchanged, colorClass: 'dark-blue',  cssClass: 'operation-type-unchanged'  },
  { type: RoadAddressChangeType.New,       colorClass: 'pink',       cssClass: 'operation-type-new'        },
  { type: RoadAddressChangeType.Transfer,  colorClass: 'red',        cssClass: 'operation-type-transfer'   },
  { type: RoadAddressChangeType.Terminated,colorClass: 'black',      cssClass: 'operation-type-terminated' },
  { type: RoadAddressChangeType.Numbering, colorClass: 'brown',      cssClass: 'operation-type-renumbered' },
  { type: RoadAddressChangeType.NotHandled,colorClass: 'yellow',     cssClass: 'operation-type-unhandeled' }
];

const UNADDRESSED_ENTRIES = [
  { label: 'Osoitteeton (valtio)',            id: 99, colorClass: 'dark-gray' },
  { label: 'Osoitteeton (kunta/yksityinen)',  id: 11, colorClass: 'gray'      }
];

// HTML -----------------------------------------------------------------------

function linearSymbol(cssClass, colorClass = '') {
  return `<div class="symbol linear ${cssClass} ${colorClass}"></div>`;
}

function legendEntry(label, symbolHtml) {
  return `<div class="legend-entry"><div class="label">${label}</div>${symbolHtml}</div>`;
}

function inlineFlexLegendEntry(innerHtml) {
  return `<div class="legend-entry legend-entry-inline-flex">${innerHtml}</div>`;
}

function svgObjectEntry(src, label) {
  return inlineFlexLegendEntry(
    `<object type="image/svg+xml" data="${src}" class="legend-entry-icon-object"></object>
     <div class="label">${label}</div>`
  );
}

// ─── Static Entries ───────────────────────────────────────────────────────────

const STATIC_ENTRIES = {
  calibrationPoint: legendEntry('Kalibrointipiste', '<div class="calibration-point-image"></div>'),
  roadPartStart:    legendEntry('Tieosan alku',     '<div class="calibration-point-image"></div>'),
  junction:         svgObjectEntry('images/junction.svg',          'Liittymä'),
  junctionTemplate: svgObjectEntry('images/junction-template.svg', 'Liittymäaihio'),
  nodeTemplate:     svgObjectEntry('images/node-point-template.svg','Solmukohta-aihio')
};

// Segment Builders -----------------------------------------------------------------

function buildMultiColoredSegments() {
  const middle = [1, 2, 3, 4, 5, 6, 2, 1]
    .map(i => `<div class="middle symbol linear rainbow-asset-${i}"></div>`)
    .join('');
  return `<div class="rainbow-container">
    <div class="edge-left symbol linear linear-asset-1"></div>
    ${middle}
    <div class="edge-right symbol linear linear-asset-1"></div>
  </div>`;
}

// Legend Section Builders -----------------------------------------------------------------

function buildRoadClassEntries() {
  return ROAD_CLASSES.map(({ id, label }) => {
    const symbol = id === 98
      ? buildMultiColoredSegments()
      : linearSymbol(`linear-asset-${id}`, ROAD_CLASS_COLORS[id]);
    return legendEntry(label, symbol);
  }).join('');
}

function buildLifecycleStatusEntries() {
  return Object.values(LifeCycleStatus)
    .filter(Boolean)
    .map(({ value, description }) => {
      const stripeClass = Object.entries(LIFECYCLE_STRIPE_CLASSES)
        .find(([key]) => description.includes(key))?.[1] ?? '';
      return legendEntry(description, linearSymbol(`construction-type-${value}`, stripeClass));
    })
    .join('');
}

function buildProjectChangeTypeEntries() {
  return PROJECT_CHANGE_TYPES
    .map(({ type, colorClass, cssClass }) =>
      legendEntry(type.displayText, linearSymbol(cssClass, colorClass))
    )
    .join('');
}

function buildUnaddressedEntries() {
  return UNADDRESSED_ENTRIES
    .map(({ label, id, colorClass }) =>
      legendEntry(label, linearSymbol(`linear-asset-${id}`, colorClass))
    )
    .join('');
}

function buildNodeEntries() {
  return Object.values(NodeType)
    .filter(nodeType => nodeType && nodeType !== NodeType.UnknownNodeType)
    .map(({ value, description }) =>
      inlineFlexLegendEntry(
        `<img src="images/node-sprite.svg#${value}" class="legend-entry-icon-image"/>
         <div class="label">${value} ${description}</div>`
      )
    )
    .join('');
}

// Layer Legend Assemblers -----------------------------------------------------------------

function getRoadAddressProjectLegend() {
  return [
    buildProjectChangeTypeEntries(),
    buildLifecycleStatusEntries(),
    buildUnaddressedEntries(),
    STATIC_ENTRIES.calibrationPoint
  ].join('');
}

function getNodeLegend() {
  return [
    buildNodeEntries(),
    STATIC_ENTRIES.junction,
    STATIC_ENTRIES.junctionTemplate,
    STATIC_ENTRIES.nodeTemplate
  ].join('');
}

function getDefaultLegend() {
  return [
    buildRoadClassEntries(),
    buildLifecycleStatusEntries(),
    STATIC_ENTRIES.roadPartStart
  ].join('');
}

// Public API -----------------------------------------------------------------

const LAYER_LEGEND_MAP = {
  roadAddressProject: getRoadAddressProjectLegend,
  node: getNodeLegend
};

export function getLegendDisplayHtml(selectedLayer) {
  return (LAYER_LEGEND_MAP[selectedLayer] ?? getDefaultLegend)();
}