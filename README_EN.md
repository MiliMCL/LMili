<p align="center">
  <img src="public/image/Mili/mili-logo.png" alt="Mili Logo" width="600">
</p>

<h1 align="center">Mili</h1>

<p align="center">
  <strong>A high-performance Minecraft server core based on Folia: a purer region-threading experience, more APIs, better stability</strong>
</p>

<p align="center">
  <a href="./README.md">中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.x_(26.2)-green" alt="Minecraft 26.2">
  <img src="https://img.shields.io/badge/JDK-25+-orange" alt="JDK 25+">
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="GPL-3.0">
</p>

---

## Overview

Mili is a Minecraft server core built on the **Paper → Folia** fork chain. The goal is to be a **pure Folia**: no technical/redstone mechanic modifications, no client-protocol hacks — just **more APIs, stability fixes and bug fixes**, plus general-purpose performance optimizations on top of Folia's region-based multithreading model.

### Fork Chain

```
Minecraft (vanilla)
  └── Paper (server framework)
        └── Folia (region multithreading)
              └── Mili (this project)
```

> Mili is now a server directly based on Folia, with package name `fun.bm.mili.lmili`.

---

## Core Features

### Folia Stability Fixes

- **Region Balancer**: Shared thread pool + priority queue replacing Folia's per-region exclusive threads, dynamic load balancing
- **Region Load Monitor**: Lock-free sliding window for region tick time statistics
- **Adaptive TPS Manager**: Dynamically adjusts TPS based on real-time load
- **Cross-Region Helper**: Typed cross-region event queue (entity damage, block notifications, etc.)
- **RegionTaskIdRegistry**: Global UUID registry preventing cross-chunk task ID collisions that cause crashes
- **Global Entity Counter**: Aggregates mob counts by region, avoiding O(entities) scans
- **Thread safety hardening**: Project-wide `catch(Exception)` → `catch(Throwable)` fix, preventing OOM/StackOverflow Errors from silently killing scheduler threads

### Bug Fixes

Numerous fixes targeting Folia's region threading model, including (but not limited to):

- Player respawn placement corrections; a series of race fixes around entity teleportation (cross-region / ender pearls / dimension switching)
- Guards against off-region pathfinding / leashing / target selection
- Fixes for delayed POI updates, chunk reload detection, dragon part synchronization
- RegionizedTaskQueue concurrent reference corrections

### General Performance Optimizations

General-purpose optimizations from Gale / Lithium / Pufferfish / SparklyPaper / Kaiiju / Petal / Krypton / Leaves and others (none of which alter technical gameplay behavior), for example:

- Data structure optimizations for noise generation, AI attributes, brain maps, criterion maps
- Zero-movement entity move skipping, variable entity wake-up duration, optimized canSee checks
- Reduced chunk loading lookups, projectile chunk loading reduction, region-limited pathfinding
- Network/protocol layer optimizations, chunk delta compression
- Villager lobotomize optimization, sensor work reduction

### API Extensions

More capabilities on top of the Folia/Paper API:

| API | Description |
|-----|-------------|
| **Tick Regions API** | APIs for querying/operating tick regions (`ThreadedRegion`, `RegionStats`, etc.) |
| **ReplayMod Photographer** | Create ReplayMod photographer entities for recording, `Photographer` / `PhotographerManager` API |
| **Bytebuf API** | Custom packet read/write API for plugins |
| **Async entity teleport events** | `EntityTeleportAsyncEvent`, `PreEntityPortalEvent`, `PostEntityPortalEvent`, etc. |
| **Waypoint API** | Entity waypoint tracking and restoration API |
| **ThreadedRegionizer** | API to obtain the global `ThreadedRegionizer` instance |

---

## Quick Start

### Requirements

| Dependency | Version | Notes |
|------------|---------|-------|
| JDK | 25+ | Build toolchain (Mili 26.2 branch requires Java 25, not JDK 21) |
| Git | 2.x | Enable long path support on Windows |

### Build Steps

```bash
# 1. Clone repository
git clone https://github.com/xucy10/Mili.git
cd Mili

# 2. Enable long paths on Windows
git config --global core.longpaths true

# 3. Apply patches (required for first build)
./gradlew applyAllPatches --no-configuration-cache --no-build-cache

# 4. Inject Kotlin support
python scripts/inject_kotlin.py

# 5. Build Paperclip JAR
./gradlew :mili-server:createPaperclipJar
```

