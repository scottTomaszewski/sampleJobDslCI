# sampleJobDslCI

## Backlog

* Optimize build job
    * Threads
        * Thread 1: minimal help:evaluate calls for mvn clean install
        * Thread 2: downstream params via help:evaluate
    * Todo: possible that help:evaluate finishes after clean install thread, although unlikely