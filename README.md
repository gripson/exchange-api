# Horizon Data Exchange Server and REST API

The data exchange API provides API services for the exchange web UI (future), the edge devices, and agreement Bots.

The exchange service also provides a few key services for BH for areas in which the decentralized P2P tools
do not scale well enough yet. As soon as the decentralized tools are sufficient, they will replace these
services in the exchange.

## Preconditions

- [Install scala](http://www.scala-lang.org/download/install.html)
- (optional) Install conscript and giter8 if you want to get example code from scalatra.org
- Install postgresql locally (unless you have a remote instance you are using). Instructions for installing on Mac OS X:
    - `brew install postgresql`
    - `echo 'host all all <my-local-subnet> trust' >> /usr/local/var/postgres/pg_hba.conf`
    - `sed -i -e "s/#listen_addresses = 'localhost'/listen_addresses = 'my-ip'/" /usr/local/var/postgres/postgresql.conf`
    - `brew services start postgresql`
    - test: `psql "host=<my-ip> dbname=<myuser> user=<myuser> password=''"`
- Add a config file on your development system at /etc/horizon/exchange/config.json with at least the following content (this is needed for the automated tests). Defaults and the full list of config variables are in `src/main/resources/config.json`:

```
{
	"api": {
		"db": {
			"jdbcUrl": "jdbc:postgresql://localhost/myuser",		// my local postgres db
			"user": "myuser",
			"password": ""
		},
		"smtp": {
			"host": "mysmtp.relay.com",	 // the SMTP relay svr the exchange uses to send pw reset emails
			"user": "myemail@email.net",    // email address
			"password": "myemailpw"    // email pw
		},
		"logging": {
			"level": "DEBUG"
		},
		"root": {
			"password": "myrootpw",
			"email": ""
		}
	}
}
```

## Building in Local Sandbox

- `./sbt`
- `jetty:start`
- Or to have the server restart automatically when code changes: `~;jetty:stop;jetty:start`
- Once the server starts, to try a simple rest method browse: [http://localhost:8080/v1/devices?id=a&token=b](http://localhost:8080/v1/devices?id=a&token=b)
- To see the swagger output, browse: [http://localhost:8080/api](http://localhost:8080/api)
- Run the automated tests (with the exchange server still running): `./sbt test`
- Run the performance tests: `src/test/bash/scale/test.sh` or `src/test/bash/scale/wrapper.sh 8`

## Building and Running the Container

- Update the `DOCKER_TAG` variable to the appropriate version in the Makefile
- To build the build container, compile your local code, build the exchange container, and run it: `make` . Or you can do the individual steps:
    - Build the build container: `make .docker-bld`
    - Build the code from your local exchange repo in the build container: `make docker-compile`
    - Build the exchange api container and run it locally: `make .docker-exec-run`
- Manually test container locally: `curl -# -X GET -H "Accept: application/json" -H "Authorization:Basic bp:mypw" http://localhost:8080/v1/devices | jq .`
    - Note: the container can not access a postgres db running locally on the docker host if the db is only listening for unix domain sockets.
- Run the automated tests: `./sbt test`
- Export environment variable `DOCKER_REGISTRY` with a value of the hostname of the docker registry to push newly built containers to (for the make target in the next step)
- Push container to our docker registry: `make docker-push-only`
- Deploy the new container to a docker host
    - Ensure that no changes are needed to the /etc/horizon/exchange/config.json file
- Test the new container : `curl -# -X GET -H "Accept: application/json" -H "Authorization:Basic myuser:mypw" https://<exchange-host>/v1/devices | jq .` (or may be https, depending on your deployment)
- To see the swagger info from the container: `https://<exchange-host>/api`
- Log output of the exchange svr can be seen via `docker logs -f exchange-api`, or it also goes to `/var/log/syslog` on the exchange docker host
- At this point you probably want to `make clean` to stop your local docker container so it stops listening on your 8080 port, or you will be very confused when you go back to running new code in your sandbox, and your testing doesn't seem to be executing it.

## Limitations/Restrictions of Current Version

- The properties used for advertising and searching should always have its value element be a string. For example the memory property value should be `"300"` instead of `300`. This is because scalatra is automatically converting the json to scala data structures, and i don't know how to have the data structures that vary in type.

## Changes Between v1.20.0 and v1.21.0

- adjusted debug logging to be more helpful and less verbose
- fixed POST admin/migratedb

## Changes Between v1.19.0 and v1.20.0

- added support for base64 encoded credentials in the header
- added optional filter params to GET /devices and /agbots
- now log who each rest api call is from
- root user is now saved in the persistent db (but still loaded/updated from config.json at startup)
- deleting a user will now delete all of its devices, agbots, and agreements (we now have a proper foreign key between users and devices/agbots, and we use the sql on delete cascade option, so now the db completely takes care of object referencial integrity)

## Changes Between v1.18.0 and v1.19.0

- added fallback for getting device/agbot owner from the db during authentication
- automated tests can now use environment variables to run against a different exchange svr
- split the create part of PUT /users/{u} to POST /users/{u} so it can be rate limited. (Except PUT can still be used to creates users as root.)
- fixed but that didn't let POST /users/{u}/reset be called with no creds
- fixed schema of users table to have username be the primary key

## Changes Between v1.17.0 and v1.18.0

- Added support for putting token, but not id, in URL parms

## Changes Between v1.16.0 and v1.17.0

- Fixed bug when user creds are passed into url parms
- Increased timeouts when comparing auth cache to db, and temporarily fall back to cached

## Changes Between v1.15.0 and v1.16.0

- Added synchronization (locking) around the auth cache

## Changes Between v1.14.0 and v1.15.0

- DB persistence complete. Remove `api.db.memoryDb` from your config.json to use (the default is to use it). Specifically in this update:
    - Fixed some of the /users routes in the persistence case
    - Modified the auth cache to verify with the db when running in persistence mode (still a few changes needed for agbots)
    - Added error handling to /users routes in persistence mode
    - Got UsersSuite tests working for persistence (the device and agbot calls within there are still skipped in persistence mode)
    - Added persistence for all /devices routes, including transactions where needed
    - Added persistence for all /agbots routes, including transactions where needed
- Added POST /agreements/confirm
- Added config.json flag to disable getting microservices templates (because that part is a little slow). In this case, PUT /devices/{id} just returns an empty map `{}`
- DELETE /devices/{id} and /agbots/{id} now delete their agreements too

## Changes Between v1.13.0 and v1.14.0

- Changed field daysStale to secondsStale in POST /search/devices. **This is not a backward compatible change.**
- Caught json parsing exceptions so they don't directly get returned to the client.
- Finished db persistence for GET /devices and /devices/{id}

## Changes Between v1.12.0 and v1.13.0

- Added POST /agbots/{agid}/dataheartbeat and POST /agbots/{agid}/isrecentdata (experimental)
- Added POST /admin/initdb and /admin/dropdb to create the db schema. Still experimenting about whether we want these.
- Added POST /admin/loglevel to change the logging level on the fly
- Fixed bug: users and id's were not getting removed from the auth cache when they were deleted from the db.
- Fixed bug: an agreement for 1 MS of a device prevented the other MS from being returned in POST /search/devices
- Must for now set `db.memoryDb: true` in config.json file, like this:

```
{
	"api": {
		"db": {
			"memoryDb": true
		}
	}
}
```

## Changes Between v1.11.0 and v1.12.0

- Fixed poor error msg when root email in config.json is blank
- Added in-memory cache of non-hashed pw/tokens. Increased speed by more than 10x.
- (No external changes in this version AFAIK)

## Changes Between v1.10.0 and v1.11.0

- added scale/performance tests
- added a softwareVersions section of input to PUT /devices/{id} so devices can report their sw levels to the exchange. See swagger for details.
- POST /users/{username}/reset is now implemented and will email a timed pw reset token to the user's email address
- Made the logging level configurable via the config.json file


## Changes Between v1.9.0 and v1.10.0

- Now it hashes all of the passwords and tokens before storing them in the db
- Add 2 /admin routes that can only be run as root: reload (reload the config file) and hashpw (return a hash the given pw)
- Added support for the root pw in the config file to be a hashed value
- To run the automated tests you must now create a local config file with the root pw in it (see above)
- Straightened out some of the Makefile variables and dependencies

## Changes Between v1.8.0 and v1.9.0

- Added support for using an osgi version range in POST /search/devices
- Made GET / return html that lists available exchange services (so far just a link to the api swagger info)
- Fixed bug where PUT /devices/{id} created the device in the db even if the microservice url was wrong such that micro templates could not be returned
- Made the microservice url input to PUT /devices/{id} tolerant of a trailing /
- Added support for /etc/horizon/exchange/config.json. If that config file doesn't exist, the server will still run with default values appropriate for local development.
- Updated all of the bash test scripts to use a real microservice url.

## Changes Between v1.7.0 and v1.8.0

- PUT /devices/{id} now returns a hash with keys of specRef and values of microservice template json blob

## Changes Between v1.6.0 and v1.7.0

- Fixed bug: agbot couldn't run POST /search/devices
- Fixed a few other, more minor, access problems
- Updated swagger info to clarify who can run what