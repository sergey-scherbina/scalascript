# Swing Full-Stack

No-socket JVM desktop example: Swing frontend and JVM backend routes run in one
process.

Run it with:

```bash
ssc run-jvm --frontend swing --transport in-process examples/frontend/swing-fullstack/swing-fullstack.ssc
```

The `Save` button posts the text field to `/api/messages` through the generated
in-process dispatcher. On a 2xx response the UI clears the field and increments
the local success counter.
