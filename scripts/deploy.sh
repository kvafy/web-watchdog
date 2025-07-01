#/usr/bin/env bash

# Description:
# Runs tests of the current app and, if green, copies the uberjar via ssh to
# a remove machine.
# Configure the variables further down marked with "TODO" before using.

# TODO(fill-me-in): The ssh "user@host" location.
SSH_DST="my-user@my-host"
# TODO(fill-me-in): Directory on the remove machine where the uberjar should be.
REMOTE_DIR="/home/my-user/path/web-watchdog"

echo -e "\n\nRunning tests ..."
lein test
if [ $? -ne 0 ]; then
  echo "Error: Tests failed"
  exit 1
fi

echo -e "\n\nBuilding the uberjar ..."
lein uberjar
if [ $? -ne 0 ]; then
  echo "Error: Build failed"
  exit 1
fi


echo -e "\n\nCopying uberjar to the server ..."
scp target/web-watchdog-*-standalone.jar  ${SSH_DST}:${REMOTE_DIR}
if [ $? -ne 0 ]; then
  echo "Error: Deployment of uberjar failed"
  exit 1
fi


echo -e "\n\nDeployment succeeded!"
echo -e "Connect to the server and restart the service:"
echo -e "  $ sudo service web-watchdog restart"
exit 0
