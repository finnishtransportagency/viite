VIITE
=====

Setting up the local dev environment
====================================

Source Code
-----------
Clone `viite`-repository from Github

```
git clone https://github.com/finnishtransportagency/viite.git
```

Frontend
---------
Install [yarn](https://yarnpkg.com/lang/en/)
[Install node.js](http://howtonode.org/how-to-install-nodejs) (you will get also [npm](https://npmjs.org/))
```
npm install -g yarn
```
Fetch and install the dependencies needed by the UI
```
npm install && yarn install
```
Install [grunt](http://gruntjs.com/getting-started)
```
npm install -g grunt-cli
```

Database
--------
Install PostGIS by [downloading and installing it from here](https://postgis.net/install/), or
by using Docker Compose:
```
cd aws/local-dev/postgis
docker-compose up
```
or by running the `aws/local-dev/postgis/start-postgis.sh` script.

PostGIS server can be stopped with the `aws/local-dev/postgis/stop-postgis.sh` script.

Docker Compose installs and starts the PostGIS database server.

[Read more about Viite PostGIS here](aws/local-dev/postgis/README.md)

Required Integrations
---------------------
Viite needs to get the links, the background maps and the coordinates for addresses
from external systems. For these connections to work, open Väylä VPN and
open SSH-tunnel with the needed port forwardings to a Väylä server.
- 9180: Viite running in devtest environment (TODO: When we are fully in AWS, this needs to be changed.)
- 8997: OAG

Idea Run Configurations
-----------------------
If you are developing with the IntelliJ Idea, you can import the run configurations
by copying the xml-files from the `aws/local-dev/idea-run-configurations` folder to the
`.idea/runConfigurations` folder (under the project folder). Restart Idea to see the new run configurations.

- Flyway_init.xml
  - Initialize the database for Flyway by creating the `schema_version` table
- Fixture_reset_test.xml
  - Empty the database, run the Flyway migrations, populate the database with the test data (required by the unit tests) 
- Flyway_migrate.xml
  - Run the Flyway migration scripts
- Grunt_Server.xml
  - Start the frontend server
- Grunt_Test.xml
  - Run the frontend tests
- Server.xml
  - Run the backend server
- Test.xml
  - Run the backend unit tests (needs the fixture reset test data)
  
Building and Running the Backend
---------------------------------
Running the unit tests from Idea:
- Run the "Test" sbt Task

Running the unit tests from the command line:
```
./sbt test
```

Running the backend from Idea:
- Run the "Server" sbt Task

Running the backend from the command line in the development mode:
```
./sbt '~;container:start; container:reload /'
```

When developing locally, backend reads the properties from the
`conf/env.properties` file. 

Environment parameter can be set from Intellij SBT Configuration Environment Variable 
or edit run configuration and set environment variable


Building and Running the Frontend
==================================
Building the frontend:
```
grunt
```

Running the tests from Idea:
- run the "Grunt Test" task

Running the tests from the command line:
```
grunt test
```

Running the frontend server from Idea:
- run the "Grunt Server" task

Running the frontend server from the command line:
- Set rasterServiceApiKey to environment variables

```
grunt server
```

Running the "Grunt Server" task does the build, runs the tests and runs the frontend in the watch-mode. 

Frontend server sends the requests to the backend server which needs to be running for the application to work.

UI will be available in this address: <http://localhost:9003/>.

Initializing the database
=========================
Empty database must be initialized for the Flyway by running the task "Flyway init"
or by calling the Viite AdminAPI: <http://localhost:8080/api/admin/flyway_init>.

All the tables can be created and populated with the test data by running the task "Fixture reset test".
TODO: nodes and junctions test data must be created too.

Flyway migrations can be run by running the task "Flyway migrate"
or by calling the Viite AdminAPI: <http://localhost:8080/api/admin/flyway_migrate>.
("Fixture reset test" does this already.)

[Käyttöönotto ja version päivitys](Deployment.md)
=================================================
