#!/usr/bin/env bash

# Description:
# Creates a daily back-up of the `state.edn` file and keeps them for 30 days.

# Installation (tested on Ubuntu):
#  - Configure the variables further down marked with "TODO".
#  - Copy this file to an appropriate location on your computer (call this LOC).
#  - Add a CRON entry:
#      $ crontab -e
#
#      Append the following line to your schedule:
#      (make sure that the last line in your file ends with a newline)
#        10 4 * * * bash <LOC>

# TODO(fill-me-in): Directory with the uberjar and the config/state file.
APP_DIR="/home/${USER}/web-watchdog"

BACKUP_DIR="${APP_DIR}/backups"

if [ ! -d "${BACKUP_DIR}" ]; then
  mkdir -p "${BACKUP_DIR}"
fi

cp "${APP_DIR}/state.edn" "${BACKUP_DIR}/state.edn.day-$(date +%d)"
