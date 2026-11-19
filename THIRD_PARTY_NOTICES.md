# Acknowledgements

The native app-window perspective projection adapts `AppWindowTilt.kt`
from [CPI Kostubet Edition](https://github.com/Kostubet/CPI-Kostubet-Edition/blob/3545c0bbd0ef67973a3c0c5bbacd1f88d84eb5e7/app/src/main/java/com/cosmod/launcherglass/xposed/AppWindowTilt.kt),
licensed under GPL-3.0. CherryPick uses its own positional curve and resolves the ColorOS transition contract for each supported launcher.

The Kyant lens adapts the edge refraction and directional highlight from
[AndroidLiquidGlass 2.0.1](https://github.com/Kyant0/AndroidLiquidGlass/tree/2.0.1).
Copyright 2025 Kyant. Licensed under Apache-2.0. CherryPick applies these
effects to its own backdrop, silhouette mask and frame scheduling.
The license is packaged with the app in `assets/licenses/AndroidLiquidGlass.txt`.
The draggable glass selection in CherryPick's tabs and segmented controls follows
the `LiquidBottomTabs` sample from the same release, adapted for Android Views.

The COUI settings theme and switch adapt the Apache-2.0 COUI 1.1.0
`Colors.kt`, `Switch.kt`, `Slider.kt` and card defaults vendored by
[ONextBox](https://github.com/MiToverG422/ONextBox/tree/d214396734f56e7ee367abb7d528fd190357e109/third_party/coui).
CherryPick renders the adapted components using Android Views. The license
is packaged in `assets/licenses/COUI.txt`.

PixelLauncherEnhanced by Mahmud0808: https://github.com/Mahmud0808/PixelLauncherEnhanced

Thank you to PixelLauncherEnhanced / Mahmud0808 for permission to use the files.

The ClipIconView floating-background integration was adapted from the FloatingGlassBackground approach in IconGlass.kt (GPL-3.0). CherryPick uses its own material renderer and ColorOS-specific source tracking. A copy of the upstream file, its license and source revision are in third_party/PixelLauncherEnhanced. The full local reference checkout is excluded from source distribution.

Thanks to @anime_killer2009 for substantial help: https://t.me/anime_killer2009

Thanks to Kostubet Topics for their help: https://t.me/KostubetTopics

Continuous corner geometry is adapted from [figma-squircle](https://github.com/phamfoo/figma-squircle), copyright 2021 Tien Pham, MIT License. The license is bundled at `app/src/main/assets/licenses/figma-squircle.txt`.
