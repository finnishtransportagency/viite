module.exports = function (grunt) {
  const serveStatic = require('serve-static');
  const serveIndex = require('serve-index');
  const path = require('path');
  const { createProxyMiddleware } = require('http-proxy-middleware');

  const apiProxy = createProxyMiddleware({
    target: 'http://127.0.0.1:8080',
    changeOrigin: false,
    pathFilter: '/api',
    secure: false,
    xfwd: false
  });

  const rasteriProxy = createProxyMiddleware({
    target: 'http://localhost:8080',
    changeOrigin: true,
    pathFilter: '/rasteripalvelu',
    secure: false,
    xfwd: false
  });

  const maastokarttaProxy = createProxyMiddleware({
    target: 'https://api.vaylapilvi.fi:443',
    changeOrigin: false,
    pathFilter: '/wmts/maasto',
    secure: true,
    xfwd: true,
    headers: {
      "X-API-Key": process.env.rasterServiceApiKey,
      host: 'api.vaylapilvi.fi'
    },
    pathRewrite: {
      '^/wmts/maasto': '/rasteripalvelu-mml/wmts/maasto'
    }
  });

  const kiinteistoProxy = createProxyMiddleware({
    target: 'https://api.vaylapilvi.fi:443',
    changeOrigin: false,
    pathFilter: '/wmts/kiinteisto',
    secure: true,
    xfwd: true,
    headers: {
      "X-API-Key": process.env.rasterServiceApiKey,
      host: 'api.vaylapilvi.fi'
    },
    pathRewrite: {
      '^/wmts/kiinteisto': '/rasteripalvelu-mml/wmts/kiinteisto'
    }
  });

  const testComponentProxy = createProxyMiddleware({
    target: 'http://localhost:9003',
    changeOrigin: true,
    pathFilter: '/test/components',
    secure: false,
    xfwd: true,
    pathRewrite: {
      '^/test/components': '/components'
    }
  });

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
          './dist/index.html': './viite-UI/tmpl/index.html'
        }
      },
      production: {
        files: {
          './dist/index.html': './viite-UI/tmpl/index.html'
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
    cacheBust: {
      viiteCacheBuster: {
        options: {
          baseDir: './dist/',
          assets: [
            'node_modules/pikaday/css/pikaday.css',
            'node_modules/jquery/dist/jquery.min.js',
            'node_modules/jquery-migrate/dist/jquery-migrate.min.js',
            'node_modules/moment/min/moment.min.js',
            'node_modules/lodash/lodash.js',
            'node_modules/backbone/backbone.js',
            'node_modules/pikaday/pikaday.js',
            'node_modules/proj4/dist/proj4.js',
            'node_modules/interactjs/dist/interact.min.js',
            'css/style.css',
            'css/viite.css',
            'js/viite.min.js'
          ]
        },
        cwd: 'dist/',
        src: ['index.html'],
        algorithm: 'md5'
      }
    },
    copy: {
      modulesTask: {
        files: [{
          cwd: './node_modules/',    // set working folder / root to copy
          src: '**/*',               // copy all files and subfolders
          dest: 'dist/node_modules', // destination folder
          expand: true               // required when using cwd
        }]
      },
      stylesTask: {
        files: [{
          cwd: './viite-UI/components/theme/default/', // set working folder / root to copy
          src: 'style.css',
          dest: 'dist/css',                            // destination folder
          expand: true                                 // required when using cwd
        }]
      }
    },
    clean: ['dist'],
    connect: {
      viite: {
        options: {
          port: 9003,
          base: ['dist', '.', 'viite-UI'],
          middleware: function (connect, opts) {
            const _staticPath = path.resolve(opts.base[2]);

            const middlewares = [
              // Serve static files.
              serveStatic(opts.base[0]),
              serveStatic(opts.base[1]),
              serveStatic(opts.base[2]),
              // Make empty directories browsable.
              serveIndex(_staticPath)
            ];
            middlewares.unshift(apiProxy);
            middlewares.unshift(rasteriProxy);
            middlewares.unshift(maastokarttaProxy);
            middlewares.unshift(kiinteistoProxy);
            middlewares.unshift(testComponentProxy);
            return middlewares;
          }
        }
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
        tasks: ['eslint', 'less:viitedev', 'mochaTest:test'],
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
  grunt.loadNpmTasks('grunt-contrib-copy');
  grunt.loadNpmTasks('grunt-cache-bust');
  grunt.loadNpmTasks('grunt-env');
  grunt.loadNpmTasks('grunt-preprocess');

  var target = grunt.option('target') || 'production';

  grunt.registerTask('server', ['env:development', 'preprocess:development', 'connect:viite', 'less:viitedev', 'watch:viite']);

  grunt.registerTask('test', ['eslint', 'env:development', 'preprocess:development', 'connect:viite', 'mochaTest:test']);

  grunt.registerTask('default', ['eslint', 'env:production', 'preprocess:production', 'connect:viite', 'mochaTest:test', 'clean', 'less:viiteprod', 'concat', 'terser', 'cacheBust:viiteCacheBuster']);

  grunt.registerTask('deploy', ['clean', 'env:' + target, 'preprocess:production', 'less:viiteprod', 'concat', 'terser', 'copy', 'cacheBust:viiteCacheBuster', 'save_deploy_info']);

  grunt.registerTask('unit-test', ['eslint', 'env:development', 'preprocess:development', 'connect:viite', 'mochaTest:test']);

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
