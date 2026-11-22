# Licensing / 许可说明

YMTR is a **mixed-license project**. It is not distributed under a single
license, and it is not a dual-licensed project where you may choose one
license. Different parts of the project are licensed under different terms.

> **中文摘要：** YMTR 是复合许可项目，不是二选一的双许可项目。未修改的 MTR
> 衍生文件采用 MIT；YMTR 原创文件采用 GPL-3.0-or-later；YMTR 修改过的 MTR
> 文件属于混合文件，整体按 GPL-3.0-or-later 分发，其中 MTR 衍生部分仍保留
> MIT 声明；YMTR 组合作品整体按 GPL-3.0-or-later 分发。详见下文。

The legally binding texts are [LICENSE-MIT](LICENSE-MIT) and
[LICENSE-GPL-3.0-or-later](LICENSE-GPL-3.0-or-later). The project-level notice
is [LICENSE](LICENSE).

## Summary

| Component | License |
| --- | --- |
| MTR-derived components (files identical to upstream MTR) | MIT |
| YMTR-original components (new files created for YMTR) | GPL-3.0-or-later |
| YMTR-modified MTR files (mixed files) | GPL-3.0-or-later as a whole; MIT for the MTR-derived portions |
| The combined YMTR work | GPL-3.0-or-later, with MIT notices preserved |
| Third-party components | their own licenses; see [NOTICE](NOTICE) |
| YMTR name and logo | trademark rights not licensed; see [TRADEMARK.md](TRADEMARK.md) |

SPDX-License-Identifier: `MIT AND GPL-3.0-or-later`

## 1. The boundary: upstream MTR vs. YMTR

The upstream project is Minecraft Transit Railway:
<https://github.com/jonafanho/Minecraft-Transit-Railway>.

YMTR is a fork. For licensing purposes:

1. **MTR-derived component** — a file that is identical to the corresponding
   file in the upstream MTR project.
2. **YMTR-original component** — a file that was created for YMTR and has no
   corresponding upstream file.
3. **Mixed file** — a file that exists upstream but has been modified for YMTR,
   or a file that combines MTR-derived and YMTR-original material.

This is a good-faith, file-level mapping. If a file is ambiguous, treat it as a
mixed file and retain the MIT notice.

## 2. MTR-derived components — MIT

Unmodified upstream MTR code, assets, translations, and build scaffolding are
licensed under the MIT License, except for third-party components included in
them. The upstream copyright notice is:

    Copyright (c) 2022 Jonathan Ho

The full MIT text is in [LICENSE-MIT](LICENSE-MIT). It must be included in all
copies or substantial portions of the MTR-derived components.

Examples of MTR-derived paths include:

- upstream Java files under `common/src/main/java/mtr/` that are identical to
  the upstream version;
- upstream assets under `common/src/main/resources/assets/mtr/` and
  `resources/common/` that are identical to the upstream version;
- the MTR lifts assets under `resources/fabric/lifts/` and
  `resources/forge/lifts/`;
- `gradle/**`, `gradlew`, `gradlew.bat`, and `crowdin.yml`.

This list is illustrative. The rule in section 1 controls.

## 3. YMTR-original components — GPL-3.0-or-later

The following are original works created for YMTR and are licensed under the
GNU General Public License, version 3 or (at your option) any later version:

### Web dashboard

- `common/src/main/java/mtr/webdashboard/**`
- `common/src/main/resources/assets/mtr/webdashboard/**`
- `resources/common/normal/assets/mtr/webdashboard/**`

### Rail data editor

- `common/src/main/java/mtr/item/ItemRailDataEditor.java`
- `common/src/main/java/mtr/packet/DataUpdateBroadcast.java`
- `common/src/main/java/mtr/packet/PacketUpdateRailData.java`
- `common/src/main/java/mtr/screen/RailDataEditorClient.java`
- `common/src/main/java/mtr/screen/RailDataEditorScreen.java`
- `common/src/main/resources/assets/mtr/models/item/rail_data_editor.json`

