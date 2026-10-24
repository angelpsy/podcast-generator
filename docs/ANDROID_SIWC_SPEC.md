# React Native Android SIWC Spike — техническая спецификация

Дата: 24 октября 2026 года. Статус: первый прототип реализован; фактические проверки отражены в [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

## Цель и границы

На реальном Pixel 10 Pro подтвердить цепочку: приложение → вход в ChatGPT в браузере телефона → согласие на использование подписки → проверка личности и разрешений → один короткий ответ OpenAI через OAuth.

Только прямой официальный поток SIWC и Responses API. Не использовать API key, токены Codex, cookies ChatGPT, backend-api, сервер на Mac или платный fallback. Mac нужен только для разработки и ADB. TTS, подкасты, база выпусков, сервер, фоновые задания и публикация в Google Play вне этой версии.

Официальные источники описывают личные локальные проекты и подходящие Plus/Pro-аккаунты, но успешность конкретной связки аккаунт/workspace/Android устанавливается тестом: [Cookbook](https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt), [Quickstart](https://developers.openai.com/siwc/quickstart).

## Платформа и зависимости

- **React Native 0.86.3 + React 19.2.3 + TypeScript 5.9.3**, Community CLI 20.1.0, npm и `package-lock.json`. Один экран без навигационного фреймворка; New Architecture и Hermes из шаблона. Expo Go не используем: нужен собственный Android-модуль с loopback listener. Expo development build возможен технически, но в эту версию не входит.
- Проект находится в `mobile/`: `App.tsx`, `src/`, `specs/NativeSiwc.ts`, `android/`. Код первого прототипа уже добавлен. Android Studio открывает `mobile/android`, Metro запускается из `mobile`.
- Application ID / namespace: `dev.podcast.siwcspike`; launcher: `.MainActivity`; название: `Podcast Generator SIWC Spike`. `MainActivity` — ReactActivity, а не отдельный Kotlin-экран.
- `minSdk=31` для этого эксперимента; `compileSdk=36`, `targetSdk=36`, Build Tools 36.0.0, NDK 27.1.12297006, CMake 3.22.1. Сохраняем базу RN-шаблона, повышая только его minSdk с 24 до 31. На Pixel записать фактическую версию Android.
- Node.js 24.16.0 из существующего nvm, npm 11.13.0, JDK 17. Wrapper 9.3.1, AGP 8.12.0, Kotlin 2.1.20 — база RN 0.86, не прежняя самостоятельная Kotlin-конфигурация. При создании проекта сверить опубликованный шаблон 0.86.3 и RNGP, записать точные версии в lockfiles; не смешивать с AGP 8.13.2 / Gradle 8.13 из предыдущего этапа.
- TypeScript/Babel/Metro/ESLint/Jest/Codegen брать из версии шаблона 0.86.3, зафиксировать разрешённые версии через npm lockfile. Не устанавливать глобальный `react-native-cli`. Watchman опционален, для первого запуска достаточно Node watcher.

Для Wrapper Gradle 9.3.1 зафиксировать `distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`. Этот дистрибутив уже скачан с официального CDN, проверен по SHA-256 и запускается на установленном JDK 17.

| Слой | Зависимости |
| --- | --- |
| React Native UI | React Native core components, React hooks; `react-native-safe-area-context` из шаблона для insets |
| Связь с Android | Собственный типизированный Turbo Native Module `NativeSiwc`, интерфейс генерируется Codegen |
| Браузер | `androidx.browser:browser:1.9.0`, fallback ACTION_VIEW |
| Асинхронность Kotlin | `kotlinx-coroutines-android:1.10.2` |
| HTTP/SSE | OkHttp версии, согласованной с runtime classpath RN; не навязывать прежний OkHttp 5.1.0 без проверки зависимостей |
| Проверка JWT | `com.nimbusds:nimbus-jose-jwt:10.10` |
| Токены | Android Keystore + приватный шифрованный файл; без AsyncStorage |

В Kotlin JSON разбирается через `org.json`. Redux, React Navigation, Retrofit, OpenAI SDK, AppAuth, Hilt и Room не нужны. Нативный модуль нужен для socket/callback и доступа к системному хранилищу; сетевой путь также оставляем в нём, чтобы не передавать OAuth-токены в JavaScript и не зависеть от реализации streaming fetch в RN. Криптографию JWT вручную не писать.

Источники: [активные версии RN](https://reactnative.dev/releases/), [Community CLI workflow](https://reactnative.dev/docs/getting-started-without-a-framework), [Turbo Modules](https://reactnative.dev/docs/turbo-native-modules-introduction), [Android template](https://github.com/react-native-community/template/tree/0.86-stable/template/android), [RN version catalog](https://github.com/facebook/react-native/blob/v0.86.0/packages/react-native/gradle/libs.versions.toml). Состояние фактической установки — в [LOCAL_ENVIRONMENT.md](LOCAL_ENVIRONMENT.md).

Ветка 0.86 выбрана из активных поддерживаемых веток: её шаблон использует уже доступный SDK 36. Проверка SDK Manager для первоначально рассмотренной 0.87 показала отсутствие `platforms;android-37`; переход на неё не нужен для проверки SIWC. Точную совместимость Android-сборки подтвердим после создания проекта; сейчас проверены опубликованные требования, а не APK.

## Экран

Показывать последовательно:

1. `Continue with ChatGPT`; текущий этап; кнопка отмены во время авторизации.
2. После проверки: email, статус `Подписка разрешена / не разрешена`. Email только из проверенного ID-токена, не из содержимого непроверенного JWT.
3. Кнопка загрузки моделей; список `display_name` с сохранением соответствующего `slug`. Первый видимый вариант можно предвыбрать; отправка только отдельной кнопкой.
4. Поле запроса, предзаполненное `Ответь одним коротким предложением: связь работает.`; кнопка `Отправить`.
5. Нарастающий ответ; финальный статус `Запрос завершён` только при `response.completed`.
6. `Повторить`, `Выйти`, `Скопировать диагностику`. Во время операции отключать повторный запуск той же операции.

Диагностика содержит этап, время, версию Android/приложения, выбранную модель, HTTP status, безопасный error code, request ID и тип терминального события. Не показывает токены, authorization code, полный callback URL, ID token, Authorization header, PKCE verifier, state/nonce, email или необработанные серверные тела. Текст ответа виден на экране, но не копируется в диагностику по умолчанию. Logcat: только фиксированные события с тегом `SiwcSpike`.

## Состав кода

| Компонент | Ответственность |
| --- | --- |
| `App.tsx` / `SiwcScreen.tsx` | React Native UI, поля, кнопки, ответ |
| `useSiwc` / reducer | Отображение проверенного native snapshot, подписка на события, пользовательские действия |
| `specs/NativeSiwc.ts` / Kotlin TurboModule | Типизированная граница JS ↔ Android, Codegen; без передачи токенов |
| `AuthCoordinator` | Одна активная попытка входа; процессный CoroutineScope, не lifecycleScope экрана |
| `LoopbackCallbackServer` | Короткоживущий ServerSocket, только `127.0.0.1`, точный путь callback |
| `OpenAiOAuthClient` | Discovery, URL авторизации, обмен кода, refresh, revocation |
| `IdTokenValidator` | JWKS, разрешённые алгоритмы и claims |
| `CredentialStore` | Шифрованное локальное хранение |
| `OpenAiInferenceClient` | Каталог моделей, POST Responses, SSE |
| `SafeDiagnostics` | Только разрешённые поля и фиксированные сообщения |

Нативные зависимости создаются вручную в process-scoped контейнере Application. Источник состояния операций — Kotlin coordinator; React hook отображает его snapshot, а не запускает второй OAuth state machine. UI-состояния: `SignedOut → Authorizing → Validating → SignedInWithoutPlan / Ready → LoadingModels → Ready → Streaming → Completed / Failed / Incomplete`. Ошибка сети сама по себе не превращает `Ready` в `SignedOut`.

### Контракт TurboModule

Методы: `getSnapshot()`, `signIn()`, `cancel()`, `listModels()`, `sendPrompt(modelSlug, text)`, `signOut()`. Async-операции возвращают Promise; для прогресса и delta — Codegen EventEmitter. В v0 события и snapshots передаются JSON-строками (`EventEmitter<string>`); диагностика собирается JS-функцией `safeDiagnostics`. В JS доступны только безопасные DTO: проверенный email, planEnabled, модели, текст ответа, статусы и безопасные ошибки. `getSnapshot` возвращает и накопленный текст, чтобы потерянное событие не означало потерю ответа.

Подписываться до старта операции. Snapshot и события содержат operation ID и монотонную revision; reducer игнорирует устаревшие события и повторные delta. Снятие подписки освобождает JS listener. Пересоздание React context/Fast Refresh не удаляет native auth session: новый UI переподписывается и сверяется с snapshot. Pending OAuth принадлежит Application, не ReactActivity, hook или экземпляру TurboModule. Если процесс погиб, действует обычный новый вход. Проверять эту границу при Reload отдельно от force-stop.

Codegen-спецификация входит в `package.json` через `codegenConfig`; использовать только поддерживаемые Codegen DTO. Автогенерируемые файлы не редактировать. Auth и SSE выполняются на IO executor/coroutine dispatcher; вызовы TurboModule не блокируют UI/JS thread.

## Вход и callback

Контракт: [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [Overview](https://developers.openai.com/siwc/token-sharing-open-source).

Сохранить один `ext_agent_host_id` формата `urn:uuid:<UUIDv4>` на установку. При первом входе использовать `dynamic_agent_client`, `agent_name_hint=Podcast Generator SIWC Spike`; далее — выданный `client_id`. Host ID стабилен при перезапуске и выходе.

Перед запуском браузера слушать `127.0.0.1` на свободном порту. Redirect: `http://127.0.0.1:<port>/auth/callback`, без `localhost`. В authorize и token exchange URI полностью одинаков. Только порт может меняться между попытками.

Параметры: `response_type=code`, `resource=https://api.openai.com/v1`, scopes `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`, случайные `state`, `nonce`, PKCE S256. Начальная регистрация возвращает выданный `client_id`; именно его использовать при обмене кода. Код одноразовый.

ID-токен проверить по JWKS OpenAI: signature, issuer, audience, expiration, nonce; запретить `alg=none` и неожиданные алгоритмы. Отдельно проверить granted scope `chatgpt.tokens.use.direct`; ID-токен не заменяет это разрешение.

Discovery получать по HTTPS с фиксированного `https://auth.openai.com/.well-known/openid-configuration`. Принимать только ожидаемый issuer; не следовать произвольным адресам из JWT (`jku` и т. п.). Неизвестный `kid`: один раз обновить JWKS, затем ошибка. Не отключать проверку TLS.

### Особенности Android — решения прототипа

- Браузер и приложение работают в личном профиле одного телефона. На Android 17 межпрофильный loopback ограничен: [изменения Android 17](https://developer.android.com/about/versions/17/behavior-changes-all).
- Listener живёт в процессе приложения, вне Activity; не останавливается на `onStop`, повороте и открытии браузера. Срок ожидания — 5 минут; отмена/успех/истечение срока закрывают сокет и pending attempt.
- В v0 нет foreground service и обещания переживать убийство процесса. После потери процесса незавершённый вход повторяется с новыми state/nonce/verifier; старый callback не принимается. Проверить это отдельно от обычного переключения в браузер.
- Ответ callback-страницы: короткий текст «Вход обработан. Вернитесь в приложение» без токенов, отражённых параметров и скриптов. Пользователь закрывает вкладку/нажимает Back или возвращается через список приложений. Не рассчитывать на автоматический запуск Activity из фона.
- Только GET `/auth/callback`; ограничить заголовки 16 KiB и время чтения соединения 5 сек. Неверные state/путь/дубли параметров отклонять без обмена кода; случайный `/favicon.ico` не завершает попытку. Валидный callback потребляется атомарно один раз.
- Manifest: `INTERNET`; launcher Activity exported, прочие компоненты закрыты, backup для auth-данных отключён и исключения data extraction. OpenAI-запросы идут только по HTTPS. Входящий raw loopback socket не требует глобального `usesCleartextTraffic=true`. Отдельное HTTP-разрешение debug-варианта для Metro 8081 допустимо только в debug source set; в сборку с включённым JS bundle оно не переносится. Политика браузера независима от приложения.
- При target 36 не запрашивать `ACCESS_LOCAL_NETWORK`; требования при переходе на target 37 проверить отдельно: [локальная сеть](https://developer.android.com/privacy-and-security/local-network-permission).

## Хранение, повторный вход, выход

Хранить проверенную личность, issued client ID, host ID, scopes, срок доступа, access/refresh/ID tokens. Токены шифровать AES-GCM ключом Android Keystore, ciphertext — в приватном `noBackupFilesDir`, новая IV на запись. Host ID и mapping также не восстанавливать из backup на другое устройство. Не хранить секреты в SavedStateHandle, intent extras, URL-журналах или на Mac. При недоступном ключе предложить новый вход.

Перед запросом обновить истекающий access token через `refresh_token` grant; обновление сериализовать, новый refresh token сохранять атомарно. При 401 — максимум одно обновление и повтор, кроме терминальных ошибок. При `invalid_grant` и подтверждённой недействительности токена — новый вход с сохранённым client ID. Повторный вход обязан совпасть по проверенному `sub` с сохранённой регистрацией. Для v0 один активный аккаунт; смена пользователя требует явного действия и новой регистрации, не перезаписи чужого mapping.

Выход: остановить запросы, попытаться отозвать refresh token по discovery revocation endpoint, очистить токены; сохранить host ID и mapping для повторного входа. При недоступной сети сообщить, что удалённый отзыв не подтверждён, дать путь отключения приложения в ChatGPT. [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

## Минимальный запрос

По OAuth access token получить `GET https://api.openai.com/v1/models`; читать `models[]`, оставлять `visibility == "list"`, передавать `slug` выбранной модели. Не предполагать обычную API-схему `data[]` и не фиксировать модель заранее.

`POST https://api.openai.com/v1/responses`, `Authorization: Bearer <access_token>`, `Content-Type: application/json`, тело:

```json
{
  "model": "<slug из каталога аккаунта>",
  "input": [{"role": "user", "content": "Ответь одним коротким предложением: связь работает."}],
  "store": false,
  "stream": true
}
```

Обрабатывать SSE по границам событий и пустой строке, а не по границам TCP-чанков; учитывать несколько `data:` строк. `response.output_text.delta` добавляет текст; `response.completed` подтверждает успех. `response.failed`, `response.incomplete`, обрыв и timeout не являются успехом, даже если часть ответа уже появилась. Не считать `[DONE]` достаточным подтверждением. Неизвестные служебные события пропускать.

Connect timeout 15 сек, чтение/простой потока 60 сек, общий deadline inference 120 сек; отмена закрывает HTTP call. Короткость задаётся prompt, не гарантируется жёстким лимитом. Автоповтор POST после частичного ответа запрещён: повтор только кнопкой пользователя.

Не отправлять `temperature`, `max_output_tokens`, `previous_response_id`, `background` и другие неподдерживаемые поля. Запрос независимый, без истории. [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

## Ошибки и интерпретация результата

| Ситуация | Поведение / вывод |
| --- | --- |
| Пользователь отменил OAuth | Остаться без подключения, без повторного открытия браузера |
| Нет plan scope | «Вход выполнен, использование подписки не разрешено»; inference отключён |
| `subscription_sharing_user_not_eligible` | Недоступно выбранному аккаунту/workspace/политике; прекратить повторные попытки |
| `subscription_sharing_usage_limit_exceeded` | Доступ есть, лимит мешает запросу; показать путь ChatGPT Settings → Usage |
| `subscription_sharing_unsupported_capability` | Ошибка параметров/возможности, не доказательство неподходящей подписки |
| 403 без стандартного error object | Показать безопасный статус/код/request ID; не объявлять причину без подтверждения |
| 503 / временная сеть | Доступ не опровергнут; ручной повтор, без смены биллинга |
| Нет callback | Отдельная проблема Android/browser/lifecycle; не вывод об аккаунте |

Нестандартные тела вроде `{"detail":"..."}` разбирать как диагностический ответ, не как стабильный код. [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery).

## Приёмка и необходимые проверки

Положительный результат: на Pixel завершены OAuth и проверка ID-токена, получен plan scope, загружен каталог моделей, непустой ответ завершён `response.completed`. Не вводился API key, запрос ушёл с OAuth bearer. Независимость от Mac проверяется только в локально подписанной сборке со встроенным JS bundle: обычный RN debug APK зависит от Metro. Это подтверждает только минимальную текстовую генерацию, не TTS и не длительные сценарии.

Проверить на устройстве: первый вход; отмена; повторный вход; поворот; возврат из браузера; Fast Refresh/Reload без потери native состояния; force-stop во время входа и новый вход; перезапуск с сохранённой сессией; обновление токена; отсутствие сети; выход; отзыв доступа в ChatGPT; проверка отсутствия секретов в Logcat, JS console и DevTools. Исчерпание лимитов не провоцировать специально.

До сквозного запуска нужны целевые локальные тесты: неверный state и повтор callback не обменивают код; неверные nonce/aud/signature отклоняются; каталог читается из `models`; SSE корректен при разрывах чанков и failed после delta; прерванный поток не даёт Completed. Можно использовать MockWebServer, без реальных учётных данных.

В отчёт записать дату, версию Android, браузер/версию, сборку приложения, выбранную модель, успех каждого этапа, duration и безопасный request ID ошибки. Разделять результаты «доступ подтверждён», «отказ eligibility», «лимит», «техническая ошибка», «не проверено».

## Порядок реализации следующего этапа

1. Создать `mobile/` Community CLI 20.1.0 с RN 0.86.3, TypeScript, npm lockfile и Wrapper шаблона с SHA-256; настроить Android ID. Только Android входит в приёмку.
2. Добавить TurboModule/Codegen и React Native экран; проверить listener + браузер на Pixel, затем полный OAuth с валидацией.
3. Подключить каталог, native SSE, события и snapshot в JS reducer.
4. Проверить хранение, повторный запуск, refresh и выход.
5. Пройти приёмку из этой спецификации и заполнить отчёт.

Порядок выше использован для первого прототипа. Точные результаты сборки, отличия v0 и оставшаяся приёмка — в [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).
