Viite PostGIS with Docker Compose
=================================
Docker Compose installs and starts the PostGIS database server for local Viite development:

- Database: viite
- Username: viite
- Password: viite

**Start the postgis server:**

`docker-compose up`

or

`./start.sh`

**Start the postgis server on the background:**

`docker-compose up -d`

**Stop the server:**

`docker-compose down`

or

`./stop.sh`

**Clean the data:**

- Delete the `data` directory
