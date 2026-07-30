# Third-party notices

PiperOS Tool uses open-source Android libraries, including AndroidX,
Material Components, Firebase Android SDK, Glide and AndroidX Media3. Their
licenses and copyright notices remain applicable to those components.

PiperOS Fake Map GPS uses osmdroid (Apache License 2.0), OpenStreetMap map
data and the OSRM routing API. OpenStreetMap attribution is displayed directly
on the map. OpenStreetMap data is available under ODbL; OSRM is distributed
under the BSD 2-Clause license.

The bundled **Silkscreen** and **VT323** fonts are licensed under the SIL Open
Font License 1.1. Their license texts are stored in:

- `app/src/main/assets/licenses/silkscreen_ofl.txt`
- `app/src/main/assets/licenses/vt323_ofl.txt`

The full Linux terminal runtime is maintained separately at
[Piperos_termux](https://github.com/Phi574/Piperos_termux). Termux-specific
application code is generally GPL-3.0-only, while `terminal-emulator` and
`terminal-view` include code under Apache License 2.0. Upstream file-level
license exceptions must be preserved when that runtime is integrated.
