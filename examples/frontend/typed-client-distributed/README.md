# Distributed typed route client

One `.ssc` file can run as a JVM backend on one machine and as a frontend
client on another machine. The client calls generated `Messages.*` methods from
`apiClients:`; those methods use HTTP `fetch` and the `--server-url` backend
base URL.

Start the backend:

```bash
ssc run --mode server --backend jvm --host 0.0.0.0 --port 49155 examples/frontend/typed-client-distributed/typed-client-distributed.ssc
```

Start a browser client, on the same or another machine:

```bash
ssc run --mode client --frontend react --server-url http://127.0.0.1:49155 examples/frontend/typed-client-distributed/typed-client-distributed.ssc
```

For a remote machine or phone, replace `127.0.0.1` with the backend machine's
LAN address printed by the server command. Electron client mode uses the same
source:

```bash
ssc run --mode client --frontend electron --server-url http://BACKEND_HOST:49155 examples/frontend/typed-client-distributed/typed-client-distributed.ssc
```
