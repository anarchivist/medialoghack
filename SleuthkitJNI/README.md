prerequisites: Sleuthkit, libewf, zlib

to build: mvn clean compile assembly:single

to run: java -jar -Djava.library.path=/usr/local/lib target/SleuthkitJNI-1.0-SNAPSHOT-jar-with-dependencies.jar