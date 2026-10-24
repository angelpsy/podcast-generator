# React Native: разработка и тестирование на Pixel с MacBook

Дата: 24 октября 2026 года. Проект: `/Users/first/Documents/projects/pet/podcast-generator`.

React Native-прототип находится в `mobile/`, Android-часть и `gradlew` — в `mobile/android/`. Статус фактической сборки и проверок: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

## 1. Инструменты и настройка IDE

Точный результат установки и версии см. в [LOCAL_ENVIRONMENT.md](LOCAL_ENVIRONMENT.md).

Используем Android Studio для Apple Silicon, существующий Temurin JDK 17, Android SDK Platform 36, Build Tools 36.0.0, Platform Tools (`adb`), Command-line Tools (`sdkmanager`), NDK 27.1.12297006 и CMake 3.22.1. Для React Native также нужны Node.js 24.16.0 и npm, уже имеющиеся через nvm. Эмулятор и system images не нужны. Watchman опционален; первый прототип может использовать Node watcher.

Открыть IDE:

```sh
open -a 'Android Studio'
```

При первом запуске пройти Welcome/Setup Wizard. Если предлагается скачивание дополнительных компонентов, выбрать Custom: SDK path `/Users/first/Library/Android/sdk`, без Android Emulator и виртуальных устройств. Уже установленные SDK повторно скачивать не нужно. Если macOS попросит разрешить доступ к USB-аксессуару — разрешить подключённый Pixel.

После создания React Native-проекта открыть папку `podcast-generator/mobile/android` через Open. Не создавать второй проект мастером поверх него. Дождаться Gradle Sync.

В Settings → Build, Execution, Deployment → Build Tools → Gradle:

- Distribution: Wrapper из проекта.
- Gradle JDK: `/Users/first/Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home`.

SDK path проверяется в SDK Manager. Android Studio работает на своём bundled JBR; его не требуется заменять. JDK 17 нужен именно Gradle этой спецификации. Gradle/AGP/Kotlin брать из выбранного RN-шаблона 0.86.3; старую standalone-конфигурацию Gradle 8.13 не переносить. Предложения IDE автоматически обновить AGP/Kotlin пока отклонять. Если IDE не находит Node из nvm, открыть из настроенного терминала через `studio mobile/android`; при необходимости зафиксировать абсолютный путь Node в локальной конфигурации RNGP, без изменения глобального shell.

Терминал — для каждой новой вкладки:

```sh
cd /Users/first/Documents/projects/pet/podcast-generator
source tools/android-env.sh
bash tools/android-doctor.sh
```

Скрипт окружения не меняет `.zshrc` и действует только в текущем shell. Настройки терминала не заменяют Gradle JDK в IDE. Не задавать одновременно конфликтующие `ANDROID_HOME` и устаревший `ANDROID_SDK_ROOT`.

Если потребуется повторить установку инструментов на этом Mac:

```sh
HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_CLEANUP=1 brew install --cask android-studio android-commandlinetools
source tools/android-env.sh
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.1.12297006' 'cmake;3.22.1'
```

Если `sdkmanager` ещё не в PATH, использовать `/Users/first/.homebrew/bin/sdkmanager`. Command-line Tools установлен Homebrew отдельно от каталога SDK; это ожидаемо. Точные пути после установки приведены в отчёте окружения.

## 2. Первое подключение Pixel по USB

1. На Pixel: Настройки → О телефоне → **Номер сборки**, нажать 7 раз, подтвердить PIN.
2. Настройки → Система → Для разработчиков → включить **Отладка по USB**. Названия пунктов могут немного различаться по версии Android.
3. Соединить Pixel с Mac USB-C кабелем с передачей данных. Разблокировать телефон.
4. Если macOS показывает запрос подключения аксессуара — разрешить.
5. На телефоне подтвердить **Разрешить отладку по USB?** с RSA fingerprint этого Mac. Для своего Mac можно выбрать «Всегда разрешать».
6. Проверить:

```sh
adb devices -l
```

