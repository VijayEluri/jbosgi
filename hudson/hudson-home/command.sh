#!/bin/sh
#
# A script that uses Maven to build the project and
# execute its test suite against a given target container 
#
# $Id$

OSGIDIR=$WORKSPACE
DISTRODIR=$OSGIDIR/distribution/installer/target
HUDSONDIR=$OSGIDIR/hudson
HUDSONBIN=$HUDSONDIR/hudson-home/bin

case "$CONTAINER" in
  'jboss600')
    SERVER_NAME=default
    JBOSS_BUILD=jboss-6.0.0.M3
    JBOSS_ZIP=$HUDSON_HOME/../jboss/$JBOSS_BUILD.zip
	CONTAINER_HOME=$WORKSPACE/jboss-6.0.0.20100429-M3
	CONTAINER_LOG=$CONTAINER_HOME/server/$SERVER_NAME/log/server.log
    rm -rf $CONTAINER_HOME; unzip -q $JBOSS_ZIP -d $WORKSPACE  
    cp --backup $HUDSONBIN/run-with-pid.sh $CONTAINER_HOME/bin/run.sh
  ;;
  'jboss601')
    SERVER_NAME=default
    JBOSS_BUILD=jboss-6.0.0-SNAPSHOT
    JBOSS_ZIP=$HUDSON_HOME/../jboss/$JBOSS_BUILD.zip
	CONTAINER_HOME=$WORKSPACE/$JBOSS_BUILD
	CONTAINER_LOG=$CONTAINER_HOME/server/$SERVER_NAME/log/server.log
    rm -rf $CONTAINER_HOME; unzip -q $JBOSS_ZIP -d $WORKSPACE
    cp --backup $HUDSONBIN/run-with-pid.sh $CONTAINER_HOME/bin/run.sh
  ;;
  'runtime')
    SERVER_NAME=all
	CONTAINER_HOME=$DISTRODIR/auto-install-dest/runtime
	CONTAINER_LOG=$CONTAINER_HOME/server/$SERVER_NAME/log/server.log
  ;;
  *)
	echo "Unsupported container: $CONTAINER"
	exit 1
  ;;
esac

#
# Report the Git submodule status
#
if [ -f .gitmodules ]; then
   GIT_CMD="git submodule status"
   echo $GIT_CMD; $GIT_CMD
fi

#
# Report the last 10 commits
#
GIT_CMD="git log --pretty=%x20%h%x20%s%x20(%an) -10"
echo $GIT_CMD; $GIT_CMD
 
#
# Setup the build environment
# 
ENVIRONMENT="-Dtarget.container=$CONTAINER -Djboss.bind.address=$JBOSS_BINDADDR -Djboss.home=$CONTAINER_HOME"

#
# Do the sanity reactor build
#
if [ $CONTAINER = 'runtime' ]; then
  MVN_CMD="mvn clean install"
  echo $MVN_CMD; $MVN_CMD; MVN_STATUS=$?
  if [ $MVN_STATUS -ne 0 ]; then
     echo maven exit status $MVN_STATUS
	 exit 1
  fi
fi

#
# Build the distro
#
MVN_CMD="mvn -Dnoreactor -Pdistro $ENVIRONMENT clean install"
echo $MVN_CMD; $MVN_CMD; MVN_STATUS=$?
if [ $MVN_STATUS -ne 0 ]; then
  echo maven exit status $MVN_STATUS
  exit 1
fi

#
# Deploy distro
#
cp $DISTRODIR/jboss-osgi-installer-*.jar $DISTRODIR/jboss-osgi-installer.jar
AUTO_INSTALL=$DISTRODIR/resources/auto-install-template.xml; cat $AUTO_INSTALL;
JAVA_CMD="java -jar $DISTRODIR/jboss-osgi-installer.jar $AUTO_INSTALL"
echo $JAVA_CMD; $JAVA_CMD 

#
# log dependency tree
#
MVN_CMD="mvn -o $ENVIRONMENT dependency:tree"
echo $MVN_CMD; $MVN_CMD | tee $WORKSPACE/dependency-tree.txt

#
# start jbossas/runtime
#
STARTUP_CMD="$HUDSONBIN/startup.sh $CONTAINER_HOME start $SERVER_NAME $JBOSS_BINDADDR"
echo $STARTUP_CMD; $STARTUP_CMD

#
# Was it successfully started?
#
$HUDSONBIN/http-spider.sh $JBOSS_BINDADDR:8090/jboss-osgi $WORKSPACE
if [ -e $WORKSPACE/spider.failed ]; then
  tail -n 200 $CONTAINER_LOG
  $HUDSONBIN/startup.sh $CONTAINER_HOME stop
  exit 1
fi

#
# execute tests
#
MVN_CMD="mvn -o -Dnoreactor -fae $ENVIRONMENT test"
echo $MVN_CMD; $MVN_CMD 2>&1 | tee $WORKSPACE/tests.log
cat $WORKSPACE/tests.log | egrep FIXME\|FAILED | sort -u | tee $WORKSPACE/fixme.txt
cat $WORKSPACE/fixme.txt | egrep "\[\S*]" > $WORKSPACE/errata-$CONTAINER.txt || :

#
# stop jbossas/runtime
#
$HUDSONBIN/startup.sh $CONTAINER_HOME stop
