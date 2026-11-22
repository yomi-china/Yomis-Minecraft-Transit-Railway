# Yomi's Minecraft Transit Railway (YMTR)

YMTR is an unofficial fork of
[Minecraft Transit Railway](https://github.com/jonafanho/Minecraft-Transit-Railway)
with additional features, fixes, and a web dashboard. It is not affiliated
with or endorsed by the Minecraft Transit Railway project.

> **License:** YMTR is a **mixed-license project**. MTR-derived components are
> licensed under the **MIT License**; YMTR-original components are licensed
> under the **GNU GPL v3.0 or later**; the combined work is distributed under
> **GPL-3.0-or-later** with the MIT notices preserved. See
> [LICENSE](LICENSE) and [LICENSING.md](LICENSING.md).

## License / 许可证

YMTR is a **mixed-license project**, not a dual-licensed project. Different
parts of the project are covered by different licenses:

- **MTR-derived components** (files identical to the upstream Minecraft Transit
  Railway project) are licensed under the **MIT License**.
- **YMTR-original components** (new files created for YMTR) are licensed under
  the **GNU General Public License, version 3 or later (GPL-3.0-or-later)**.
- **YMTR-modified MTR files** are mixed files: the MTR-derived portions remain
  MIT; the YMTR-original modifications are GPL-3.0-or-later; the file as a
  whole is distributed under GPL-3.0-or-later, with the MIT notice retained.
- **The combined YMTR work** is distributed under **GPL-3.0-or-later**, with
  the MIT notices preserved. This does not revoke or reduce the MIT rights in
  the MTR-derived components.

See [LICENSE](LICENSE) for the project-level notice,
[LICENSE-MIT](LICENSE-MIT) and
[LICENSE-GPL-3.0-or-later](LICENSE-GPL-3.0-or-later) for the full license
texts, and [LICENSING.md](LICENSING.md) for the path-by-path mapping.

YMTR 是一个**复合许可项目**，不是二选一的双许可项目。不同组成部分适用不同许可证：

- **MTR 衍生部分**（与上游 Minecraft Transit Railway 项目完全一致的文件）
  采用 **MIT 许可证**。
- **YMTR 原创部分**（为 YMTR 新建的文件）采用 **GNU 通用公共许可证第 3 版
  或其后续版本（GPL-3.0-or-later）**。
- **YMTR 修改过的 MTR 文件**属于混合文件：其中 MTR 衍生部分仍为 MIT，
  YMTR 原创修改部分为 GPL-3.0-or-later；文件整体按 GPL-3.0-or-later 分发，
  并保留 MIT 声明。
- **YMTR 组合作品整体**按 **GPL-3.0-or-later** 分发，同时保留 MIT 声明。
  这不会撤销或减少 MTR 衍生部分已有的 MIT 权利。

完整许可证文本见 [LICENSE-MIT](LICENSE-MIT) 与
[LICENSE-GPL-3.0-or-later](LICENSE-GPL-3.0-or-later)；逐路径说明见
[LICENSING.md](LICENSING.md)。

**The previous "All Rights Reserved" notice (July 2026) is superseded.**
It could not revoke the MIT rights already granted for MTR-derived code. The
current and future releases use the mixed-license scheme above. See
[LICENSING.md](LICENSING.md) for details.

**此前的“保留所有权利”（ARR）声明（2026 年 7 月）已被取代。**
该声明不能撤销 MTR 衍生代码上已经授予的 MIT 权利。当前及后续版本采用上述
复合许可方案。详见 [LICENSING.md](LICENSING.md)。

License and notice files are bundled in the mod jar under
`META-INF/licenses/`.

## Background: the July 2026 attribution incident

In July 2026, a modpack redistributed YMTR with the mod name changed from
"Yomi's Minecraft Transit Railway" to "Minecraft Transit Railway" and the YMTR
icon replaced with the official MTR icon, without mentioning YMTR. The modpack
author acknowledged this. This incident is why the project now has a separate
trademark policy and a mixed license that keeps unmodified MTR-derived code
under MIT while putting YMTR-original code under GPLv3.

<details>
<summary>Full record / 完整记录</summary>

> ## 掠人之美，掩人之善
> ## Usurping Credit, Concealing Merit
>
> 2026 年 7 月，本项目曾因下述事件将许可证紧急改为
> **ARR（All Rights Reserved）**。该变更已被取代，当前项目采用 MIT +
> GPL-3.0-or-later 的复合许可；详见 [LICENSE](LICENSE) 与
> [LICENSING.md](LICENSING.md)。
>
> 近日发现，整合包 **RNA** 的 DLC 包，在分发时，对 YMTR 的 JAR 文件进行了如下篡改：
>
> - 将模组名称 **"Yomi's Minecraft Transit Railway"** 改为 **"Minecraft Transit Railway"**
> - 将模组 LOGO 由 **YMTR 的 LOGO** 替换为 **MTR 官方版的 LOGO**
> - Fabric 与 Forge 版本均遭修改，且整合包中未提及 YMTR 分毫
>
> 此举意在 **掠人之美，掩人之善**——窃取他人成果的表面荣光，刻意抹去真正的贡献来源。整合包用户完全无从知晓自己使用的实为 YMTR。与开源精神背道而驰，亦是对贡献者的侮辱。
>
> 特此公示该整合包的相关链接：
>
> - [RNA 整合包地址](https://modrinth.com/modpack/railway-network-access)
> - [RNA 整合包 MTRv3 DLC 包地址](https://www.curseforge.com/minecraft/modpacks/blockitectures-dlc-pack-for-mtrv3)
> - [该作者 MC百科主页](https://www.mcmod.cn/author/38612.html)
>
> 请各位使用者明辨真伪。
>
> ---
>
> It has recently come to light that the modpack **RNA** DLC pack has been
> distributing YMTR with the following tampering applied to its JAR files:
>
> - The mod name **"Yomi's Minecraft Transit Railway"** was changed to
>   **"Minecraft Transit Railway"**
> - The mod logo was replaced from **YMTR's logo** to **the official MTR logo**
> - Both Fabric and Forge builds were altered, and YMTR was **never mentioned**
>   anywhere in the modpack
>
> This is a textbook case of **"掠人之美，掩人之善"** — usurping the credit of
> another's work while concealing their merits. Users of the modpack have no
> way of knowing that they are, in fact, using YMTR. This runs counter to the
> spirit of open source and is a fundamental insult to contributors.
>
> For the community's awareness, relevant links to the offending content are
> provided below:
>
> - [RNA Modpack](https://modrinth.com/modpack/railway-network-access)
> - [RNA Modpack MTRv3 DLC Pack](https://www.curseforge.com/minecraft/modpacks/blockitectures-dlc-pack-for-mtrv3)
> - [Author's Profile](https://www.mcmod.cn/author/38612.html)

</details>

## About Minecraft Transit Railway

_Minecraft Transit Railway_ is a [Minecraft mod](https://minecraft.gamepedia.com/Mods)
based on Hong Kong's MTR, the London Underground, and the New York Subway. It
adds trains into the game along with other miscellaneous blocks and items.
With this mod, it is possible to build a fully functional railway system in
your world. YMTR builds on that foundation.

## Downloads and Installation

See the YMTR repository for downloads:
<https://github.com/yomi-china/Yomis-Minecraft-Transit-Railway>

Do not download YMTR from unofficial reuploads that have removed the YMTR
name, logo, or license notices. See [TRADEMARK.md](TRADEMARK.md).

## Guide

- [MTR wiki](https://github.com/jonafanho/Minecraft-Transit-Railway/wiki) for
  the base mechanics.
- `docs/` and [`自定义轨道数据.md`](自定义轨道数据.md) for YMTR-specific
  features.

## Contributing

Contributions are welcome via the YMTR repository:
<https://github.com/yomi-china/Yomis-Minecraft-Transit-Railway>

By contributing, you agree that your contributions are licensed under the
license that applies to the files you modify: MIT for MTR-derived components,
GPL-3.0-or-later for YMTR-original components and mixed files. See
[LICENSING.md](LICENSING.md).

### Help Translate the Mod

Translation contributions are welcome. Please keep the existing license
notices and submit translations through the YMTR repository.

## Building

To build YMTR, run the following command in the root directory of the project:

```
gradlew build -PbuildVersion=<minecraft version>
```

The mod jar is generated in:

```
<root>/build/release/MTR-<fabric|forge>-<minecraft version>-<mod version>.jar
```

License and notice files are bundled in the jar under `META-INF/licenses/`.

## Credits

- **Minecraft Transit Railway**: Jonathan Ho and contributors.
  <https://github.com/jonafanho/Minecraft-Transit-Railway>
- **YMTR**: Yomi_307 and contributors.
  <https://github.com/yomi-china/Yomis-Minecraft-Transit-Railway>
- **Third-party components**: see [NOTICE](NOTICE).

### Minecraft Transit Railway links

- [GitHub](https://github.com/jonafanho/Minecraft-Transit-Railway)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/minecraft-transit-railway)
- [Wiki](https://github.com/jonafanho/Minecraft-Transit-Railway/wiki)
- [Discord](https://discord.gg/PVZ2nfUaTW)

## License details

The full license texts are:

- [LICENSE-MIT](LICENSE-MIT) — MIT License for MTR-derived components.
- [LICENSE-GPL-3.0-or-later](LICENSE-GPL-3.0-or-later) — GNU GPL v3.0 or later
  for YMTR-original components and the combined work.

The Noto fonts bundled with the mod are licensed under the SIL Open Font
License 1.1. Third-party libraries remain under their own licenses; see
[NOTICE](NOTICE).

SPDX-License-Identifier: `MIT AND GPL-3.0-or-later`
