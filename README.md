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

To set up CodeArtifact for local development or to switch between CodeArtifact and public repositories, please refer to our [CodeArtifact Setup Guide](project/README.md).

Note: If you choose not to use CodeArtifact, follow the instructions below.

Frontend
---------
Install [Node.js](https://nodejs.org/en/download/releases) 20.19.0 or later
within the Node 20 line, or 22.12.0 or later. This includes [npm](https://npmjs.org/).
Fetch and install the dependencies needed by the UI
```
npm ci
```

Database
--------
Install PostGIS by [downloading and installing it from here](https://postgis.net/install/), or
by using Docker Compose:
```
cd local-dev/postgis
docker compose up
```
or by running the `local-dev/postgis/start-postgis.sh` script.

PostGIS server can be stopped with the `local-dev/postgis/stop-postgis.sh` script, 

or with: 
```
docker compose down
 ```

Docker Compose installs and starts the PostGIS database server.

[Read more about Viite PostGIS here](local-dev/postgis/README.md)

Idea Run Configurations
-----------------------
If you are developing with the IntelliJ Idea, you can import the run configurations
by copying the xml-files from the `local-dev/idea-run-configurations` folder to the
`.idea/runConfigurations` folder (under the project folder). Restart Idea to see the new run configurations.

- Flyway_init.xml
  - Initialize the database for Flyway by creating the `flyway_schema_history` table
- Fixture_reset_test.xml
  - Empty the database, run the Flyway migrations, populate the database with the test data (required by the unit tests) 
- Flyway_migrate.xml
  - Run the Flyway migration scripts
- Frontend_Dev.xml
  - Start the Vite frontend server
- Frontend_Test.xml
  - Run frontend unit tests
- Server.xml
  - Run the backend server
- Test.xml
  - Run the backend unit tests (needs the fixture reset test data)

Add the following environment variables to the run configurations. 
The api keys can be found from the Viite project's Confluence page. (You need a permission to view the password page) 
- Server & Test 
  - kgvApiKey
  - rasterServiceApiKey
  - vkmApiKey

- Frontend Dev
  - rasterServiceApiKey
  - kgvApiKey
  
Building and Running the Backend
---------------------------------
Running the unit tests from Idea:
- Run the "Test" sbt Task

Running the unit tests from the command line:
```
sbt test
```

Running the backend from Idea:
- Run the "Server" sbt Task

Running the backend from the command line in the development mode:
```
sbt run
```

When developing locally, backend reads the properties from the
`conf/env.properties` file. 

Environment parameter can be set from Intellij SBT Configuration Environment Variable 
or edit run configuration and set environment variable


Building and Running the Frontend
==================================
Build and validate the frontend:
```
npm run build
```

Running lint and unit tests from Idea:
- run the "Frontend Tests" task

Running the tests from the command line:
```
npm test
```

Running the frontend server from Idea or the command line:
Copy the frontend env template once, then fill in your local API keys:
```powershell
Copy-Item viite-UI\.env.example viite-UI\.env.local
```
The local file is ignored by Git. Restart Vite after changing its values.

Start the dev server from the repository root:
```
npm run dev
```

The frontend is available at <http://localhost:9003/>. Start the backend separately.

Unit tests are independent of the frontend server and backend:
```
npm run test:unit
```

Run tests in watch mode separately from Vite development serving:
```
npm run test:watch
```

Production build validation runs lint, unit tests, and Vite:
```
npm run build
```

The deployment command runs the validated build:
```
npm run deploy
```

Preview a completed build at <http://localhost:4173/>:
```
npm run preview
```

Initializing the database
=========================
Empty database must be initialized for the Flyway by running the task "Flyway init"
or by calling the Viite AdminAPI: <http://localhost:8080/api/admin/flyway_init>.

All the tables can be created and populated with the test data by running the task "Fixture reset test".
TODO: nodes and junctions test data must be created too.

Flyway migrations can be run by running the task "Flyway migrate"
or by calling the Viite AdminAPI: <http://localhost:8080/api/admin/flyway_migrate>.
("Fixture reset test" does this already.)

[Deployment and Infra related instructions](https://github.com/finnishtransportagency/viite-infra?tab=readme-ov-file#viite-infrastructure)
=================================================
