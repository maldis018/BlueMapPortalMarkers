# BlueMapPortalMarkers

A Paper plugin that adds a toggleable layer of Nether Portal markers to [BlueMap](https://bluemap.bluecolored.de/). The marker layer updates automatically: new portals are picked up via Bukkit's `PortalCreateEvent`, existing portals are found through a Paper 26.1 POI API sweep around spawn and online players, and additional portals are discovered through POI queries as chunks load. Discovered portals are persisted to a JSON file so the layer survives restarts.

## Requirements

- Paper 26.1.2+ server
- The BlueMap plugin installed and enabled
- Java 21+ runtime

## Build

```sh
./gradlew build
```

The resulting plugin jar is written to `build/libs/`.

Note: this project is freshly scaffolded and has not been built or tested yet.