### Web dashboard networking and UI

- `common/src/main/java/mtr/packet/PacketWebDashboardClient.java`
- `common/src/main/java/mtr/packet/PacketWebDashboardServer.java`
- `common/src/main/java/mtr/screen/RoutePlatformScreen.java`
- `common/src/main/java/mtr/screen/WebDashboardUrlScreen.java`

### Compatibility, audio, and diagnostics

- `common/src/main/java/mtr/client/AnteRailCompat.java`
- `common/src/main/java/mtr/client/NetworkAudioPlayer.java`
- `common/src/main/java/mtr/client/VoxyDepthCompat.java`
- `common/src/main/java/mtr/MtrDebug.java`
- `fabric/src/main/java/mtr/mixin/EntitySetOldPosAndRotMixin.java`
- `fabric/src/main/java/mtr/mixin/VoxyFogCompatMixin.java`
- `forge/src/main/java/mtr/mixin/EntitySetOldPosAndRotMixin.java`
- `forge/src/main/java/mtr/mixin/SHMetroMixin.java`
- `forge/src/main/java/mtr/mixin/VoxyFogCompatMixin.java`
- `resources/fabric/normal/mtr.voxy.mixins.json`
- `resources/forge/normal/mtr.voxy.mixins.json`

### Documentation and branding

- YMTR-authored documentation under `docs/`, except code copied from MTR or
  Minecraft/Mojang;
- `自定义轨道数据.md`;
- `resources/fabric/normal/icon.png` and `resources/forge/normal/icon.png`
  (the YMTR icon; the file paths exist upstream, but the YMTR icon is an
  original work).

The YMTR name and logo are also subject to [TRADEMARK.md](TRADEMARK.md). The
GPLv3 covers the copyright in the icon; it does not grant trademark rights.

This list is not exhaustive. New YMTR-original files are GPL-3.0-or-later even
if they are not listed here. Some files listed above may incorporate code
adapted from MTR; those MTR-derived portions remain MIT under the mixed-file
rule in section 4.

## 4. Mixed files — GPL-3.0-or-later with MIT portions

A mixed file contains both MTR-derived and YMTR-original material. Examples
include:

- MTR Java files that YMTR has modified, such as
  `common/src/main/java/mtr/data/Train.java`,
  `common/src/main/java/mtr/screen/EditStationScreen.java`,
  `common/src/main/java/mtr/render/RenderTrains.java`, and other files that
  differ from upstream MTR;
- `common/src/main/resources/assets/mtr/lang/en_us.json` and `zh_cn.json`;
- `README.md`, `build.gradle`, and other project files that contain YMTR
  changes on top of MTR text;
- assets such as the modified MTR textures under
  `resources/common/normal/assets/mtr/textures/`;
- `docs/path-generation-repro/src/mtr/**`, which contains MTR-derived code for
  a reproduction harness. Those files remain MIT. The Minecraft API stubs under
  `docs/path-generation-repro/src/net/minecraft/**` are not relicensed by YMTR
  and remain subject to Mojang's terms.

For a mixed file:

- the MTR-derived portions remain licensed under MIT;
- the YMTR-original portions are licensed under GPL-3.0-or-later;
- the file as a whole is distributed as part of the GPL-3.0-or-later combined
  work;
- the MIT copyright and permission notices must be retained.

If you reuse a mixed file, you must comply with the MIT terms for the
MTR-derived portions and the GPLv3 terms for the YMTR-original portions.

## 5. The combined work — GPL-3.0-or-later

YMTR is distributed as a combined work. Because the MIT License is compatible
with the GNU GPL, the combined work as a whole is distributed under
GPL-3.0-or-later. This is required by GPLv3 section 5(c) for the GPLv3-covered
parts, and it is permitted by the MIT License for the MTR-derived parts,
provided that the MIT notices are retained.

This does not revoke, replace, or reduce the MIT rights in the MTR-derived
components. Those components remain available under MIT from their copyright
holders. The GPLv3 adds obligations for the combined work; it does not take
away the MIT permissions.

