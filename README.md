# GemPowers

An original, configurable gem-powered PvP plugin for Paper 1.21.11.

## Requirements
- Java 21
- Paper 1.21.11 (or compatible 1.21.x)
- Maven 3.9+ to build locally

## Build
Run `mvn package` from the repository root. The plugin JAR is created at `target/gempowers-1.0.0.jar`.

GitHub Actions builds the plugin automatically when code is pushed. Open **Actions → Build GemPowers → latest run → Artifacts** to download the JAR.

## Commands
- `/gems` — show your assigned gem, tier, and energy
- `/gems ability` — activate your ability
- `/gems trust <player>` and `/gems untrust <player>` — manage trusted players
- `/gems admin set <player> <gem> [tier]` — assign a gem
- `/gems admin energy <player> <amount>` — set energy
- `/gems admin give <player> <gem> [tier]` — give a gem item
- `/gems admin reload` — reload configuration

Admin commands require `gempowers.admin`; ability use requires `gempowers.use`.

## Install
Download the built JAR from the Actions artifact, place it in the server's `plugins/` folder, and restart the server. Test on a backup/staging server first; plugin behavior and balance should be tested on your exact Paper build before using it on a live SMP.