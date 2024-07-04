module.exports = function (grunt) {
  var serveStatic = require('serve-static');
  var serveIndex = require('serve-index');
  var path = require('path');
  grunt.initConfig({
    pkg: grunt.file.readJSON('package.json'),
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
            context: '/rasteripalvelu',
            host: 'localhost',
            port: '8080',
            https: false,
            secure: false,
            changeOrigin: true,
            xforward: false
          },
          {
            context:'/wmts/maasto',
            host: 'api.vaylapilvi.fi',
            port: '443',
            https: true,
            changeOrigin: false,
            xforward: true,
            headers: {
                "X-API-Key": process.env.rasterServiceApiKey,
                host: 'api.vaylapilvi.fi'
            },
            rewrite: {
                '/wmts/maasto':'/rasteripalvelu-mml/wmts/maasto'
            }
          },
          {
            context:'/wmts/kiinteisto',
            host: 'api.vaylapilvi.fi',
            port: '443',
            https: true,
            changeOrigin: false,
            xforward: true,
            headers: {
              "X-API-Key": process.env.rasterServiceApiKey,
              host: 'api.vaylapilvi.fi'
            },
            rewrite: {
              '/wmts/kiinteisto':'/rasteripalvelu-mml/wmts/kiinteisto'
            }
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
      src: [
        'gruntfile.js',
        'viite-UI/test/**/*.js',
        'viite-UI/test_data/*.js',
        'viite-UI/src/'
      ]
    },
    mochaTest: {
      test: {
        options: {
          reporter: 'spec',
          //captureFile: 'results.txt', // Optionally capture the reporter output to a file
          quiet: false, // Optionally suppress output to standard out (defaults to false)
          clearRequireCache: false, // Optionally clear the require cache before running tests (defaults to false)
          noFail: false // Optionally set to not fail on failed tests (will still fail on other errors)
        },
        src: ['viite-UI/test/unit-tests/*.js']
      }
    },
    watch: {
      viite: {
        files: ['<%= eslint.src %>', 'viite-UI/src/**/*.less', 'viite-UI/**/*.html'],
        tasks: ['eslint', 'less:viitedev', 'mochaTest:test', 'configureProxies:viite'],
        options: {
          livereload: true
        }
      }
    }
  });

  grunt.loadNpmTasks("grunt-terser");
  grunt.loadNpmTasks("gruntify-eslint");
  grunt.loadNpmTasks('grunt-mocha-test');
  grunt.loadNpmTasks('grunt-contrib-watch');
  grunt.loadNpmTasks('grunt-contrib-concat');
  grunt.loadNpmTasks('grunt-contrib-less');
  grunt.loadNpmTasks('grunt-contrib-connect');
  grunt.loadNpmTasks('grunt-contrib-clean');
  grunt.loadNpmTasks('grunt-connect-proxy');
  grunt.loadNpmTasks('grunt-cache-breaker');
  grunt.loadNpmTasks('grunt-env');
  grunt.loadNpmTasks('grunt-preprocess');

  var target = grunt.option('target') || 'production';

  grunt.registerTask('server', ['env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'less:viitedev', 'watch:viite']);

  grunt.registerTask('test', ['eslint', 'env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'mochaTest:test']);

  grunt.registerTask('default', ['eslint', 'env:production', 'configureProxies:viite', 'preprocess:production', 'connect:viite', 'mochaTest:test', 'clean', 'less:viiteprod', 'concat', 'terser', 'cachebreaker']);

  grunt.registerTask('deploy', ['clean', 'env:' + target, 'preprocess:production', 'less:viiteprod', 'concat', 'terser', 'cachebreaker', 'save_deploy_info']);

  grunt.registerTask('unit-test', ['eslint', 'env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'mochaTest:test']);

  grunt.registerTask('save_deploy_info',
    function () {
      var options = this.options({
        file: 'viite-backend/conf/revision.properties'
      });

      var data = ('latestDeploy=' + grunt.template.today('dd-mm-yyyy HH:MM:ss'));
      grunt.file.write(options.file, data);


    }
  );
};
