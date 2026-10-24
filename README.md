# Veil — Fabric 26.3

Fabric rendering, shader and editor infrastructure for Minecraft 26.3. Original project: [FoundryMC/Veil](https://github.com/FoundryMC/Veil). Preserve `LICENSE` and the dependency notices in `licenses/`.

## Workspace

Clone the Fabric 26.3 branches into sibling directories:

```sh
git clone -b 26.3 https://github.com/nedogimov-m/imguimc-fabric.git imguimc
git clone -b 26.3 https://github.com/nedogimov-m/Veil-Fabric.git veil
git clone -b 26.3 https://github.com/nedogimov-m/sable-fabric.git sable
git clone -b 26.3 https://github.com/nedogimov-m/Simulated-Fabric.git Aeronautics
```

Use JDK 25. Gradle 9.7 and Fabric Loom 1.18.4 are pinned by the wrapper/build files.

## Build

```sh
./gradlew assemble --no-configuration-cache
```

The sibling ImGuiMC project is included through a Gradle composite build. Output: `build/libs/veil-fabric-*.jar`. Sable already includes this JAR, so the complete Aeronautics installation does not require an additional copy.

Only Fabric/common source sets are compiled. The local Maven directory contains pinned third-party compiler libraries; generated build files and historical port diagnostics are excluded from Git. The Fabric 26.3 assembly and native client resource/world startup passed.
