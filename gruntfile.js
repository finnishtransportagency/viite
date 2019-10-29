module.exports = function(grunt) {
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
    uglify: {
      options: {
        banner: '/*! <%= pkg.name %> <%= grunt.template.today("dd-mm-yyyy") %> */\n'
      },
      dist: {
        files: {
          'dist/js/<%= pkg.name %>.min.js': ['dist/js/<%= pkg.name %>.js']
        }
      }
    },
    cachebreaker: {
      options: {
        match: ['viite.css'],
        replacement: 'md5',
        src: {
          path: 'dist/css/viite.css'
        }
      },
      files: {
        src: ['viite-UI/index.html']
      }
    },
    clean: ['dist'],
    connect: {
      viite: {
        options: {
          port: 9003,
          base: ['dist', '.', 'viite-UI'],
          middleware: function(connect, opts) {
            var config = [
              // Serve static files.
              connect.static(opts.base[0]),
              connect.static(opts.base[1]),
              connect.static(opts.base[2]),
              // Make empty directories browsable.
              connect.directory(opts.base[2])
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
            host: 'oag.vayla.fi',
            port: '80',
            https: false,
            changeOrigin: true,
            xforward: false
          },
          {
            context: '/wmts',
            host: 'oag.liikennevirasto.fi',
            port: '80',
            https: false,
            changeOrigin: true,
            xforward: false,
            rewrite: {
              '^/wmts': '/rasteripalvelu-mml/wmts'
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
            context: '/vkm',
            host: 'oag.liikennevirasto.fi',
            port: '80',
            https: true,
            changeOrigin: true,
            xforward: false
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
    jshint: {
      files: ['Gruntfile.js', 'viite-UI/test/**/*.js', 'viite-UI/src/**/*.js', 'viite-UI/test_data/*.js', 'viite-UI/src/' ],
      options: {
        reporterOutput: "",
        // options here to override JSHint defaults
        globals: {
          jQuery: true,
          console: true,
          module: true,
          document: true
        }
      }
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
      viite_integration: {
        options: {
          mocha: { ignoreLeaks: true },
          urls: ['http://127.0.0.1:9003/test/integration-tests.html'],
          run: false,
          log: true,
          logErrors: true,
          timeout: 100000,
          reporter: 'Spec'
        }
      },
      options: {
        growlOnSuccess: false
      }
    },
    watch: {
      viite: {
        files: ['<%= jshint.files %>', 'viite-UI/src/**/*.less', 'viite-UI/**/*.html'],
        tasks: ['properties', 'jshint', 'env:development', 'preprocess:development', 'less:viitedev', 'mocha:viite_unit', 'mocha:viite_integration', 'configureProxies:viite'],
        options: {
          livereload: true
        }
      }
    },
    exec: {
    }
  });

  grunt.loadNpmTasks('grunt-contrib-uglify');
  grunt.loadNpmTasks('grunt-contrib-jshint');
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

  grunt.registerTask('test', ['properties', 'jshint', 'env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'mocha:viite_unit', 'mocha:viite_integration']);

  grunt.registerTask('default', ['properties', 'jshint', 'env:production', 'exec:prepare_openlayers', 'exec:viite_build_openlayers', 'configureProxies:viite', 'preprocess:production', 'connect:viite', 'mocha:viite_unit', 'mocha:viite_integration', 'clean', 'less:viiteprod', 'concat', 'uglify', 'cachebreaker']);

  grunt.registerTask('deploy', ['clean', 'env:'+target, 'preprocess:production', 'less:viiteprod', 'concat', 'uglify', 'cachebreaker', 'save_deploy_info']);

  grunt.registerTask('unit-test', ['properties', 'jshint', 'env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'mocha:viite_unit']);

  grunt.registerTask('integration-test', ['jshint', 'env:development', 'configureProxies:viite', 'preprocess:development', 'connect:viite', 'mocha:viite_integration']);

  grunt.registerTask('save_deploy_info',
    function() {
      var options = this.options({
        file: 'revision.properties'
      });

      var data = ('digiroad2.latestDeploy=' + grunt.template.today('dd-mm-yyyy HH:MM:ss'));
      grunt.file.write(options.file, data);


    }
  );
};