Ожидается одна строка с состоянием `device`. На macOS отдельный USB-драйвер для Pixel не требуется. Режим «Передача файлов» обычно не обязателен; при проблемах можно переключить USB-режим и проверить ещё раз. [Официальная инструкция](https://developer.android.com/studio/run/device).

При нескольких подключениях, включая USB и Wi‑Fi одного телефона, выбрать один serial из `adb devices -l`:

```sh
export ANDROID_SERIAL='SERIAL_ИЗ_ADB_DEVICES'
adb get-state
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

`ANDROID_SERIAL` — только выбор ADB-транспорта. Не копировать его в публичный отчёт. При переключении с USB на Wi‑Fi обновить переменную или выполнить `unset ANDROID_SERIAL`.

## 3. Первый запуск React Native по USB

С одним подключённым Pixel:

1. Терминал 1: запустить Metro из корня JS-проекта:

```sh
cd /Users/first/Documents/projects/pet/podcast-generator
source tools/android-env.sh
cd mobile
npm ci
npm start -- --host 127.0.0.1
```

`npm ci` нужен при первом запуске и изменении lockfile, а не после каждого изменения JS. Не запускать проект в Expo Go: собственный TurboModule должен быть встроен в APK.

2. Терминал 2: пробросить порт Metro и собрать/запустить debug-приложение:

```sh
cd /Users/first/Documents/projects/pet/podcast-generator
source tools/android-env.sh
adb reverse tcp:8081 tcp:8081
cd mobile
npm run android -- --no-packager
```

3. На телефоне пройти Continue with ChatGPT в браузере телефона, вернуться в приложение, выбрать модель и отправить запрос.
4. Правки `.tsx` обычно видны через Fast Refresh без установки нового APK. Для изменений Kotlin, Codegen, Gradle и нативных зависимостей заново собрать и установить APK.

Через Android Studio: открыть `mobile/android`, выбрать `app` и Pixel, нажать Run. Metro должен уже работать; `adb reverse` также должен быть настроен. Нативные breakpoint — в IDE, TypeScript/React — в React Native DevTools (клавиша `j` в терминале Metro при поддерживаемом подключении).

Для RN debug-приложения Mac обслуживает JavaScript bundle через Metro. Он не выполняет OAuth и не пересылает OpenAI-запросы. Для проверки независимости от компьютера нужна сборка со встроенным bundle, описанная ниже. [Запуск на устройстве](https://reactnative.dev/docs/running-on-device).

## 4. Повторяемый цикл и диагностика

Из корня `mobile/`:

```sh
npm test -- --runInBand
npm run lint
npx tsc --noEmit
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n dev.podcast.siwcspike/.MainActivity
```

Код и тесты уже находятся в `mobile/`. Устанавливать APK только после успешной сборки. При нескольких подключениях выбрать `ANDROID_SERIAL`; команды Gradle + `adb install` удобнее автоматического выбора устройства CLI.

Повседневно сохранять данные приложения. Обновление с тем же application ID и ключом сохраняет сессию; очистка/переустановка с удалением данных создаёт новую регистрацию. В ReactNative UI Fast Refresh и Reload должны переподключаться к native snapshot, не создавать новый OAuth автоматически.

Логи Android:

```sh
adb logcat -v time 'SiwcSpike:I' 'AndroidRuntime:E' '*:S'
```

JS-ошибки смотреть в Metro/DevTools. Токены и callback query не должны попадать ни в Logcat, ни в JS console. Не включать HTTP BODY logging.

Холодный запуск без удаления сессии:

```sh
adb shell am force-stop dev.podcast.siwcspike
adb shell am start -W -n dev.podcast.siwcspike/.MainActivity
```

Очистка данных только для явного теста новой установки. Сначала выйти или отозвать доступ в ChatGPT; затем при необходимости `adb shell pm clear dev.podcast.siwcspike`. Очистка сама по себе не отзывает удалённый доступ.

### Сборка для теста без Metro/Mac

Release-сборка уже настроена с embedded JS bundle, `debuggable=false` и подписью тем же стандартным локальным debug-ключом машины (не для публикации). RNGP должен выполнять bundle task для release, а не включать этот вариант в `debuggableVariants`. После этого:

```sh
cd /Users/first/Documents/projects/pet/podcast-generator/mobile/android
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n dev.podcast.siwcspike/.MainActivity
```

Остановить Metro, отключить кабель/ADB и оставить интернет телефона. Проверить новый запуск и запрос. Если подпись отличается, обновление поверх debug не получится; не удалять данные вслепую. Стандартный debug-ключ находится вне репозитория, в пользовательском Android-каталоге.

## 5. Wi‑Fi для ежедневной разработки

Первый сквозной тест удобнее провести по USB. Далее кабель не нужен: Pixel и Mac должны быть в одной сети, позволяющей устройствам обращаться друг к другу.

Через IDE:

1. Pixel: Для разработчиков → **Беспроводная отладка**, включить.
2. Android Studio: список устройств → **Pair Devices Using Wi‑Fi**.
3. Выбрать QR code, на Pixel — сопряжение по QR-коду и отсканировать; либо воспользоваться pairing code.
4. Выбрать беспроводной Pixel как устройство запуска и нажать Run.

Через терминал:

```sh
adb pair IP_ТЕЛЕФОНА:ПОРТ_СОПРЯЖЕНИЯ
```

Ввести код из экрана «Подключить устройство с помощью кода сопряжения». Если после pairing устройство не появилось автоматически:

```sh
adb connect IP_ТЕЛЕФОНА:ПОРТ_ПОДКЛЮЧЕНИЯ
adb devices -l
```

Порт подключения указан на основном экране «Беспроводная отладка» и обычно отличается от pairing port. После изменения сети или перезапуска отладки адрес/порт могут измениться. Для debug-сборки оставить Metro запущенным и повторить `adb -s IP_ТЕЛЕФОНА:ПОРТ_ПОДКЛЮЧЕНИЯ reverse tcp:8081 tcp:8081`; после переподключения проброс проверить заново. Это позволяет не открывать Metro всему LAN. При двойном подключении выбрать serial Wi‑Fi в `ANDROID_SERIAL` либо отключить USB. [ADB и Wi‑Fi pairing](https://developer.android.com/tools/adb).

Не использовать старый режим `adb tcpip 5555`: для этого устройства доступно штатное сопряжение Wireless debugging.

## 6. Первый сквозной тест SIWC

| Шаг | Что фиксируем |
| --- | --- |
| 1. Запустить приложение | Версия сборки, Android и браузера |
| 2. Continue with ChatGPT | Браузер открылся на телефоне |
| 3. Выбрать аккаунт/workspace, разрешить использование плана | Есть явное согласие пользователя |
| 4. Дождаться callback и вернуться | ID-токен проверен; plan scope получен |
| 5. Загрузить модели | Каталог аккаунта получен; видимые модели доступны |
| 6. Отправить короткий запрос | Появились delta и затем `response.completed` |
| 7. Закрыть и открыть приложение | Сессия читается; запрос можно повторить |
| 8. Установить сборку со встроенным JS bundle, остановить Metro, отключить USB/Wi‑Fi debugging | Холодный запуск и генерация работают без связи с Mac, интернет телефона остаётся |

USB/Wi‑Fi debugging, Metro и доступ к OpenAI — разные соединения. `adb reverse tcp:8081 tcp:8081` нужен только для debug JavaScript bundle. OAuth callback принимает телефон на своём динамическом порту, этот порт не пробрасывается. Вход нельзя завершать в браузере Mac: его `127.0.0.1` указывает на другой компьютер. Mac не выступает OpenAI-прокси; ngrok не нужен.

Форма отчёта:

```text
Дата:
Сборка приложения:
Android / API:
Браузер / версия:
План: Plus или Pro (без email)
Этапы: browser / callback / token validation / plan scope / models / response.completed
Выбранная модель:
Результат: подтверждён / eligibility отказ / лимит / техническая ошибка / не проверено
HTTP status / error code / request ID при ошибке:
Наблюдение без секретов:
```

## 7. Быстрое устранение проблем

| Симптом | Действие |
| --- | --- |
| Пустой `adb devices` | Разблокировать Pixel; проверить кабель с данными, USB-порт и USB debugging |
| `unauthorized` | Подтвердить RSA-запрос на телефоне; если его нет — переподключить кабель, при необходимости отозвать USB authorizations и подтвердить заново |
| `offline` | Переподключить; если не помогает, `adb kill-server`, затем `adb start-server` (затронет другие ADB-сессии) |
| `more than one device` | Выбрать `ANDROID_SERIAL` или использовать `adb -s SERIAL ...` |
| Wi‑Fi pairing успешен, устройства нет | Проверить другой connection port, выполнить `adb connect`; исключить guest Wi‑Fi/AP isolation/VPN, мешающий локальной связи |
| `SDK location not found` | Проверить SDK Manager и `ANDROID_HOME`; IDE может создать игнорируемый `mobile/android/local.properties` с `sdk.dir=/Users/first/Library/Android/sdk` |
| Несовместимый Gradle/JDK | Выбрать JDK 17 и Wrapper из mobile/android; не использовать прежний Gradle 8.13 |
| Unable to load script / connection to Metro | Проверить Metro из mobile/, порт 8081 и adb reverse выбранного устройства; debug APK не автономен |
| TurboModule не найден | Проверить Codegen/регистрацию, пересобрать APK; Fast Refresh не добавляет Kotlin-код |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Установленная версия подписана другим ключом. Не удалять вслепую: удаление потеряет сессию; сначала решить, нужен ли полный сброс |
| Callback `connection refused` | Проверить, что вход открыт на телефоне, listener уже запущен, процесс жив, порт/путь совпали и браузер в том же профиле |
| AwaitingReturn / локальный callback в браузере | Вернуться в приложение через Back или список приложений: обмен кода начинается на переднем плане, ожидание до 2 минут |
| После браузера экран входа | Проверить тайм-аут/убийство процесса; начать новую попытку, не переиспользовать старый code |
| Есть вход, нет plan scope | Проверить согласие на Use your ChatGPT plan; inference пока недоступен |
| Есть текст, затем ошибка | Частичный ответ не подтверждает успех; нужен `response.completed` |

Подробные критерии и ограничения прототипа: [ANDROID_SIWC_SPEC.md](ANDROID_SIWC_SPEC.md).
