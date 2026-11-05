import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Vite serves and builds public files directly, so stage ignored assets and vendor licenses here.
const repositoryRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const uiRoot = path.join(repositoryRoot, 'viite-UI');
const publicRoot = path.join(uiRoot, 'public');

const directories = [
  ['images', path.join(uiRoot, 'images')],
  ['manual', path.join(uiRoot, 'manual')],
  ['swagger', path.join(uiRoot, 'swagger')]
];

const files = [
  ['favicon.ico', path.join(uiRoot, 'favicon.ico')],
  ['node_modules/ol/dist/ol.js', path.join(repositoryRoot, 'node_modules/ol/dist/ol.js')],
  ['node_modules/jquery/dist/jquery.min.js', path.join(repositoryRoot, 'node_modules/jquery/dist/jquery.min.js')],
  ['node_modules/moment/min/moment.min.js', path.join(repositoryRoot, 'node_modules/moment/min/moment.min.js')],
  ['node_modules/lodash/lodash.js', path.join(repositoryRoot, 'node_modules/lodash/lodash.js')],
  ['node_modules/backbone/backbone.js', path.join(repositoryRoot, 'node_modules/backbone/backbone.js')],
  ['node_modules/pikaday/pikaday.js', path.join(repositoryRoot, 'node_modules/pikaday/pikaday.js')],
  ['node_modules/proj4/dist/proj4.js', path.join(repositoryRoot, 'node_modules/proj4/dist/proj4.js')],
  ['node_modules/interactjs/dist/interact.min.js', path.join(repositoryRoot, 'node_modules/interactjs/dist/interact.min.js')]
];

const licenses = [
  ['ol', 'LICENSE.md'],
  ['jquery', 'LICENSE.txt'],
  ['moment', 'LICENSE'],
  ['lodash', 'LICENSE'],
  ['backbone', 'LICENSE'],
  ['pikaday', 'LICENSE'],
  ['proj4', 'LICENSE.md'],
  ['interactjs', 'LICENSE']
];

for (const [, source] of directories) {
  if (!existsSync(source)) {
    throw new Error(`Required public asset directory is missing: ${source}`);
  }
}

for (const [, source] of files) {
  if (!existsSync(source)) {
    throw new Error(`Required public asset is missing: ${source}`);
  }
}

for (const [packageName, licenseFile] of licenses) {
  const source = path.join(repositoryRoot, 'node_modules', packageName, licenseFile);
  if (!existsSync(source)) {
    throw new Error(`Required vendor license is missing: ${source}`);
  }
}

rmSync(publicRoot, { recursive: true, force: true });
mkdirSync(publicRoot, { recursive: true });

for (const [destination, source] of directories) {
  cpSync(source, path.join(publicRoot, destination), { recursive: true });
}

for (const [destination, source] of files) {
  const target = path.join(publicRoot, destination);
  mkdirSync(path.dirname(target), { recursive: true });
  cpSync(source, target);
}

for (const [packageName, licenseFile] of licenses) {
  const source = path.join(repositoryRoot, 'node_modules', packageName, licenseFile);
  const target = path.join(publicRoot, 'licenses', 'vendor', packageName, licenseFile);
  mkdirSync(path.dirname(target), { recursive: true });
  cpSync(source, target);
}

console.log(`Prepared static assets in ${publicRoot}`);
