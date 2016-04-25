The java file in this directory is not really needed. It is technically a hack
so that ninja/gyp, according to the build files in the chromium project, will
create a jar file containing only resources. It is a requirement to declare at least
one source file, if we don't want to change the build scripts.
