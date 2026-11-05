import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig, loadEnv } from 'vite';

const repositoryRoot = path.dirname(fileURLToPath(import.meta.url));
const uiRoot = path.join(repositoryRoot, 'viite-UI');
const sourceRoot = path.join(uiRoot, 'src');
const publicRoot = path.join(uiRoot, 'public');
const distRoot = path.join(repositoryRoot, 'dist');

const vendorPackages = [
  'ol',
  'jquery',
  'moment',
  'lodash',
  'backbone',
  'pikaday',
  'proj4',
  'interactjs'
];

const vendorVersions = Object.fromEntries(vendorPackages.map((packageName) => {
  const packagePath = path.join(repositoryRoot, 'node_modules', packageName, 'package.json');
  const packageJson = JSON.parse(readFileSync(packagePath, 'utf8'));
  return [packageName, packageJson.version];
}));

function proxyErrorHandler(route) {
  return (proxy) => {
    proxy.on('error', (error, _request, response) => {
      const code = error.code || error.name || 'unknown error';
      console.error(`[vite proxy] ${route} failed (${code})`);

      if (response && typeof response.writeHead === 'function' && !response.headersSent) {
        response.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
        response.end(`The ${route} upstream is unavailable. Check the backend and proxy configuration.`);
      }
    });
  };
}

function proxyOptions(route, options) {
  return {
    ...options,
    configure: proxyErrorHandler(route)
  };
}

function requiredCredentialMiddleware(credentials) {
  const protectedRoutes = [
    { prefix: '/wmts/maasto', variable: 'rasterServiceApiKey' },
    { prefix: '/wmts/kiinteisto', variable: 'rasterServiceApiKey' },
    { prefix: '/paikkatiedot/wms', variable: 'kgvApiKey' }
  ];

  return {
    name: 'viite-proxy-credentials',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const requestPath = (request.url || '').split('?')[0];
        const route = protectedRoutes.find(({ prefix }) =>
          requestPath === prefix || requestPath.startsWith(`${prefix}/`)
        );

        if (!route || credentials[route.variable]) {
          next();
          return;
        }

        console.error(`[vite proxy] Missing ${route.variable}; set it in the server environment to use ${route.prefix}.`);
        response.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' });
        response.end(`Missing server-side credential ${route.variable}. Set it in the environment and restart Vite.`);
      });
    }
  };
}

function fullReloadForApplicationJavaScript() {
  return {
    name: 'viite-full-reload-for-application-js',
    handleHotUpdate({ file, server }) {
      if (file.startsWith(`${sourceRoot}${path.sep}`) && file.endsWith('.js')) {
        server.ws.send({ type: 'full-reload' });
        return [];
      }
    }
  };
}

export default defineConfig(({ mode }) => {
  const credentials = loadEnv(mode, uiRoot, ['kgvApiKey', 'rasterServiceApiKey', 'vkmApiKey']);

  const apiProxy = proxyOptions('/api', {
    target: 'http://127.0.0.1:9080',
    changeOrigin: false,
    secure: false,
    xfwd: false
  });
  
  const rasterProxy = proxyOptions('/rasteripalvelu', {
    target: 'http://localhost:9080',
    changeOrigin: true,
    secure: false,
    xfwd: false
  });
  
  const maastoProxy = proxyOptions('/wmts/maasto', {
    target: 'https://api.vaylapilvi.fi:443',
    changeOrigin: false,
    secure: true,
    xfwd: true,
    headers: {
      'X-API-Key': credentials.rasterServiceApiKey || '',
      host: 'api.vaylapilvi.fi'
    },
    rewrite: (requestPath) => requestPath.replace(/^\/wmts\/maasto/, '/rasteripalvelu-mml/wmts/maasto')
  });
  
  const kiinteistoProxy = proxyOptions('/wmts/kiinteisto', {
    target: 'https://api.vaylapilvi.fi:443',
    changeOrigin: false,
    secure: true,
    xfwd: true,
    headers: {
      'X-API-Key': credentials.rasterServiceApiKey || '',
      host: 'api.vaylapilvi.fi'
    },
    rewrite: (requestPath) => requestPath.replace(/^\/wmts\/kiinteisto/, '/rasteripalvelu-mml/wmts/kiinteisto')
  });
  
  const paikkatiedotProxy = proxyOptions('/paikkatiedot/wms', {
    target: 'https://api.vaylapilvi.fi:443',
    changeOrigin: true,
    secure: true,
    xfwd: true,
    headers: {
      'X-API-Key': credentials.kgvApiKey || '',
      host: 'api.vaylapilvi.fi'
    }
  });
  
  return {
    root: uiRoot,
    base: '/',
    publicDir: publicRoot,
    resolve: {
      alias: [
        { find: '@router.js', replacement: path.join(sourceRoot, 'router.js') },
        { find: '@src', replacement: sourceRoot },
        { find: '@components', replacement: path.join(sourceRoot, 'components') },
        { find: '@model', replacement: path.join(sourceRoot, 'model') },
        { find: '@node-menu', replacement: path.join(sourceRoot, 'view', 'node-menu') },
        { find: '@utils', replacement: path.join(sourceRoot, 'utils') },
        { find: '@view', replacement: path.join(sourceRoot, 'view') },
        { find: '@', replacement: sourceRoot }
      ]
    },
    plugins: [
      requiredCredentialMiddleware(credentials),
      fullReloadForApplicationJavaScript(),
      {
        name: 'viite-vendor-cache-versions',
        transformIndexHtml(html) {
          return Object.entries(vendorVersions).reduce(
            (versionedHtml, [packageName, version]) =>
              versionedHtml.replaceAll(`?v=__${packageName}__`, `?v=${version}`),
            html
          );
        }
      }
    ],
    server: {
      host: '127.0.0.1',
      port: 9003,
      strictPort: true,
      proxy: {
        '^/api(?:/|$)': apiProxy,
        '^/rasteripalvelu(?:/|$)': rasterProxy,
        '^/wmts/maasto(?:/|$)': maastoProxy,
        '^/wmts/kiinteisto(?:/|$)': kiinteistoProxy,
        '^/paikkatiedot/wms(?:/|$)': paikkatiedotProxy
      }
    },
    preview: {
      host: '127.0.0.1',
      port: 4173,
      strictPort: true
    },
    build: {
      target: 'es2020',
      assetsDir: 'assets',
      outDir: distRoot,
      emptyOutDir: true,
      sourcemap: false,
      rollupOptions: {
        input: path.join(uiRoot, 'index.html')
      }
    }
  };
});