MIT (Expat) is listed by the FSF as GPL-compatible:
<https://www.gnu.org/licenses/license-list.html#Expat>.

Third-party libraries are separate works and remain under their own licenses;
they are not relicensed under GPLv3 by this project. See [NOTICE](NOTICE).

## 6. Third-party components and reference source trees

Third-party libraries, assets, and reference source trees remain under their
own licenses. This includes, without limitation:

- `libs/MSD1.3.4-common.jar`;
- the reference source trees `MTR官方版原始源代码供参考/`,
  `ANTE模组完整源代码/`, and `Voxy模组完整源代码/`;
- Minecraft/Mojang code and API stubs.

These are not part of the YMTR licensed work and must not be redistributed as
if they were YMTR-original code. See [NOTICE](NOTICE) for the main bundled
third-party components.

## 7. A note on the 2026 ARR notice

In July 2026, the project's LICENSE file was changed to "All Rights Reserved"
in response to an incident where a modpack redistributed YMTR with its name and
logo replaced by the official MTR branding.

That notice is superseded by this mixed-license scheme. In particular:

- The ARR notice could not revoke the MIT rights that had already been granted
  for MTR-derived code. Those rights remain in effect.
- YMTR-original components are relicensed by their copyright holder(s) under
  GPL-3.0-or-later for the current and future releases, to the extent of their
  rights.
- Copies of releases that were distributed under the ARR notice remain subject
  to the terms that accompanied those copies. This document does not
  retroactively change the terms of those copies.

The current and future releases use the mixed-license scheme described above.

## 8. Contributions

Unless you explicitly state otherwise, by submitting a contribution to YMTR you
agree to license it under the license that applies to the file(s) you modify:

- MIT for MTR-derived components;
- GPL-3.0-or-later for YMTR-original components and mixed files.

You represent that you have the right to grant these licenses for your
contribution. Do not submit code or assets under a license that is incompatible
with MIT or GPLv3.

## 9. Frequently asked questions

### Can I include YMTR in a modpack?

Yes. Include the unmodified jar, keep the name, logo, and license files intact,
and comply with the GPLv3 (including providing the Corresponding Source for the
GPLv3-covered parts) and the MIT notice requirements. See
[TRADEMARK.md](TRADEMARK.md) before renaming or rebranding.

### Can I fork YMTR?

Yes. Your fork must:

- preserve the MIT notices for MTR-derived components;
- license YMTR-original components and the combined work under GPL-3.0-or-later;
- mark your changes as required by GPLv3 section 5(a);
- provide the Corresponding Source for the GPLv3-covered parts;
- use your own name and logo, or clearly identify the fork as a modified
  version of YMTR (see [TRADEMARK.md](TRADEMARK.md)).

### Can I make a proprietary fork?

No. The GPLv3-covered parts cannot be made proprietary. The MTR-derived parts
remain available under MIT, but the YMTR-original components are GPLv3 and
cannot be distributed as proprietary software.

### Can I relicense YMTR under a different license?

You can reuse the MIT-licensed MTR-derived components under MIT. You cannot
relicense the GPLv3-covered YMTR-original components under a more restrictive
license. If you want to use YMTR-original components in a GPLv3-incompatible
project, contact the copyright holder.

### Does the GPLv3 require me to publish my changes?

If you distribute a modified version, the GPLv3 requires you to provide the
Corresponding Source to recipients. It does not require you to publish changes
you make for private use and do not distribute.

### What about the YMTR name and logo?

The YMTR name and logo are not licensed for trademark use under MIT or GPLv3.
The copyright in the YMTR icon is licensed under GPL-3.0-or-later, but the
licenses do not grant trademark rights. See [TRADEMARK.md](TRADEMARK.md).

## 10. Disclaimer

This document is a good-faith summary of the project's licensing structure. It
is not legal advice. If you need legal advice about your specific use, consult
a qualified lawyer.
