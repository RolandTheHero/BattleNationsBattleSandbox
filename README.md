# Battle Nations Battle Sandbox
This project creates a sandbox for battling in Battle Nations. The user can choose to put any unit formations against each other for fun or to trial units.

This work adapts the [Battle Nations Animation Grabber](https://github.com/bobmath/BattleNationsAnimation) (BaNG) project into a battle simulation sandbox.
Parts of BaNG is used to parse unit stats and play animations.
The modifications to the BaNG are as follows:
- The JSON library used (javax.json) is switched out for [org.json](https://mvnrepository.com/artifact/org.json/json).
- Added the parsing of more attributes of various files.
- Added parsing of Madrona's Unity game files.

Other work in this project is almost entirely (99.9%+) AI generated.
This began as a test to see how far the Battle Nations battle system could be recreated using AI.

A copy of the Battle Nations game files is required. This project does not include them! Both the pre-Madrona bundle folder and an install of the Madrona (Unity) version can be loaded.

This project is licensed under the GNU General Public License, version 2 (see [LICENSE](LICENSE)). It includes and adapts third-party code and data under compatible licences; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the credits and notices.

Battle Nations is (C) Madrona Games, Inc.
This tool is an unofficial community project. When sharing screenshots, videos or other media of this tool, you must make clear that it comes from this tool and not from the original game.

## How to Use
1. Make sure you have Java installed (minimum version 16).
2. Clone this repository.
3. Run the Main file.
4. Select the game files folder when prompted.
