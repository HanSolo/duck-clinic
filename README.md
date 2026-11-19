# Jakarta EE Duck Clinic


## Run the Jakarta EE Duck Clinic

### Run with Open Liberty

```
mvn clean package liberty:run
```

### Run with WildFly

```
mvn clean package wildfly:run -Pwildfly
```

### Run with Payara

```
mvn clean package payara-server:start
```

The application is then available at http://localhost:8080/duck-clinic/.

### Run with Docker

Needs Docker with Compose. The `Dockerfile` builds the WAR and runs it on Payara Server.

```
docker compose up --build
```

Open http://localhost:8080/duck-clinic/ once the log says the application is deployed. Stop with `docker compose down`.

The data is reset at every start, and port 8080 is bound to `127.0.0.1` only. See `README-docker.md` for details, including how to change the Payara version.
