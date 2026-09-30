# Third-Party Notices

Battle Nations Battle Sandbox is licensed under the GNU General Public License,
version 2 (see [LICENSE](LICENSE)). It includes, adapts or is built with the
third-party works listed below. Each is used under its own licence, all of which
are compatible with GPL v2. Their notices are reproduced here as those licences
require.

This project contains no Battle Nations game content. The game files are read
from a copy existing on the user's device.

| Component | Used for | Licence |
|---|---|---|
| [Battle Nations Animation Grabber (BaNG)](#battle-nations-animation-grabber-bang) | Adapted: unit/ability parsing, animation and sprite code | GPL v2 |
| [JOrbis](#jorbis) | Bundled library: Vorbis audio decoding | LGPL 2.1 |
| [JLayer](#jlayer) | Bundled library: MP3 audio decoding | LGPL 2.1 |
| [JAAD](#jaad) | Bundled library: AAC audio decoding | Public domain |
| [JSON-java (org.json)](#json-java-orgjson) | Bundled library: JSON parsing | Public domain |
| [crunch (Unity fork)](#crunch-unity-fork) | Ported: `CrunchDecoder.java` | zlib |
| [python-fsb5](#python-fsb5) | Followed: FSB5 parsing in `FsbAudio.java` | MIT |
| [vgmstream](#vgmstream) | Followed: FSB Vorbis details in `FsbAudio.java` | ISC-style |
| [detex](#detex) | Followed: BC7 partition table layout in `TextureDecoder.java` | ISC |
| [AssetStudio](#assetstudio) | Followed: DXT rounding in `TextureDecoder.java` | MIT |
| [UnityPy](#unitypy) | Data: Unity's common type-tree strings in `UnityBundle.java` | MIT |

---

## Battle Nations Animation Grabber (BaNG)

https://github.com/bobmath/BattleNationsAnimation

Copyright (C) 2014 Robert Mathews

Licensed under the GNU General Public License, version 2 (the same licence as
this project; see [LICENSE](LICENSE)). The files adapted from BaNG carry a notice
at the top saying so; they were modified in 2026, and the git history records
each change and its date.

## JOrbis

http://www.jcraft.com/jorbis/ (Maven: `org.jcraft:jorbis:0.0.17`)

JOrbis is copyrighted by JCraft, Inc.

JOrbis is licensed under the GNU Lesser General Public License, version 2.1;
see [licenses/LGPL-2.1.txt](licenses/LGPL-2.1.txt). It is included unmodified.
Its source is available from the link above and from Maven Central, so it can be
replaced with a modified version.

## JLayer

http://www.javazoom.net/javalayer/javalayer.html (Maven:
`com.googlecode.soundlibs:jlayer:1.0.1.4`)

Copyright (C) JavaZoom

JLayer is licensed under the GNU Lesser General Public License, version 2.1; see
[licenses/LGPL-2.1.txt](licenses/LGPL-2.1.txt). It is included unmodified. Its
source is available from the link above and from Maven Central, so it can be
replaced with a modified version.

## JAAD

Maven: `de.sfuhrm:jaad:0.8.7`

Released into the public domain (as stated in its Maven metadata).

## JSON-java (org.json)

https://github.com/stleary/JSON-java (Maven: `org.json:json`)

Released into the public domain ("Public Domain.", per its LICENSE file).

## crunch (Unity fork)

https://github.com/Unity-Technologies/crunch (branch `unity`)

`src/main/java/hero/roland/bnsim/gamefiles/newformat/CrunchDecoder.java` is an altered
version: a Java port of `crn_decomp.h`, `crn_defs.h` and `crnlib.h`, cut down to
decoding DXT1/DXT5 textures. It is not the original software.

```
crn_decomp.h uses the ZLIB license:
http://opensource.org/licenses/Zlib

Copyright (c) 2010-2016 Richard Geldreich, Jr. and Binomial LLC

This software is provided 'as-is', without any express or implied
warranty.  In no event will the authors be held liable for any damages
arising from the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not
claim that you wrote the original software. If you use this software
in a product, an acknowledgment in the product documentation would be
appreciated but is not required.

2. Altered source versions must be plainly marked as such, and must not be
misrepresented as being the original software.

3. This notice may not be removed or altered from any source distribution.
```

## python-fsb5

https://github.com/HearthSim/python-fsb5

The FSB5 header parsing in `FsbAudio.java` follows python-fsb5's.

```
The MIT License (MIT)

Copyright (c) 2016 Simon Pinfold

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## vgmstream

https://github.com/vgmstream/vgmstream

`FsbAudio.java` relies on facts about FSB Vorbis documented by vgmstream (the
fixed block sizes and how the packet data ends). No vgmstream code or data is
included. The Vorbis setup packets are read at run time from the user's own
game install.

```
Copyright (c) 2008-2025 Adam Gashlin, Fastelbja, Ronny Elfert, bnnm,
                        Christopher Snowhill, NicknineTheEagle, bxaimc,
                        Thealexbarney, CyberBotX, EdnessP, et al

Portions Copyright (c) 2004-2008, Marko Kreen
Portions Copyright 2001-2007  jagarl / Kazunori Ueno <jagarl@creator.club.ne.jp>
Portions Copyright (c) 1998, Justin Frankel/Nullsoft Inc.
Portions Copyright (C) 2006 Nullsoft, Inc.
Portions Copyright (c) 2005-2007 Paul Hsieh
Portions Copyright (C) 2000-2004 Leshade Entis, Entis-soft.
Portions Public Domain originating with Sun Microsystems

Permission to use, copy, modify, and distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## detex

https://github.com/hglm/detex

The packed BC7 three-subset partition table in `TextureDecoder.java` follows
detex's layout.

```
Copyright (c) 2015 Harm Hanemaaijer <fgenfb@yahoo.com>

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## AssetStudio

https://github.com/Perfare/AssetStudio

The DXT1/DXT5 colour rounding in `TextureDecoder.java` matches AssetStudio's.

```
MIT License

Copyright (c) 2016 Radu
Copyright (c) 2016-2020 Perfare

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## UnityPy

https://github.com/K0lb3/UnityPy

The table of Unity's common type-tree strings in `UnityBundle.java` is taken from
UnityPy's copy.

```
MIT License

Copyright (c) 2019-2026 K0lb3

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Trademarks

Battle Nations is (C) Madrona Games, Inc. FMOD is a
trademark of Firelight Technologies Pty Ltd. Unity is a trademark of Unity
Technologies. Vorbis is a trademark of the Xiph.Org Foundation. These names are
used only to identify file formats and sources; this project is not affiliated
with or endorsed by any of these companies.
