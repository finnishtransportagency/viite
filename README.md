VIITE
=====

Setting up the local dev environment
====================================

Source Code
-----------
Clone "viite"-repository from Github

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
Install PostGIS by [downloading and installing it](https://postgis.net/install/) or
by using Docker Compose:
```
cd aws/local-dev/postgis
docker-compose up
```
Docker Compose installs and starts the PostGIS database server with the needed database and user.
- Username: viite
- Password: viite
- Database: viite

Required Integrations
---------------------
Viite needs to get the links, the background maps and the coordinates for addresses
from external systems. For these connections to work, open Väylä VPN and
open SSH-tunnel with the needed port forwardings to a Väylä server.
- 9180: Viite running in dev/devtest environment
- 8997: OAG

Idea Run Configurations
-----------------------
If you are developing with the IntelliJ Idea, you can import the run configurations
from the `aws/local-dev/idea-run-configurations` folder.

Building and Running the Backend
---------------------------------
Backend reads environment specific variables from the environment variables.
These variables for development are listed in
`aws/local-dev/environment-variables.properties` 

If you are using Idea, these variables are already set in the "Server" and "Test" run configurations.

Running the unit tests from Idea:
- Run the "Test" sbt Task

Running the unit tests from the command line:
```
./sbt test
```

Running the backend from Idea:
- Run the "Server" sbt Task

Running the backend from the command line in the development mode:
(Environment variables need to be set first)
```
./sbt '~;container:start; container:reload /'
```

Building and Running the Frontend
==================================
Building the frontend:
```
grunt
```

Running the tests:
```
grunt test
```
(Or run the "Grunt Test" from Idea)

Running the frontend server:
```
grunt server
```
(Or run the "Grunt Server" from Idea)

Running the frontend runs the tests, builds less and runs in the watch-mode. 

Frontend server sends the requests to the backend server that needs to be running for the application to work.

UI will be available in this address: <http://localhost:9003/>.

Initializing the database
=========================


Käyttäjien lisääminen ja päivittäminen CSV-tiedostosta
======================================================

Palvelun käyttäjien tietoja voi päivittää ja uusia käyttäjiä voi lisätä CSV - tiedostosta, jossa on määritelty uusien ja päivitettävien käyttäjien käyttäjänimet sekä kuntatunnukset joihin näillä käyttäjillä tulisi olla oikeudet.

Alla esimerkki CSV-tiedostosta:
```
kuntakäyttäjä; ;105, 258, 248, 245;
olemassaolevatunnus; ;410, 411, 412, 413;
elykäyttäjä;0,1,2,3,4,5,6,7,8,9;
```

Käyttäjiä voi päivittää ja lisätä käyttäen `import-users-from-csv.sh` skriptiä:
```
./import-users-from-csv.sh <digiroad2-palvelin:portti> <ylläpitäjän-tunnus> <polku-csv-tiedostoon>
```

[Käyttöönotto ja version päivitys](Deployment.md)
=================================================
