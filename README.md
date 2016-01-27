# sampleJobDslCI

## Backlog

* Optimize build job
    * Threads
        * Thread 1: minimal help:evaluate calls for mvn clean install
        * Thread 2: downstream params via help:evaluate
    * Todo: possible that help:evaluate finishes after clean install thread, although unlikely
    * Could have multiple threads run their stuff, build variables, then print them to their own
    env.properties file.  At the end (figuring this part out might be non-trivial?), read in all the
       env.properties files.