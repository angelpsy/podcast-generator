# Этап 0: Room/KSP и React Navigation

25 октября 2026 года, Asia/Tbilisi. **Оставшийся пункт этапа 0 закрыт:** выбранные зависимости собраны в текущем проекте и проверены на Pixel 10 Pro / Android 17. Предыдущие четыре проверки — в [протоколе SIWC/TTS/плеера](STAGE0_V2_TEST_2026-10-25.md). Продуктовая библиотека и интерфейс v2 ещё не реализованы.

## Проверенная конфигурация

| Компонент | Версия |
| --- | --- |
| React Native / React | 0.86.3 / 19.2.3 |
| Kotlin / KSP | 2.1.20 / 2.1.20-2.0.1, явно `ksp.useKSP2=true` |
| Gradle / Android Gradle Plugin | 9.3.1 / 8.12.0 |
| JDK / NDK | 17 / 27.1.12297006 |
| min / compile / target SDK | 31 / 36 / 36 |
| Room runtime/compiler | 2.8.5 |
| `@react-navigation/native` | 7.5.1 |
| `@react-navigation/native-stack` | 7.21.0 |
| `@react-navigation/bottom-tabs` | 7.21.0 |
| `react-native-screens` | 4.30.0 |
| `react-native-safe-area-context` | 5.10.1, прежняя установленная версия |
| Renderer / JS | Fabric / Hermes |

