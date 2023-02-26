## This simple command-line program uses Redis to store and randomly fetch questions that have been written as JSON objects
### The default behavior of the application is to load questions into redis from a local file called: ``jsonkey.tldf``

* To run the program loading questions from the local jsonkeyvalue.tldf file (supplying the host and port for Redis) do:
```
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host myhost.com --port 10000"
```

* To run the program without loading the questions from the local file (reuse - what is already in Redis) do:

```
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host myhost.com --port 10000 --loadquestions false"
```

* To run the program with a specific password and filepath to a different question source file do:

``` 
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-11100.cnn.non-gbg-2.gce.cloud.redislabs.com --port 11100 --password 5jrLKFWjuNqd**^^%%TY^% --filepath src/main/resources/jsonkeyvaluePrivate.tldf"
```
