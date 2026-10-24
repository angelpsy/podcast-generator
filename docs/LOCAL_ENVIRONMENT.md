# Локальная среда React Native / Android

Дата настройки: 24 октября 2026 года. Mac: Apple Silicon (`arm64`), macOS 26.6.2. Свободное место перед настройкой: около 248 GiB.

Стек изменён на React Native + TypeScript. Общие Android-инструменты, NDK/CMake и npm-зависимости React Native установлены; проверки инструментов прошли успешно. После подготовки среды создан первый прототип в `mobile/`; актуальная сборка и приёмка описаны в [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md). Вход SIWC на телефоне ещё не выполнялся. Проверки предыдущей Kotlin-конфигурации не считаются проверками сборки React Native.

## Установлено и проверено

| Компонент | Версия / расположение | Результат |
| --- | --- | --- |
| Node.js / npm | 24.16.0 / 11.13.0, существующий nvm | Проверены; Node зафиксирован в .nvmrc |
| NDK / CMake | 27.1.12297006 / 3.22.1 | Установлены; CMake и NDK Clang запускаются |
| Homebrew, был установлен ранее | `/Users/first/.homebrew` | Использован для двух casks |
| Temurin JDK, был установлен ранее | 17.0.20.1+1 | `java -version` успешно |
| Android Studio Apple Silicon | Homebrew cask `2026.1.4.7,quail4`; app plist сообщает `2026.1` | Установлен в `/Applications/Android Studio.app`; мастер первого запуска ещё не пройден |
| Android Command-line Tools | 22.0, build 15859902 | `sdkmanager --version` успешно |
| SDK Platform | Android 36, revision 2 | `android.jar` установлен |
| SDK Build Tools | 36.0.0 | `aapt2 version` успешно, universal arm64/x86_64 |
| Platform Tools | 37.0.1, ADB protocol version 1.0.41 | ADB daemon запускается; устройств в списке нет |
| Gradle RN-шаблона | 9.3.1, .tooling/gradle-9.3.1 | SHA-256 совпала; запуск на Java 17 успешен |
| Gradle предыдущего этапа | 8.13 | Запуск на JDK 17 успешен; SHA-256 официального дистрибутива проверен |
| AGP / Kotlin предыдущего этапа | 8.13.2 / 2.2.21 | Загружены; временная конфигурация зависимостей отработала |
| Библиотеки предыдущего этапа | Прежние standalone-версии; 36 прямых и транзитивных артефактов | Скачаны в Gradle cache; `checkDebugAarMetadata` и `prefetchLibraries` успешны |

SDK: `/Users/first/Library/Android/sdk`. Command-line Tools от Homebrew находятся отдельно: `/Users/first/.homebrew/share/android-commandlinetools/cmdline-tools/latest`; команды доступны в `/Users/first/.homebrew/bin`. Явный `--sdk_root` и `ANDROID_HOME` указывают им нужный SDK.

JDK: `/Users/first/Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home`. Текущий локальный Gradle: `.tooling/gradle-9.3.1`; `.tooling/gradle-8.13` — кэш предыдущего этапа. Библиотеки и плагины: стандартный кэш `/Users/first/.gradle/caches`. В `gradle --version` встроенный Kotlin относится к самому Gradle, а не к Android Kotlin plugin. Эта standalone-база больше не используется для приложения React Native.

Стандартные SDK-лицензии приняты при установке. На первом этапе эмулятор, system images, NDK, CMake и отдельный USB-драйвер не устанавливались. Для React Native дополнительно установлены NDK и CMake; эмулятор по-прежнему не нужен. Watchman опционален и пока не установлен: используется Node watcher. Rosetta для проверенного `aapt2` не нужна. Профили shell пользователя не менялись; окружение задаётся через `source tools/android-env.sh`.

## Выполненные проверки и границы

