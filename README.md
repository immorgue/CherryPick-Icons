# CherryPick Icons

**v0.3.241 Beta** · [Релизы / releases](https://github.com/immorgue/CherryPick-Icons/releases) · [Ченжлог / changelog](CHANGELOG.md) · [Совместимость / compatibility](COMPATIBILITY.md)

Для этой версии нужен LSPosed с поддержкой libxposed API 102. После обновления перезапустите лаунчер.

This version requires LSPosed with libxposed API 102 support. Restart the launcher after updating.

[Русский](#русский) · [English](#english)

## Русский

Модуль LSPosed для стеклянного оформления системного лаунчера ColorOS, OxygenOS и realme UI.

### Как создавался проект

Проект был полностью навайбкожен с помощью **GPT 6.1 Sol**.
Участие автора проекта - воплощение идеи, создание логики работы и интерфейса.

### Возможности

- Автооформление иконок с режимами Default, Dark, Clear и Tinted. Оттенок можно подобрать по обоям или вручную через Customize в режиме редактирования рабочего стола.
- Liquid Glass на базе Kyant, матовое стекло Frosted и режим «Только цвет». У дока, папок и мультипапок свои настройки.
- Стекло для совместимых виджетов фото, часов и погоды. Живое и статичное размытие обоев.
- Интерфейс COUI на русском и английском с предпросмотрами, профилями обоев, импортом и экспортом настроек.
- Конструктор рабочего стола с предпросмотром сетки, размерами и отступами. Изменение сетки может переставить элементы.
- Опциональные анимации приложений в стиле iOS и экспериментальный музыкальный островок.

### Совместимость и установка

Бета проверена на устройстве с лаунчером 17.5.4. Для локальных APK лаунчеров 16.x и 17.x сверены классы и ресурсы. Анимации приложений выключены по умолчанию и пока не тестировались на ColorOS 16.

1. Установите APK, выдайте рут и включите CherryPick Icons в LSPosed.
2. В области действия выберите установленный системный лаунчер и CherryPick Icons.
3. Выберите Flux в системных настройках иконок.
4. Включите основной тумблер CherryPick и перезапустите лаунчер. Для экспериментального островка нужен System UI в области действия LSPosed.

[Подробный гайд по установке](INSTALL.md#русский)

### Сборка

Нужны JDK 17 и Android SDK с платформой API 35. Укажите путь к SDK в локальном
`local.properties` (`sdk.dir=...`) или через `ANDROID_HOME`.

```powershell
.\gradlew.bat assembleRelease lintRelease
```

Linux / macOS:

```sh
./gradlew assembleRelease lintRelease
```

APK появится в `app/build/outputs/apk/release/app-release.apk`.
Первой сборке нужен интернет для загрузки Gradle и зависимостей.

Ключи подписи в репозиторий не входят. Если есть локальный `app/debug.keystore`,
сборка использует его для совместимости с существующими тестовыми APK.
Иначе Gradle создаёт обычный локальный debug-ключ. Такие сборки предназначены
для разработки; для распространения нужно отдельно настроить постоянную подпись.
Обновление поверх установленного APK требует того же сертификата.

### Исходники

\- `app/src/main/java/com/cosmod/launcherglass/ui` - настройки и обмен с лаунчером.<br>
\- `app/src/main/java/com/cosmod/launcherglass/xposed` - хуки и материалы иконок, папок и фото.<br>
\- `app/src/main/java/com/cosmod/launcherglass/util` - получение и обработка обоев, диагностика.<br>
\- [ARCHITECTURE.md](ARCHITECTURE.md) - устройство рендера и история изменений.

Исторические ссылки `../scratch/...` в архитектурных заметках относятся к локальным
проверкам разработчика и не входят в публичные исходники.

### Благодарности и лицензия

Спасибо [PixelLauncherEnhanced](https://github.com/Mahmud0808/PixelLauncherEnhanced) / Mahmud0808 за разрешение на использование файлов.
Спасибо [@anime_killer2009](https://t.me/anime_killer2009) и [Kostubet Topics](https://t.me/KostubetTopics) за помощь.
Подробности заимствований: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Лицензия: [GNU GPL v3](COPYING).

### Поддержать проект

[Поддержать разработку через CloudTips](https://pay.cloudtips.ru/p/e6230fb4).

## English

An LSPosed module for glass effects in the ColorOS, OxygenOS, and realme UI system launchers.

### How this project was made

This project was entirely vibecoded with **GPT 6.1 Sol**.
The project author's contribution was bringing the idea to life, defining the product logic,
and creating the interface.

### Features

- Auto icon styling with Default, Dark, Clear, and Tinted. The Customize menu in home screen edit mode lets you use a wallpaper-based tint or pick one manually.
- Kyant-based Liquid Glass, Frosted, and Color Only. The dock, folders, and large folders have their own settings.
- Glass for compatible photo, clock, and weather widgets. Live and static wallpaper blur.
- COUI interface in English and Russian with previews, wallpaper profiles, and settings import and export.
- Desktop designer with a grid preview, item sizing, and spacing controls. Changing the grid can rearrange items.
- Optional iOS-style app animations and an experimental music island.

### Compatibility and installation

This beta was tested on a device running launcher 17.5.4. Classes and resources were verified in local 16.x and 17.x launcher APKs. App animations are off by default and haven’t been tested on ColorOS 16 yet.

1. Install the APK, grant root access and enable CherryPick Icons in LSPosed.
2. Select the installed system launcher and CherryPick Icons in the module scope.
3. Select Flux in the system icon settings.
4. Turn on CherryPick’s main switch and restart the launcher. The experimental music island requires System UI in the LSPosed scope.

[Detailed installation guide](INSTALL.md#english)

### Building

Use JDK 17 and Android SDK platform API 35. Set the SDK location in your local
`local.properties` (`sdk.dir=...`) or through `ANDROID_HOME`.

```powershell
.\gradlew.bat assembleRelease lintRelease
```

Linux / macOS:

```sh
./gradlew assembleRelease lintRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.
The first build needs an internet connection to download Gradle and dependencies.

Signing keys are excluded from this repository. If a local `app/debug.keystore` exists,
the build uses it to remain compatible with the existing test APKs. Otherwise, Gradle
generates a standard local debug key. These builds are intended for development;
distribution requires a separately configured, permanent signing identity.
Updating an installed APK requires the same signing certificate.

### Source layout

\- `app/src/main/java/com/cosmod/launcherglass/ui` - settings and launcher communication.<br>
\- `app/src/main/java/com/cosmod/launcherglass/xposed` - hooks and icon, folder, and photo materials.<br>
\- `app/src/main/java/com/cosmod/launcherglass/util` - wallpaper capture and processing, diagnostics.<br>
\- [ARCHITECTURE.md](ARCHITECTURE.md) - rendering architecture and change history.

Historical `../scratch/...` links in the architecture notes refer to local developer
validation artifacts and are not included in the published source.

### Credits and license

Thanks to [PixelLauncherEnhanced](https://github.com/Mahmud0808/PixelLauncherEnhanced) / Mahmud0808 for permission to use the files.
Thanks to [@anime_killer2009](https://t.me/anime_killer2009) and [Kostubet Topics](https://t.me/KostubetTopics) for their help.
iOS animation physics author: [@bazik1337](https://t.me/bazik1337).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for adaptation details.
License: [GNU GPL v3](COPYING).

### Support the project

[Support development through CloudTips](https://pay.cloudtips.ru/p/e6230fb4).
