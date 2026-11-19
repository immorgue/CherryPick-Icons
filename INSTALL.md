# Установка / Installation

[Русский](#русский) · [English](#english)

## Русский

### Перед установкой

Понадобятся рут-права, LSPosed с поддержкой libxposed API 102 и системный лаунчер с оформлением иконок Flux.
Другие икон паки сейчас не поддерживаются.

Бета проверена на устройстве с лаунчером 17.5.4. Классы и ресурсы также сверены в локальных APK лаунчеров 16.x и 17.x.
Анимации приложений выключены по умолчанию и пока не тестировались на ColorOS 16.

### Пошаговая установка

1. Скачайте APK из [релизов CherryPick Icons](https://github.com/immorgue/CherryPick-Icons/releases) и установите его.
2. Откройте CherryPick Icons. Когда менеджер рут-доступа запросит разрешение, предоставьте его приложению. Если ранее отказали, откройте свой менеджер рут-доступа и разрешите доступ для CherryPick Icons.
3. Откройте LSPosed, найдите CherryPick Icons в списке модулей и включите его.
4. В области действия модуля выберите установленный системный лаунчер и CherryPick Icons. Нужен именно лаунчер, которым вы пользуетесь.
5. В системных настройках оформления иконок выберите **Flux**.
6. Вернитесь в CherryPick Icons и включите основной переключатель модуля. На новой установке уже выбраны Auto Tinted для иконок и Auto для дока.
7. Нажмите кнопку перезапуска лаунчера на главном экране CherryPick Icons. Дождитесь возвращения рабочего стола.

### Если что-то работает неправильно

1. Проверьте, что CherryPick Icons получил рут-доступ в вашем менеджере рут-прав.
2. Проверьте включение модуля и правильный лаунчер в области действия LSPosed.
3. Убедитесь, что выбран Flux и включён основной переключатель CherryPick Icons.
4. Перезапустите лаунчер кнопкой внутри CherryPick Icons после изменения области действия или настроек.
5. Если установили обновление поверх старой сборки, проверьте режим размытия в настройках. Live обновляет материал во время движения; сохранённый выбор Static при обновлении остаётся прежним.

Если проблема сохраняется, [сообщите о ней в Kostubet Topics](https://t.me/KostubetTopics).
Укажите модель телефона, версию прошивки, версию системного лаунчера и CherryPick Icons,
а также шаги для повторения проблемы. По возможности приложите запись экрана и диагностический лог из настроек модуля.

## English

### Before installation

You need root access, LSPosed with libxposed API 102 support, and the system launcher with the Flux icon style.
Other icon packs are not currently supported.

This beta was tested on a device running launcher 17.5.4. Classes and resources were also verified in local 16.x and 17.x launcher APKs.
App animations are off by default and haven’t been tested on ColorOS 16 yet.

### Step-by-step installation

1. Download the APK from [CherryPick Icons releases](https://github.com/immorgue/CherryPick-Icons/releases) and install it.
2. Open CherryPick Icons. Grant the app root access when your root manager asks. If you denied it previously, open your root manager and allow root access for CherryPick Icons.
3. Open LSPosed, find CherryPick Icons in the module list and enable it.
4. Select your installed system launcher and CherryPick Icons in the module scope. Select the launcher you actually use.
5. Choose **Flux** in the system icon style settings.
6. Return to CherryPick Icons and enable the main module switch. New installations start with Auto Tinted for icons and Auto for the dock.
7. Use the launcher restart button on the CherryPick Icons home screen. Wait for the home screen to return.

### If something does not work correctly

1. Check that your root manager has granted root access to CherryPick Icons.
2. Check that the module is enabled and the correct launcher is selected in LSPosed.
3. Make sure Flux is selected and the main CherryPick Icons switch is enabled.
4. Restart the launcher using the button inside CherryPick Icons after changing the scope or settings.
5. If you updated an older build, check the blur mode in settings. Live updates the material during motion; an explicitly saved Static selection is preserved during updates.

If the issue persists, [report it in Kostubet Topics](https://t.me/KostubetTopics).
Include your phone model, firmware version, system launcher version, CherryPick Icons version
and steps to reproduce the issue. If possible, attach a screen recording and a diagnostic log from the module settings.
