# FlareSolverr

Some websites have anti-bot protections that cannot be easily circumvented. For
such cases web-watchdog supports downloading their HTML contents using
[FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) running as a Docker
container.

## Setup as a System V service

There are different ways how to run FlareSolverr. I use System V on Ubuntu and
the instructions below are for that use case.

The following will create and run the FlareSolverr Docker container, reserving
the name `flaresolverr` for it. It binds FlareSolverr to port 8191, via which
web-watchdog will communicate with it.

```sh
# Ensure Docker is installed.
sudo apt install docker.io
# Add your user to the docker group.
sudo usermod -a -G docker $USER
# Let changes take effect.
sudo reboot now

# Create the a container with 'flaresolverr' alias. First time this will
# download the image.
docker run -d \
  --name=flaresolverr \
  -p 8191:8191 \
  -e LOG_LEVEL=info \
  ghcr.io/flaresolverr/flaresolverr:latest

# Check that FlareSolverr is running.
curl http://localhost:8191/
```

The 'flaresolverr' name can now be used to manually start and stop the
container on demand:

```sh
# Start/stop.
docker start flaresolverr
docker stop flaresolverr

# Remove the `flaresolverr` alias and allow re-defining it again.
docker rm flaresolverr

# Pull new version of the image.
docker pull ghcr.io/flaresolverr/flaresolverr:latest
```

Final step is to install FlareSolverr as a System V service and ensure that it
starts before the web-watchdog service. Follow steps in `scripts/flaresolverr`.