- `bash tools/android-doctor.sh` завершился с кодом 0. `adb devices -l` вернул пустой список: подключение Pixel ещё предстоит.
- Прежний standalone prefetch (скрипт теперь удалён): `BUILD SUCCESSFUL`, 36 артефактов, проверка Android AAR metadata прошла для SDK 36. Тогда был использован временный library-проект без исходников; Android-приложение не создавалось.
- Проверены синтаксис трёх shell-скриптов и относительные ссылки документации.
- После реализации успешно выполнены Codegen, компиляция Kotlin/JNI, JVM unit tests, Android lint и сборки debug/release через проектный Wrapper. Запуск IDE и сквозной тест на телефоне ещё не выполнены; см. [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).
- Известные предупреждения инструментов: `sdkmanager` помечен deprecated в пользу Android CLI, но выполняется успешно; AGP сообщает о новой версии SDK XML и о deprecated Gradle features. Эти проверки завершились успешно. Это предупреждения предыдущего standalone-этапа. Для React Native используется Wrapper его шаблона, а не Gradle 8.13.

Локальные логи (игнорируются Git): `.tooling/android-doctor.log`, `.tooling/dependencies.log`, `.tooling/sdk-install.log`, `.tooling/sdk-licenses.log`. Они относятся только к установке, пользовательских OAuth-данных в них нет.

## База React Native и текущая подготовка

React Native 0.86.3, React 19.2.3, Community CLI 20.1.0. Опубликованный npm-шаблон `@react-native-community/template@0.86.3` проверен: compile/target SDK 36, Build Tools 36.0.0, NDK 27.1.12297006, Kotlin 2.1.20, Wrapper Gradle 9.3.1. AGP задаёт RNGP; catalog установленного `@react-native/gradle-plugin` подтверждает AGP 8.12.0 и Kotlin 2.1.20. При реализации сверить фактический dependency graph и не смешивать его с прежним Gradle 8.13/AGP 8.13.2.

SDK 37 из первоначально рассмотренного шаблона 0.87 текущий SDK Manager не нашёл; выбран поддерживаемый RN 0.86 с доступным SDK 36. Источники и архитектура — в [спецификации](ANDROID_SIWC_SPEC.md).

Node.js доступен в `/Users/first/.nvm/versions/node/v24.16.0/bin/node`. `source tools/android-env.sh` выбирает этот Node вместе с SDK и JDK в текущем shell. Профили пользователя не меняются.

`tools/prefetch-react-native.sh` подготавливает зависимости опубликованного RN-шаблона в игнорируемой `.tooling/react-native-dependencies/` с локальным package-lock, без install scripts. Это вспомогательный кэш, а не приложение. Подготовка завершена: установлены 866 npm-пакетов без install scripts. Проверены React Native 0.86.3, React 19.2.3, TypeScript 5.9.3, Metro 0.84.6 и запуск CLI 20.1.0. npm сообщил о deprecated транзитивных dev-зависимостях шаблона; полная проверка приложения пока не выполнялась. В `mobile/` уже есть собственный npm lockfile; установка зависимостей, Codegen и Android-сборка выполнены.

Обновлённый `tools/android-doctor.sh` завершился с кодом 0: проверены Node/npm, JDK, SDK Manager, CMake 3.22.1, NDK Clang 18.0.2, Gradle 9.3.1, ADB и aapt2. Clang из host-каталога darwin-x86_64 успешно запускается на этом Mac. Список устройств ADB по-прежнему пуст. Полная Android-сборка RN и Codegen теперь выполнены успешно через `mobile/android/gradlew`. AGP дополнительно установил Build Tools 35.0.0 для зависимого Android library-модуля; приложение использует 36.0.0. Наличие RN debug APK не обеспечивает запуск без Mac: для приёмки нужна сборка со встроенным JS bundle.

```sh
cd /Users/first/Documents/projects/pet/podcast-generator
source tools/android-env.sh
bash tools/android-doctor.sh
bash tools/prefetch-react-native.sh
```

Восстановление дополнительных SDK-компонентов:

```sh
sdkmanager --sdk_root="$ANDROID_HOME" 'ndk;27.1.12297006' 'cmake;3.22.1'
```

Логи RN-подготовки: `.tooling/react-native-sdk-install.log`, `.tooling/react-native-dependencies.log`; итоговая проверка — `.tooling/android-doctor.log`.

Инструкция: [ANDROID_DEVICE_TESTING.md](ANDROID_DEVICE_TESTING.md). Спецификация: [ANDROID_SIWC_SPEC.md](ANDROID_SIWC_SPEC.md).