Build artifacts in `mili-server/build/libs/`:
- `mili-26.2-paperclip.jar` — runnable Paperclip JAR

---

## API Usage

### Gradle

```kotlin
repositories {
    maven {
        url = "https://repo.menthamc.org/repository/maven-public/"
    }
}

dependencies {
    compileOnly("fun.bm.mili:mili-api:26.2-R0.1-SNAPSHOT")
}
```

### Maven

```xml
<repositories>
  <repository>
    <id>menthamc</id>
    <url>https://repo.menthamc.org/repository/maven-public/</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>fun.bm.mili</groupId>
    <artifactId>mili-api</artifactId>
    <version>26.2-R0.1-SNAPSHOT</version>
    <scope>provided</scope>
  </dependency>
</dependencies>
```

---

## Project Structure

```
Mili/
├── mili-api/                  # Mili API module
│   └── src/main/java/         #   Event API, Photographer, Bytebuf
├── mili-server/               # Mili server core
│   ├── minecraft-patches/     #   97 feature patches (features/)
│   ├── paper-patches/         #   Paper API/Server layer patches
│   └── src/main/
│       └── java/fun/bm/mili/  #   Java source
│           ├── bridge/        #     Chunk-region bridge
│           ├── chunk/         #     Chunk system
│           ├── command/       #     Command system
│           ├── config/        #     Config modules (TOML, pure Java implementation)
│           ├── metrics/       #     bStats metrics
│           ├── portal/        #     Portal management
│           ├── utils/         #     Utilities (region scheduling, network optimization, memory management, etc.)
│           └── villager/      #     Villager optimizer
├── lmili-api/                 # LMili extra API sources (package fun.bm.mili.lmili)
├── folia-server/              # Folia submodule (upstream, do not modify directly)
├── paper-server/              # Paper server (patch application target)
├── paper-api/                 # Paper API (patch application target)
├── docs/                      # Documentation
├── build.gradle.kts           # Root build script
└── gradle.properties          # Version & upstream ref configuration
```

---

## Configuration

Mili provides a TOML configuration file (pure Java parsing implementation):

| File | Package | Description |
|------|---------|-------------|
| `mili_config.toml` | `fun.bm.mili.config.modules` | Main config: feature toggles, experiments, fixes and optimizations |

Config categories:

| Category | Description | Example modules |
|----------|-------------|-----------------|
| `function` | Gameplay mechanics & utilities | `LanguageConfig`, `TpsBarConfig`, `RegionBarConfig` |
| `experiment` | Experimental performance/concurrency features | `RegionBalancerConfig`, `CrossDimensionTeleportQueueConfig` |
| `optimizations` | Performance optimizations | `NetworkOptimizerConfig`, `MmapRegionStorageConfig`, `VillagerOptimizerConfig` |
| `fixes` | Crash/behavior fixes | `PortalLinkFixConfig`, `CollisionBehaviorConfig` |
| `misc` | Miscellaneous | `AutoUpdateConfig`, `BStatsConfig`, `ServerModNameConfig` |

---

## Patch Workflow

Mili manages feature patches with the **Hyacinthusweight** (paperweight-based) patch system:

1. Modify code in `mili-server/src/minecraft/java/`
2. Commit changes: `git commit -m "description"`
3. Rebuild patches: `./gradlew :mili-server:rebuildAllServerPatches`
4. Commit the patch files and push

Edits to generated files under `mili-server/src/minecraft/java/` are overwritten by `applyAllPatches`; changes must go through patch files in `minecraft-patches/features/`.

See the [contributing guide](docs/CONTRIBUTING_EN.md) for details.

---

## Contributing

Pull requests and issues are welcome! Please read first:

- [Contributing guide](docs/CONTRIBUTING_EN.md)
- When reporting issues, include full logs, environment info and reproduction steps

---

## Related Links

| Project | Link |
|---------|------|
| Folia (direct upstream) | https://github.com/PaperMC/Folia |
| Paper | https://github.com/PaperMC/Paper |
| Lophine (former direct upstream) | https://github.com/LophineLabs/Lophine, continued by its original developer |

---

## Community

<!-- [Discord](https://discord.com/invite/BSa67dbvVf) -->

QQ Group: (TBA)

## Acknowledgements

Thanks to all contributors and sponsors for their continued support. If this project helps you, please give us a star on GitHub.

## License

This project is licensed under GPL-3.0.
