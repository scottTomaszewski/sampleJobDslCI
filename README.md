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

* Automatically create flows/builds/releases/whatever for aggregting modules across not only
platform releases, but also cdm versions
    * ex: bom is created for all modules on v8.  See if you can also create separate boms for
    v8-cdm1.2.3 vs v8-cdm4.5.6
* Cleanup descriptions to be useful from the pipeline view
* add manual step for promotion of bom
* Adds parameterized args to each job that takes in env vars