React Native, React, Kotlin, Gradle и существующий safe-area-context не обновлялись. Навигационные пакеты закреплены точными версиями в package.json и package-lock.json; `npm ls` не выявил конфликтов peer dependencies. Screens 4.30 используется с Fabric, как и требует эта ветка. [Официальная установка навигации](https://reactnavigation.org/docs/getting-started/), [совместимость и настройка Screens](https://github.com/software-mansion/react-native-screens).

KSP выбран под текущую линию Kotlin, без переноса проекта на новый компилятор. Room подключён через `stageZeroImplementation`, процессор — только через `kspStageZero`; тестовая БД не добавлена в обычный release. [Подключение Room](https://developer.android.com/jetpack/androidx/releases/room), [релиз KSP](https://github.com/google/ksp/releases/tag/2.1.20-2.0.1).

## Room: компиляция и настоящий SQLite

Сработала задача `:app:kspStageZeroKotlin`, созданы `StageZeroDatabase_Impl.kt`, `ProbeDao_Impl.kt` и экспорт схемы `app/schemas/stageZero/dev.podcast.siwcspike.podcast.StageZeroDatabase/2.json`. Room processor и generated-код компилируются текущим Kotlin/Gradle.

На Pixel выполнен mode `room`, результат **PASS**, `INSTRUMENTATION_CODE:-1`:

- создана отдельная SQLite-БД версии 1 в cache со строкой на русском языке;
- Room выполнил явную миграцию 1→2 с добавлением NOT NULL language и default `ru`; старые данные сохранены, схема провалидирована;
- вставка/чтение/переименование и порядок вопросов работают;
- замена вопросов с повторяющимся primary key вызвала SQLiteConstraintException; транзакция откатилась, прежние вопросы остались;
- внешний ключ отклонил вопрос для отсутствующего топика;
- после закрытия и повторного открытия данные сохранились;
- удаление топика каскадно удалило его вопросы.

Тест использовал фактический `StageZeroDatabase_Impl`, созданный KSP. БД имеет уникальное имя, удаляется в finally, не читает SIWC storage и не изменяет основной journal подкаста; его неизменность проверена отдельно.

Это проверка механизма миграций Room на маленькой тестовой схеме. Миграция настоящих `current.json`/draft в продуктовую библиотеку v2 остаётся этапом 1.

## React Navigation: реальный интерфейс на Pixel

StageZeroNavigationActivity открывает отдельный React Native component с native-stack и тремя bottom-tabs. Для Activity установлен `RNScreensFragmentFactory` до super.onCreate — по инструкции установленного Screens. Изменения тестового экрана и регистрации компонента находятся в отдельном `index.stageZero.js`; Gradle выбирает его только для bundle-задачи stageZero.

Mode `navigation` использует платформенный UiAutomation без дополнительной библиотеки UI-тестов. Back отправляется через системный KEYCODE_BACK; физический predictive-back gesture отдельно не проверялся. Проверено:

| Сценарий | Результат |
| --- | --- |
| Три вкладки и Android Back по истории вкладок | Работают |
| Два вложенных native-stack экрана | Открываются, параметры маршрута корректны |
| Android Back из вложенных экранов | Возвращает последовательно к предыдущему экрану и вкладкам |
| Счётчик после смены вкладок и push/pop | Состояние сохраняется |
| Переключение светлой/тёмной темы NavigationContainer | Работает, счётчик не сбрасывается |
| Home и возврат к существующей Activity | Приложение действительно уходит из активного окна; после возврата тема и счётчик сохранены |
| Activity.recreate из экрана деталей | Новая Activity создаётся, native-crash нет, тестовый React UI возвращается к начальной вкладке |

Первый автоматический запуск завершился таймаутом UI-ожидания; точный шаг в исходном отчёте не фиксировался. Сам экран при отдельном запуске отображался, ошибок React Native/AndroidRuntime этого запуска не обнаружено. После добавления диагностики шагов весь прогон прошёл. Затем в тесте добавлены проверка видимости и ожидание UI idle, чтобы действия не опережали переходы, а Home проверяется по смене активного пакета. **Два последовательных запуска финального теста прошли полностью.** Первый таймаут не считается доказанной ошибкой зависимости; его точная причина не установлена.

Room и navigation reports имеют `mainJournalUnchanged:true`. Ни один из этих режимов не запрашивает новый Responses или TTS. Пользовательский подкаст не перегенерировался.

## Сборки и регрессии

Успешно: stageZero APK и тестовый APK, обычный release, 60 JVM-тестов, TypeScript, ESLint, Jest (1 тест). Lint debug: 0 ошибок / 21 предупреждение; stageZero: 0 ошибок / 22 предупреждения. Состав APK проверен: тестовые StageZeroDatabase и navigation component отсутствуют в обычном release и присутствуют в stageZero. Нативный Screens автолинкуется штатно; основной экран приложения пока остаётся v1.2.

Дополнительное предупреждение stageZero — SetTextI18n для строкового литерала тестового экрана; это не ошибка совместимости. Остальные предупреждения относятся к существующей конфигурации/зависимостям.

| Артефакт | Размер, байт | SHA-256 |
| --- | --- | --- |
| `app-stageZero.apk` | 192 868 560 | `cb9360bafd288525f729ebd32a4a091d6213687f71e464cc1813bacc7fb3f548` |
| `app-stageZero-androidTest.apk`, финальный | 69 084 | `147fab93e170e25a6f34ba447eede05d7354d5674b3bcb3783b730d35db6bdd6` |

После проверки на Pixel возвращена исходная 1.2 / code 3, APK hash прочитан с устройства и совпал: `7ed7c9695007b89b1bbc892b8c4f1f1a4cb6fea904277af409db0f90767fc290`; тестовый APK удалён. В 14:52:03–14:52:05 обычный клиент подтвердил `Ready → LoadingModels → HTTP 200 → Ready`, повторный OAuth-вход не понадобился. Отчёты и stdout сохранены на Mac в `.tooling/stage0-compat/`. Экспериментальная папка отчётов на Pixel очищена, в MediaStore-папке остался один прежний M4A с неизменным SHA-256 `c7686961d080bbc898b34a4505589b98104817511e52bc39dd69c2dd94a839e2`.

## Воспроизведение

Из `mobile/android` после `source ../../tools/android-env.sh`:

```sh
./gradlew :app:assembleStageZero :app:assembleStageZeroAndroidTest -PstageZeroChecks=true
adb install -r app/build/outputs/apk/stageZero/app-stageZero.apk
adb install -r app/build/outputs/apk/androidTest/stageZero/app-stageZero-androidTest.apk
adb shell am instrument -w -e mode room dev.podcast.siwcspike.test/dev.podcast.siwcspike.podcast.StageZeroChecks
adb shell am instrument -w -e mode navigation dev.podcast.siwcspike.test/dev.podcast.siwcspike.podcast.StageZeroChecks
```

Для навигации Pixel должен быть разблокирован. Ожидается `result:PASS` и `INSTRUMENTATION_CODE:-1`, exit code ADB сам по себе недостаточен. Локальную копию исходного APK сохранять до экспериментальной установки; `assembleRelease` уже содержит новые зависимости и не заменяет backup предыдущей сборки.

## Решение для следующего этапа

Использовать проверенные версии Room/KSP и React Navigation без обновления RN/Kotlin. На этапе 1 перенести Room runtime/compiler в нужную продуктовую конфигурацию, ввести настоящую схему и отдельно проверить импорт legacy JSON. При переносе native-stack в MainActivity применить проверенную настройку fragment factory.

Не проверялись: другие Android-устройства, iOS, full UI v2, глубокие ссылки, восстановление навигационного маршрута после process death, большие библиотеки и реальные миграции их будущих схем. Эти проверки не являются блокерами закрытого этапа 0 и относятся к реализации/приёмке следующих этапов.
