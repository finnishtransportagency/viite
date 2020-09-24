module.exports = function (grunt) {
  var serveStatic = require('serve-static');
  var serveIndex = require('serve-index');
  var path = require('path');
  grunt.initConfig({
    pkg: grunt.file.readJSON('package.json'),
    properties: {
      app: 'conf/dev/keys.properties'
    },
    env: {
      options: {},
      development: {
        NODE_ENV: 'DEVELOPMENT'
      },
      staging: {
        NODE_ENV: 'STAGING'
      },
      testing: {
        NODE_ENV: 'PRODUCTION'
      },
      production: {
        NODE_ENV: 'PRODUCTION'
      }
    },
    preprocess: {
      development: {
        files: {
          './viite-UI/index.html': './viite-UI/tmpl/index.html'
        }
      },
      production: {
        files: {
          './viite-UI/index.html': './viite-UI/tmpl/index.html'
        }
      }
    },
    concat: {
      options: {
        separator: ';'
      },
      dist: {
        files: {
          'dist/js/<%= pkg.name %>.js': ['viite-UI/src/**/*.js', '!**/ol-custom.js']
        }
      }
    },
    terser: {
      options: {
        // Task-specific options go here.
      },
      main: {
        files: {
          'dist/js/<%= pkg.name %>.min.js': ['dist/js/<%= pkg.name %>.js']
          // Target-specific file lists and/or options go here.
        }
      }
    },
    cachebreaker: {
      indexfile: {
        options: {
          match: [
            {
              // Pattern    // File to hash
              'style.css': 'viite-UI/components/theme/default/style.css',
              'pikaday.css': 'node_modules/pikaday/css/pikaday.css',
              'viite.css': 'dist/css/viite.css',
              'jquery.min.js': 'node_modules/jquery/dist/jquery.min.js',
              'jquery-migrate.min.js': 'node_modules/jquery-migrate/dist/jquery-migrate.min.js',
              'moment.min.js': 'node_modules/moment/min/moment.min.js',
              'lodash.js': 'node_modules/lodash/lodash.js',
              'backbone.js': 'node_modules/backbone/backbone.js',
              'pikaday.js': 'node_modules/pikaday/pikaday.js',
              'proj4.js': 'node_modules/proj4/dist/proj4.js',
              'interact.min.js': 'node_modules/interactjs/dist/interact.min.js',
              'ol.js': 'viite-UI/components/ol.js',
              'viite.min.js': 'dist/js/viite.js'
            }
          ],
          replacement: 'md5'
        },
        files: {
          // File where md5 hashes are stored
          src: ['viite-UI/index.html']
        }
      }
    },
    clean: ['dist'],
    connect: {
      viite: {
        options: {
          port: 9003,
          base: ['dist', '.', 'viite-UI'],
          middleware: function (connect, opts) {
            var _staticPath = path.resolve(opts.base[2]);
            var config = [
              // Serve static files.
              serveStatic(opts.base[0]),
              serveStatic(opts.base[1]),
              serveStatic(opts.base[2]),
              // Make empty directories browsable.
              serveIndex(_staticPath)
            ];
            var proxy = require('grunt-connect-proxy/lib/utils').proxyRequest;
            config.unshift(proxy);
            return config;
          }
        },
        proxies: [
          {
            context: '/api',
            host: '127.0.0.1',
            port: '8080',
            https: false,
            changeOrigin: false,
            xforward: false
          },
          {
            context: '/viite/api-docs',
            host: '127.0.0.1',
            port: '8080',
            https: false,
            changeOrigin: true,
            xforward: false,
            rewrite: {
              '^/viite/api-docs': '/api-docs'
            }
          },
          {
            context: '/arcgis',
            host: 'aineistot.esri.fi',
            https: true,
            port: '443',
            changeOrigin: true,
            xforward: false,
            headers: {referer: 'https://aineistot.esri.fi/arcgis/rest/services/Taustakartat/Harmaasavy/MapServer?f=jsapi'}
          },
          {
            context: '/rasteripalvelu',
            host: 'localhost',
            port: '9180',
            https: false,
            secure: false,
            changeOrigin: true,
            xforward: false,
            rewrite: {
              '^/rasteripalvelu': '/viite/rasteripalvelu'
            }
          },
          {
            context: '/wmts',
            host: 'localhost',
            port: '9180',
            https: false,
            secure: false,
            xforward: false,
            rewrite: {
              '^/wmts': '/viite/wmts'
            }
          },
          {
            context: '/maasto',
            host: 'karttamoottori.maanmittauslaitos.fi',
            https: false,
            changeOrigin: true,
            xforward: false,
            headers: {referer: 'http://www.paikkatietoikkuna.fi/web/fi/kartta'}
          },
          {
            context: '/test/components',
            host: 'localhost',
            port: '9003',
            https: false,
            changeOrigin: true,
            xforward: true,
            rewrite: {
              '^/test/components': '/components'
            }
          }
        ]
      }
    },
    less: {
      viitedev: {
        files: {
          "dist/css/viite.css": "viite-UI/src/less/main.less"
        }
      },
      viiteprod: {
        options: {
          cleancss: true
        },
        files: {
          "dist/css/viite.css": "viite-UI/src/less/main.less"
        }
      }
    },
    eslint: {
      src: ['gruntfile.js', 'viite-UI/test/**/*.js', 'viite-UI/src/map/*.js', 'viite-UI/src/modalconfirm/*.js', 'viite-UI/src/model/*.js', 'viite-UI/src/utils/*.js', 'viite-UI/src/view/*.js', 'viite-UI/test_data/*.js', 'viite-UI/src/']
    },
    mocha: {
      viite_unit: {
        options: {
          // mocha options
          mocha: {
            ignoreLeaks: false,
            "debug-brk": (grunt.option('debug-brk')) ? "" : 0
          },

          // URLs passed through as options
          urls: ['http://127.0.0.1:9003/test/test-runner.html'],

          // Indicates whether 'mocha.run()' should be executed in
          // 'bridge.js'
          run: false,
          log: true,
          reporter: 'Spec'
        }
      },
      options: {
        growlOnSuccess: false
      }
    },
    watch: {
      viite: {
        files: ['<%= eslint.src %>', 'viite-UI/src/**/*.less', 'viite-UI/**/*.html'],
        tasks: ['properties', 'eslint', 'env:development', 'preprocess:development', 'less:viitedev', 'mocha:viite_unit', 'configureProxies:viite'],
        options: {
          livereload: true
        }
      }
    },
    exec: {}
  });

  grunt.loadNpmTasks("grunt-terser");
  grunt.loadNpmTasks("gruntify-eslint");
  grunt.loadNpmTasks('grunt-mocha');
  grunt.loadNpmTasks('grunt-contrib-watch');
  grunt.loadNpmTasks('grunt-contrib-concat');
  grunt.loadNpmTasks('grunt-contrib-less');
  grunt.loadNpmTasks('grunt-contrib-connect');
  grunt.loadNpmTasks('grunt-contrib-clean');
  grunt.loadNpmTasks('grunt-connect-proxy');
  grunt.loadNpmTasks('grunt-execute');
  grunt.loadNpmTasks('grunt-cache-breaker');
  grunt.loadNpmTasks('grunt-env');
  grunt.loadNpmTasks('grunt-preprocess');
  grunt.loadNpmTasks('grunt-exec');
  grunt.loadNpmTasks('grunt-properties-reader');

  var target = grunt.option('target') || 'production';

  grunt.registerTask('server', ['properties', 'env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'less:viitedev', 'watch:viite']);

  grunt.registerTask('test', ['properties', 'eslint', 'env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'mocha:viite_unit']);

  grunt.registerTask('default', ['properties', 'eslint', 'env:production', 'exec:prepare_openlayers', 'exec:viite_build_openlayers', 'configureProxies:viite', 'preprocess:production', 'connect:viite', 'mocha:viite_unit', 'clean', 'less:viiteprod', 'concat', 'terser', 'cachebreaker']);

  grunt.registerTask('deploy', ['clean', 'env:' + target, 'preprocess:production', 'less:viiteprod', 'concat', 'terser', 'cachebreaker', 'save_deploy_info']);

  grunt.registerTask('unit-test', ['properties', 'eslint', 'env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'mocha:viite_unit']);

  grunt.registerTask('save_deploy_info',
    function () {
      var options = this.options({
        file: 'revision.properties'
      });

      var data = ('digiroad2.latestDeploy=' + grunt.template.today('dd-mm-yyyy HH:MM:ss'));
      grunt.file.write(options.file, data);


    }
  );
};
