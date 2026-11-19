# API 102 migration and module sections

## Runtime

CherryPick now declares a modern `XposedModule` entry in
`META-INF/xposed/java_init.list`, targeting libxposed API 102. The legacy manifest
metadata and `assets/xposed_init` are removed. A framework providing API 102 is
required; Android API levels and launcher version detection are unchanged.

`bridge/HookRuntime` adapts existing before/after rendering callbacks to the modern
interceptor chain. It preserves argument mutation, result replacement, callback
extras, priority, native exceptions and after-hook recovery. Reflective OEM access
is implemented by our own cached helper; no `de.robv.android.xposed` API remains.
The compile-only API stub is documented in `app/libs/README.md`.

`ModernEntry.onHotReloading` deliberately returns false. The current renderer
retains View callbacks, RenderNodes, wallpaper factory subscriptions and worker
executors. API 102 requires complete generation teardown before approving reload.
Enabling autoHotReload without that work would risk duplicate callbacks and stale
classloaders. Updating this build still requires restarting launcher/SystemUI.

Official contracts:
- https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html
- https://libxposed.github.io/api/io/github/libxposed/api/XposedModuleInterface.html

## Independent sections

- `feature_icons`: icon appearance hooks; disabled callbacks run the stock method.
- `feature_desktop`: gates dock, folder material, photo widgets, iOS tilt and layout.
- `feature_layout`: gates native desktop geometry without deleting saved dimensions.

The global module switch still stops everything. To keep only iOS animations,
leave Desktop enabled, turn Icons/layout/dock/widgets/folder material off, and keep
iOS animations on. Group switches preserve child preference values.

## Settings UI

Module contains Icons, Desktop, Appearance and Other. Icons includes a pinned
preview and Auto and Custom appearance controls. Dock, folders and widgets have
their own previews. The launcher's Customize menu changes the Auto appearance.
The in-app previews share the production Kyant shader, optics, glyph processing
and tone helpers. The launcher supplies the native folder geometry and icon assets
on the home screen.

Wallpaper profiles automatically save edits and remain appearance-only; feature
groups, layout, developer settings and animation physics never move between
wallpapers. Export uses Android's share sheet. Import opens the Android chooser
for file sources and also accepts JSON shared from another app.

## Validation

Host tests cover the hook adapter's before/after semantics, failure recovery,
reflection overloads and primitive widening in addition to existing motion,
geometry, settings and profile coverage. Device validation must additionally
verify launcher and SystemUI injection, settings preview and independent switches.
No alternate launcher APK is installed for compatibility checking.
