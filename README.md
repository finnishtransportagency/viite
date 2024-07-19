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

CodeArtifact Setup (Optional for Local Development)
-----------------------------
This project uses AWS CodeArtifact to manage npm and Maven packages. Using CodeArtifact is optional for local development but required for CI/CD pipelines.

To set up CodeArtifact for local development or to switch between CodeArtifact and public repositories, please refer to our [CodeArtifact Setup Guide](aws/cloud-formation/codeArtifact/README.md).

Note: If you choose not to use CodeArtifact, follow the instructions below.

Frontend
---------
Install [node.js](https://nodejs.org/en/download/releases) (Version 14.21.3 works well. You will also get [npm](https://npmjs.org/))   
Fetch and install the dependencies needed by the UI
```
npm install
```
Install [grunt-cli](http://gruntjs.com/getting-started) if you want to run grunt from the command line
```
npm install -g grunt-cli
```

Database
--------
Install PostGIS by [downloading and installing it from here](https://postgis.net/install/), or
by using Docker Compose:
```
cd local-dev/postgis
docker-compose up
```
or by running the `local-dev/postgis/start-postgis.sh` script.

PostGIS server can be stopped with the `local-dev/postgis/stop-postgis.sh` script.

Docker Compose installs and starts the PostGIS database server.

[Read more about Viite PostGIS here](local-dev/postgis/README.md)

Idea Run Configurations
-----------------------
If you are developing with the IntelliJ Idea, you can import the run configurations
by copying the xml-files from the `local-dev/idea-run-configurations` folder to the
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

Add the following environment variables to the run configurations. 
The api keys can be found from the Viite project's Confluence page. (You need a permission to view the password page in Confluence) 
- Server & Test 
  - kgvApiKey
  - rasterServiceApiKey
  - vkmApiKey

- Grunt Server
  - rasterServiceApiKey
  
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
