# Первый React Native SIWC-прототип

Обновлено: 25 октября 2026 года. Платформа сборки: Android arm64, min API 31, целевое устройство Pixel 10 Pro.

## Git

Локальный репозиторий инициализирован на ветке `main`. Первый коммит `1c55801` сохраняет спецификацию и инструменты до реализации. Remote не настроен, публикация на GitHub не выполнялась.

## Реализовано

- React Native / TypeScript: один экран входа, разрешений, выбора модели, запроса, ответа и диагностики.
- Kotlin TurboModule и Codegen. Состояние хранится в процессе Android, JS получает полные безопасные snapshots с revision.
- OAuth Authorization Code + PKCE, state/nonce, динамический client ID, браузер телефона и listener на `127.0.0.1`.
- Проверка подписи ID-токена по JWKS, issuer, audience, срока и nonce. Проверка plan scope отдельно от личности.
- AES-GCM / Android Keystore, атомарное сохранение в no-backup storage, refresh и выход с попыткой revocation.
- Каталог моделей аккаунта и Responses API с OAuth bearer, `store:false`, `stream:true`; подтверждение успеха по `response.completed`.
- Отмена операций, безопасные коды ошибок, диагностика через системное меню Share без токенов/email/ответа.
- Локальная release-подпись стандартным debug-ключом машины, JS bundle включается в release. Это конфигурация для тестирования, не публикации.

## Фактические проверки

| Проверка | Результат |
| --- | --- |
| TypeScript | Прошёл |
| ESLint | Прошёл |
| Jest: исключение приватных данных из диагностики | 1 тест прошёл |
| Android JVM unit tests | 18 тестов, 0 ошибок; 8 проверяют восстановление refresh |
| Codegen, Kotlin, JNI | Скомпилированы |
| Debug APK | Собран |
| Android lint | 0 ошибок, 17 предупреждений (включая шаблон/RN, версии зависимостей и application-context singleton) |
| Release APK | Собран; подпись APK v2 проверена; JS bundle включён, debuggable/cleartext выключены |
| Запуск, браузерный callback и запрос на Pixel | Успешно 25 октября: «Связь работает.», response.completed; [отчёт](DEVICE_TEST_2026-10-25.md) |

JVM-тесты проверяют неверные/повторённые state, точный callback path, подпись и claims JWT, каталог `models`, SSE с фрагментированным вводом и отсутствие ложного completion на `[DONE]`/обрыве события. Это не заменяет сквозной тест аккаунта на телефоне.

Выход и повторный вход также проверены на Pixel: revocation HTTP 200, SignedOut после холодного запуска, новый вход и ответ модели успешны. Обмен кода после browser callback теперь ожидает возврата приложения в foreground. Подробности и границы — в [отчёте устройства](DEVICE_TEST_2026-10-25.md).

## Ограничения v0

Ревью версии `f97b12e`: [CODE_REVIEW_V0_2026-10-25.md](CODE_REVIEW_V0_2026-10-25.md). R-01 и R-02 исправлены: все терминальные коды refresh распознаются, ответ с заменой токена сохраняется зашифрованным до проверки JWKS. После сбоя/отмены проверка продолжается без повторного использования старого refresh-токена. Добавлены 8 JVM-тестов; на реальном аккаунте refresh ещё не проверен. Исправленные ошибки и способы диагностики: [SIWC_ERROR_LOG.md](SIWC_ERROR_LOG.md).

- Один зарегистрированный аккаунт. Другой аккаунт с тем же client ID отклоняется; интерфейс переключения регистраций пока отсутствует.
- Браузер открывается через системный ACTION_VIEW. После callback нужно вручную вернуться в приложение.
- Незавершённая авторизация не переживает убийство процесса; тайм-аут — 5 минут.
- APK собран только для arm64. Запуск и основной сценарий проверены на Pixel 10 Pro / Android 17.
- Проблема с защищённым хранилищем требует явного сброса данных/нового входа; автоматического удаления регистрации нет.
- Доступ подписки для минимального текстового запроса подтверждён на Pixel 25 октября. TTS и генерации подкастов в версии нет.

Release APK: `mobile/android/app/build/outputs/apk/release/app-release.apk`, около 25 MiB. Debug APK: `mobile/android/app/build/outputs/apk/debug/app-debug.apk`, около 45 MiB. Артефакты сборки игнорируются Git. При создании v0 собраны оба варианта; после последнего исправления проверены `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`, `BUILD SUCCESSFUL`. Debug APK после последнего исправления не пересобирался. После исправления refresh снова успешно выполнены 18 JVM-тестов, lint и assembleRelease, а также Jest, TypeScript и ESLint. APK с этими исправлениями собран локально, на Pixel в этой задаче не установлен. Проверки исходного ревью отдельно перечислены в его отчёте.

## Первый запуск

Подключить Pixel по USB, включить USB debugging и подтвердить RSA-запрос. Из корня проекта:

```sh
source tools/android-env.sh
adb devices -l
adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n dev.podcast.siwcspike/.MainActivity
```

На телефоне: Continue with ChatGPT → разрешить использование подписки → вернуться в приложение → загрузить модели → получить ответ. Успех — статус `Запрос успешно завершён` и `response.completed`. При ошибке воспользоваться «Поделиться диагностикой».

Подробный цикл разработки через Metro и Wi‑Fi: [ANDROID_DEVICE_TESTING.md](ANDROID_DEVICE_TESTING.md).
